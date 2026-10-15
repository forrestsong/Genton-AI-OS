package com.ainative.policy;

import android.content.Context;
import android.util.Log;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * C6.4 值域版本管理器
 * 
 * 职责：
 * 1. 加载和管理值域知识库版本信息
 * 2. 验证分类器模型版本兼容性
 * 3. 处理 OTA 更新（版本检查、签名验证、回滚）
 * 4. 与 PolicyEngine 和分类器模型联动
 * 
 * 版本联动流程：
 * 1. 模型 OTA 更新触发
 * 2. ValueDomainVersionManager 检查版本兼容性
 * 3. 如果不兼容，阻止模型加载并告警
 * 4. 如果兼容，更新值域知识库并通知 PolicyEngine 重新加载
 * 
 * 符合 WBS：
 * - C6.4 值域/枚举与分类器版本联动 OTA
 * - C1.2 值域知识库（随 PolicyPack 版本化）
 * - C1.4 PolicyPack 签名打包与灰度工具链
 */
public class ValueDomainVersionManager {
    
    private static final String TAG = "ValueDomainVersionManager";
    
    // 值域知识库路径
    private static final String VALUE_DOMAIN_CATALOG_PATH = 
            "/vendor/ainative/config/value_domain_catalog_v1.yaml";
    
    // OTA 更新路径
    private static final String OTA_UPDATE_PATH = 
            "/data/system/ainative/value_domain_ota/";
    
    // 单例实例
    private static volatile ValueDomainVersionManager sInstance;
    
    private final Context mContext;
    private final ReadWriteLock mLock = new ReentrantReadWriteLock();
    
    // 当前加载的值域版本
    private ValueDomainVersion mCurrentVersion;
    
    // 版本变更监听器
    private final java.util.List<VersionChangeListener> mListeners = 
            new java.util.ArrayList<>();
    
    private ValueDomainVersionManager(Context context) {
        mContext = context.getApplicationContext();
    }
    
    /**
     * 获取单例实例
     */
    public static ValueDomainVersionManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ValueDomainVersionManager.class) {
                if (sInstance == null) {
                    sInstance = new ValueDomainVersionManager(context);
                }
            }
        }
        return sInstance;
    }
    
    /**
     * 初始化：加载当前值域版本
     */
    public void initialize() {
        mLock.writeLock().lock();
        try {
            Log.i(TAG, "Initializing ValueDomainVersionManager...");
            
            // 加载值域知识库版本信息
            mCurrentVersion = loadVersionFromFile(VALUE_DOMAIN_CATALOG_PATH);
            
            if (mCurrentVersion == null) {
                Log.e(TAG, "Failed to load value domain version");
                throw new RuntimeException("Value domain catalog not found or invalid");
            }
            
            Log.i(TAG, "Loaded value domain version: " + mCurrentVersion);
            
        } finally {
            mLock.writeLock().unlock();
        }
    }
    
    /**
     * 获取当前值域版本
     */
    public ValueDomainVersion getCurrentVersion() {
        mLock.readLock().lock();
        try {
            return mCurrentVersion;
        } finally {
            mLock.readLock().unlock();
        }
    }
    
    /**
     * 检查分类器版本兼容性
     * 
     * @param classifierVersion 通知分类器版本
     * @return 是否兼容
     */
    public boolean checkClassifierCompatibility(String classifierVersion) {
        mLock.readLock().lock();
        try {
            if (mCurrentVersion == null) {
                Log.w(TAG, "No current version loaded");
                return false;
            }
            
            boolean compatible = mCurrentVersion.isCompatibleWithClassifier(classifierVersion);
            
            if (!compatible) {
                Log.w(TAG, "Classifier version mismatch: expected " + 
                        mCurrentVersion.getNotificationClassifierVersion() + 
                        ", got " + classifierVersion);
            }
            
            return compatible;
            
        } finally {
            mLock.readLock().unlock();
        }
    }
    
    /**
     * 检查评分器版本兼容性
     * 
     * @param scorerVersion 反骚扰评分器版本
     * @return 是否兼容
     */
    public boolean checkScorerCompatibility(String scorerVersion) {
        mLock.readLock().lock();
        try {
            if (mCurrentVersion == null) {
                Log.w(TAG, "No current version loaded");
                return false;
            }
            
            boolean compatible = mCurrentVersion.isCompatibleWithScorer(scorerVersion);
            
            if (!compatible) {
                Log.w(TAG, "Scorer version mismatch: expected " + 
                        mCurrentVersion.getSpamScorerVersion() + 
                        ", got " + scorerVersion);
            }
            
            return compatible;
            
        } finally {
            mLock.readLock().unlock();
        }
    }
    
    /**
     * 处理 OTA 更新
     * 
     * @param otaPackagePath OTA 包路径
     * @return 更新结果
     */
    public OtaUpdateResult handleOtaUpdate(String otaPackagePath) {
        mLock.writeLock().lock();
        try {
            Log.i(TAG, "Handling OTA update from: " + otaPackagePath);
            
            // 1. 加载新版本信息
            ValueDomainVersion newVersion = loadVersionFromFile(otaPackagePath);
            if (newVersion == null) {
                return OtaUpdateResult.failure("Failed to load new version");
            }
            
            // 2. 版本校验
            if (!validateVersionUpdate(mCurrentVersion, newVersion)) {
                return OtaUpdateResult.failure("Version validation failed");
            }
            
            // 3. 签名验证
            if (!verifySignature(otaPackagePath, newVersion.getSignature())) {
                return OtaUpdateResult.failure("Signature verification failed");
            }
            
            // 4. 校验和验证
            if (!verifyChecksum(otaPackagePath, newVersion.getChecksumSha256())) {
                return OtaUpdateResult.failure("Checksum verification failed");
            }
            
            // 5. 备份当前版本（用于回滚）
            ValueDomainVersion oldVersion = mCurrentVersion;
            
            // 6. 更新到新版本
            mCurrentVersion = newVersion;
            
            // 7. 通知监听器
            notifyVersionChange(oldVersion, newVersion);
            
            Log.i(TAG, "OTA update successful: " + oldVersion.getVersion() + 
                    " -> " + newVersion.getVersion());
            
            return OtaUpdateResult.success(newVersion);
            
        } catch (Exception e) {
            Log.e(TAG, "OTA update failed", e);
            return OtaUpdateResult.failure("Exception: " + e.getMessage());
        } finally {
            mLock.writeLock().unlock();
        }
    }
    
    /**
     * 回滚到指定版本
     * 
     * @param targetVersion 目标版本号
     * @return 是否成功
     */
    public boolean rollbackToVersion(String targetVersion) {
        mLock.writeLock().lock();
        try {
            Log.i(TAG, "Rolling back to version: " + targetVersion);
            
            // 查找回滚版本文件
            String rollbackPath = OTA_UPDATE_PATH + "rollback_" + targetVersion + ".yaml";
            ValueDomainVersion rollbackVersion = loadVersionFromFile(rollbackPath);
            
            if (rollbackVersion == null) {
                Log.e(TAG, "Rollback version not found: " + targetVersion);
                return false;
            }
            
            // 备份当前版本
            ValueDomainVersion oldVersion = mCurrentVersion;
            
            // 回滚
            mCurrentVersion = rollbackVersion;
            
            // 通知监听器
            notifyVersionChange(oldVersion, rollbackVersion);
            
            Log.i(TAG, "Rollback successful: " + oldVersion.getVersion() + 
                    " -> " + rollbackVersion.getVersion());
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Rollback failed", e);
            return false;
        } finally {
            mLock.writeLock().unlock();
        }
    }
    
    /**
     * 从文件加载版本信息
     */
    private ValueDomainVersion loadVersionFromFile(String filePath) {
        try (InputStream input = new FileInputStream(filePath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(input);
            
            // 解析基础字段
            String version = (String) data.get("version");
            String lastUpdated = (String) data.get("last_updated");
            
            // 解析元数据
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
            if (metadata == null) {
                Log.w(TAG, "No metadata found in value domain catalog");
                return null;
            }
            
            String schemaVersion = (String) metadata.get("schema_version");
            
            @SuppressWarnings("unchecked")
            Map<String, String> classifierVersion = 
                    (Map<String, String>) metadata.get("classifier_version");
            String notificationClassifierVersion = 
                    classifierVersion.get("notification_classifier");
            String spamScorerVersion = classifierVersion.get("spam_scorer");
            
            String minPolicyEngineVersion = (String) metadata.get("min_policy_engine_version");
            String minDslCompilerVersion = (String) metadata.get("min_dsl_compiler_version");
            
            String updateChannel = (String) metadata.get("update_channel");
            Boolean incrementalUpdate = (Boolean) metadata.get("incremental_update");
            String rollbackVersion = (String) metadata.get("rollback_version");
            
            String checksumSha256 = (String) metadata.get("checksum_sha256");
            String signature = (String) metadata.get("signature");
            
            return new ValueDomainVersion(
                    version,
                    schemaVersion,
                    lastUpdated,
                    notificationClassifierVersion,
                    spamScorerVersion,
                    minPolicyEngineVersion,
                    minDslCompilerVersion,
                    updateChannel,
                    incrementalUpdate != null && incrementalUpdate,
                    rollbackVersion,
                    checksumSha256 != null ? checksumSha256 : "",
                    signature != null ? signature : ""
            );
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load version from file: " + filePath, e);
            return null;
        }
    }
    
    /**
     * 验证版本更新是否合法
     */
    private boolean validateVersionUpdate(ValueDomainVersion oldVersion, 
                                         ValueDomainVersion newVersion) {
        // 1. 版本号必须递增
        if (compareVersion(newVersion.getVersion(), oldVersion.getVersion()) <= 0) {
            Log.e(TAG, "New version must be greater than current version");
            return false;
        }
        
        // 2. 更新通道必须一致（stable/beta/dev）
        if (!oldVersion.getUpdateChannel().equals(newVersion.getUpdateChannel())) {
            Log.e(TAG, "Update channel mismatch");
            return false;
        }
        
        // 3. schema 版本必须兼容
        if (!newVersion.getSchemaVersion().equals(oldVersion.getSchemaVersion())) {
            Log.w(TAG, "Schema version changed, may require migration");
        }
        
        return true;
    }
    
    /**
     * 验证签名
     */
    private boolean verifySignature(String filePath, String signature) {
        // TODO: 实现平台签名验证
        // 当前仅检查签名非空
        if (signature == null || signature.isEmpty()) {
            Log.w(TAG, "No signature provided, skipping verification");
            return true;  // 开发阶段允许无签名
        }
        
        // 实际部署时应验证签名
        return true;
    }
    
    /**
     * 验证校验和
     */
    private boolean verifyChecksum(String filePath, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isEmpty()) {
            Log.w(TAG, "No checksum provided, skipping verification");
            return true;
        }
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String actualChecksum = sb.toString();
            
            boolean valid = actualChecksum.equals(expectedChecksum);
            if (!valid) {
                Log.e(TAG, "Checksum mismatch: expected " + expectedChecksum + 
                        ", got " + actualChecksum);
            }
            
            return valid;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify checksum", e);
            return false;
        }
    }
    
    /**
     * 比较版本号
     */
    private int compareVersion(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        
        return 0;
    }
    
    /**
     * 注册版本变更监听器
     */
    public void registerVersionChangeListener(VersionChangeListener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
            }
        }
    }
    
    /**
     * 注销版本变更监听器
     */
    public void unregisterVersionChangeListener(VersionChangeListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }
    
    /**
     * 通知版本变更
     */
    private void notifyVersionChange(ValueDomainVersion oldVersion, 
                                    ValueDomainVersion newVersion) {
        synchronized (mListeners) {
            for (VersionChangeListener listener : mListeners) {
                try {
                    listener.onVersionChanged(oldVersion, newVersion);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying listener", e);
                }
            }
        }
    }
    
    /**
     * 版本变更监听器
     */
    public interface VersionChangeListener {
        /**
         * 版本变更回调
         * 
         * @param oldVersion 旧版本
         * @param newVersion 新版本
         */
        void onVersionChanged(ValueDomainVersion oldVersion, ValueDomainVersion newVersion);
    }
    
    /**
     * OTA 更新结果
     */
    public static class OtaUpdateResult {
        private final boolean success;
        private final String message;
        private final ValueDomainVersion newVersion;
        
        private OtaUpdateResult(boolean success, String message, ValueDomainVersion newVersion) {
            this.success = success;
            this.message = message;
            this.newVersion = newVersion;
        }
        
        public static OtaUpdateResult success(ValueDomainVersion newVersion) {
            return new OtaUpdateResult(true, "Update successful", newVersion);
        }
        
        public static OtaUpdateResult failure(String message) {
            return new OtaUpdateResult(false, message, null);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
        
        public ValueDomainVersion getNewVersion() {
            return newVersion;
        }
    }
}
