package com.ainative.policy;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * C6.4 值域版本元数据
 * 
 * 职责：
 * 1. 记录值域知识库的版本信息
 * 2. 绑定分类器模型版本（NotificationClassifier/SpamScorer）
 * 3. 支持 OTA 更新和兼容性检查
 * 
 * 版本联动策略：
 * - category 值集与 NotificationClassifier 版本强绑定
 * - 模型版本升级时，值域知识库必须同步更新
 * - 策略引擎加载值域时验证版本兼容性
 * 
 * 符合 WBS：
 * - C6.4 值域/枚举与分类器版本联动 OTA
 * - C1.2 值域知识库（随 PolicyPack 版本化）
 * - C4.2 通知分类器和反骚扰模型
 */
public class ValueDomainVersion implements Parcelable {
    
    // 值域知识库版本（语义化版本）
    private final String version;
    
    // 值域知识库 schema 版本
    private final String schemaVersion;
    
    // 最后更新时间（ISO 8601 格式）
    private final String lastUpdated;
    
    // 分类器版本绑定
    private final String notificationClassifierVersion;
    private final String spamScorerVersion;
    
    // 兼容性要求
    private final String minPolicyEngineVersion;
    private final String minDslCompilerVersion;
    
    // OTA 更新元数据
    private final String updateChannel;
    private final boolean incrementalUpdate;
    private final String rollbackVersion;  // null 表示无回滚
    
    // 签名和完整性
    private final String checksumSha256;
    private final String signature;
    
    public ValueDomainVersion(
            String version,
            String schemaVersion,
            String lastUpdated,
            String notificationClassifierVersion,
            String spamScorerVersion,
            String minPolicyEngineVersion,
            String minDslCompilerVersion,
            String updateChannel,
            boolean incrementalUpdate,
            String rollbackVersion,
            String checksumSha256,
            String signature) {
        this.version = version;
        this.schemaVersion = schemaVersion;
        this.lastUpdated = lastUpdated;
        this.notificationClassifierVersion = notificationClassifierVersion;
        this.spamScorerVersion = spamScorerVersion;
        this.minPolicyEngineVersion = minPolicyEngineVersion;
        this.minDslCompilerVersion = minDslCompilerVersion;
        this.updateChannel = updateChannel;
        this.incrementalUpdate = incrementalUpdate;
        this.rollbackVersion = rollbackVersion;
        this.checksumSha256 = checksumSha256;
        this.signature = signature;
    }
    
    // Getters
    
    public String getVersion() {
        return version;
    }
    
    public String getSchemaVersion() {
        return schemaVersion;
    }
    
    public String getLastUpdated() {
        return lastUpdated;
    }
    
    public String getNotificationClassifierVersion() {
        return notificationClassifierVersion;
    }
    
    public String getSpamScorerVersion() {
        return spamScorerVersion;
    }
    
    public String getMinPolicyEngineVersion() {
        return minPolicyEngineVersion;
    }
    
    public String getMinDslCompilerVersion() {
        return minDslCompilerVersion;
    }
    
    public String getUpdateChannel() {
        return updateChannel;
    }
    
    public boolean isIncrementalUpdate() {
        return incrementalUpdate;
    }
    
    public String getRollbackVersion() {
        return rollbackVersion;
    }
    
    public String getChecksumSha256() {
        return checksumSha256;
    }
    
    public String getSignature() {
        return signature;
    }
    
    /**
     * 检查是否与指定的分类器版本兼容
     * 
     * @param classifierVersion 分类器版本
     * @return 是否兼容
     */
    public boolean isCompatibleWithClassifier(String classifierVersion) {
        return notificationClassifierVersion.equals(classifierVersion);
    }
    
    /**
     * 检查是否与指定的评分器版本兼容
     * 
     * @param scorerVersion 评分器版本
     * @return 是否兼容
     */
    public boolean isCompatibleWithScorer(String scorerVersion) {
        return spamScorerVersion.equals(scorerVersion);
    }
    
    /**
     * 检查策略引擎版本是否满足最低要求
     * 
     * @param engineVersion 策略引擎版本
     * @return 是否满足要求
     */
    public boolean checkEngineVersion(String engineVersion) {
        return compareVersion(engineVersion, minPolicyEngineVersion) >= 0;
    }
    
    /**
     * 检查 DSL 编译器版本是否满足最低要求
     * 
     * @param compilerVersion DSL 编译器版本
     * @return 是否满足要求
     */
    public boolean checkCompilerVersion(String compilerVersion) {
        return compareVersion(compilerVersion, minDslCompilerVersion) >= 0;
    }
    
    /**
     * 比较版本号（语义化版本）
     * 
     * @param v1 版本 1
     * @param v2 版本 2
     * @return >0 表示 v1 > v2，=0 表示相等，<0 表示 v1 < v2
     */
    private int compareVersion(String v1, String v2) {
        if (v1 == null || v2 == null) {
            return 0;
        }
        
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        
        return 0;
    }
    
    /**
     * 解析版本号部分（处理数字和非数字字符）
     */
    private int parseVersionPart(String part) {
        try {
            // 提取数字部分（如 "1-beta" → 1）
            String numPart = part.replaceAll("[^0-9].*$", "");
            return numPart.isEmpty() ? 0 : Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    @Override
    public String toString() {
        return "ValueDomainVersion{" +
                "version='" + version + '\'' +
                ", schemaVersion='" + schemaVersion + '\'' +
                ", lastUpdated='" + lastUpdated + '\'' +
                ", notificationClassifierVersion='" + notificationClassifierVersion + '\'' +
                ", spamScorerVersion='" + spamScorerVersion + '\'' +
                ", updateChannel='" + updateChannel + '\'' +
                '}';
    }
    
    // Parcelable 实现
    
    protected ValueDomainVersion(Parcel in) {
        version = in.readString();
        schemaVersion = in.readString();
        lastUpdated = in.readString();
        notificationClassifierVersion = in.readString();
        spamScorerVersion = in.readString();
        minPolicyEngineVersion = in.readString();
        minDslCompilerVersion = in.readString();
        updateChannel = in.readString();
        incrementalUpdate = in.readByte() != 0;
        rollbackVersion = in.readString();
        checksumSha256 = in.readString();
        signature = in.readString();
    }
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(version);
        dest.writeString(schemaVersion);
        dest.writeString(lastUpdated);
        dest.writeString(notificationClassifierVersion);
        dest.writeString(spamScorerVersion);
        dest.writeString(minPolicyEngineVersion);
        dest.writeString(minDslCompilerVersion);
        dest.writeString(updateChannel);
        dest.writeByte((byte) (incrementalUpdate ? 1 : 0));
        dest.writeString(rollbackVersion);
        dest.writeString(checksumSha256);
        dest.writeString(signature);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    public static final Creator<ValueDomainVersion> CREATOR = new Creator<ValueDomainVersion>() {
        @Override
        public ValueDomainVersion createFromParcel(Parcel in) {
            return new ValueDomainVersion(in);
        }
        
        @Override
        public ValueDomainVersion[] newArray(int size) {
            return new ValueDomainVersion[size];
        }
    };
}
