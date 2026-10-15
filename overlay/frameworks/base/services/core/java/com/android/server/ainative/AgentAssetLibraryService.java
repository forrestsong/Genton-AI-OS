package com.android.server.ainative;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;

import com.ainative.assets.AssetDescriptor;
import com.ainative.assets.AssetManifest;
import com.ainative.assets.AssetQuery;
import com.ainative.assets.AssetUpdateInfo;
import com.ainative.assets.CompatibilityReport;
import com.ainative.assets.ExecutionReport;
import com.ainative.assets.IAgentAssetLibraryService;
import com.ainative.assets.IAgentAssetListener;
import com.ainative.assets.RegressionJob;
import com.ainative.assets.ResolveRequest;
import com.ainative.assets.ResolvedAsset;
import com.ainative.assets.ToolSchema;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资产目录服务实现
 * 
 * 管理 Skill/DMI/Macro 三类操作资产的全生命周期：
 * - 注册、验签、版本绑定、机型兼容性管理
 * - 解析降级链（DMI → Macro → L2兜底）
 * - staged-enable 与回归测试
 * - 失败自动停用与执行回灌统计
 * - 工具Schema注入（供Agent调用）
 * 
 * 核心架构：
 * 1. 资产目录：ConcurrentHashMap存储资产元数据
 * 2. 签名验证：SHA256校验 + 平台签名验证
 * 3. 版本匹配：语义版本解析（>=4.2, <5）
 * 4. 降级链：DMI失效 → Macro → L2兜底
 * 5. 失败统计：连续失败 → 自动停用 + 审计
 * 6. 回归测试：异步任务 + 进度追踪
 * 7. 变更通知：RemoteCallbackList广播资产变更
 * 
 * 红线：
 * - 未签名资产拒绝注册
 * - Macro只能声明agent.input_inject等UI类能力
 * - 本服务不执行副作用，只提供目录与解析建议
 */
public class AgentAssetLibraryService extends IAgentAssetLibraryService.Stub {
    private static final String TAG = "AgentAssetLibraryService";
    
    // Macro允许的能力白名单（红线：只能声明UI注入类能力）
    private static final Set<String> MACRO_ALLOWED_CAPABILITIES = new HashSet<>(Arrays.asList(
        "agent.input_inject",
        "agent.screen_read",
        "agent.ui_navigate",
        "agent.gesture"
    ));
    
    // 失败自动停用阈值
    private static final int FAILURE_THRESHOLD = 3;         // 连续失败3次
    private static final long FAILURE_WINDOW_MS = 3600000;  // 1小时窗口
    
    // 资产存储
    private final Map<String, AssetRecord> mAssetStore = new ConcurrentHashMap<>();
    
    // 执行统计
    private final Map<String, ExecutionStats> mExecutionStats = new ConcurrentHashMap<>();
    
    // 回归任务
    private final Map<String, RegressionJob> mRegressionJobs = new ConcurrentHashMap<>();
    
    // 变更监听器
    private final RemoteCallbackList<IAgentAssetListener> mListeners = new RemoteCallbackList<>();
    
    private final Context mContext;
    private final Handler mHandler;
    private final PackageManager mPackageManager;
    private final String mDeviceModel;
    private final String mOsVersion;
    
    public AgentAssetLibraryService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mDeviceModel = Build.MODEL;
        mOsVersion = "A-0.5"; // TODO: 从系统属性读取
        
        HandlerThread handlerThread = new HandlerThread("AssetLibraryWorker");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        
        Slog.i(TAG, "AgentAssetLibraryService initialized (device=" + mDeviceModel + ", os=" + mOsVersion + ")");
    }
    
    @Override
    public AssetDescriptor[] queryAssets(AssetQuery query) {
        if (query == null) {
            Slog.w(TAG, "queryAssets: null query");
            return new AssetDescriptor[0];
        }
        
        List<AssetDescriptor> results = new ArrayList<>();
        
        for (AssetRecord record : mAssetStore.values()) {
            // 类型过滤
            if (query.kind != 0 && record.manifest.kind != query.kind) {
                continue;
            }
            
            // 包名过滤
            if (query.targetApp != null && !query.targetApp.isEmpty()) {
                if (!query.targetApp.equals(record.manifest.targetAppPackage)) {
                    continue;
                }
            }
            
            // 机型过滤
            if (query.deviceModel != null && !query.deviceModel.isEmpty()) {
                if (!isDeviceModelMatch(record.manifest.deviceModels, query.deviceModel)) {
                    continue;
                }
            }
            
            // 标签过滤
            if (query.labels != null && query.labels.length > 0) {
                if (!hasAnyLabel(record.manifest.labels, query.labels)) {
                    continue;
                }
            }
            
            // 状态过滤
            if (query.onlyEnabled && record.status != IAgentAssetLibraryService.ASSET_STATUS_ENABLED) {
                continue;
            }
            
            // 语义查询（简单包含匹配）
            if (query.semanticQuery != null && !query.semanticQuery.isEmpty()) {
                String lower = query.semanticQuery.toLowerCase();
                if (!record.manifest.description.toLowerCase().contains(lower) &&
                    !record.manifest.name.toLowerCase().contains(lower)) {
                    continue;
                }
            }
            
            results.add(toDescriptor(record));
            
            if (results.size() >= query.topK) {
                break;
            }
        }
        
        Slog.d(TAG, "queryAssets: found " + results.size() + " matches");
        return results.toArray(new AssetDescriptor[0]);
    }
    
    @Override
    public AssetDescriptor getAsset(String assetId) {
        if (assetId == null || assetId.isEmpty()) {
            return null;
        }
        
        AssetRecord record = mAssetStore.get(assetId);
        if (record == null) {
            return null;
        }
        
        return toDescriptor(record);
    }
    
    @Override
    public int resolveForUse(ResolveRequest request, ResolvedAsset resolved) {
        if (request == null) {
            Slog.w(TAG, "resolveForUse: null request");
            return IAgentAssetLibraryService.STATUS_DENIED;
        }
        
        Slog.d(TAG, "resolveForUse: actionDescription=" + request.actionDescription + 
               ", targetApp=" + request.targetApp + ", deviceModel=" + request.deviceModel);
        
        // 按优先顺序查找匹配的资产
        List<String> candidateIds = new ArrayList<>();
        
        if (request.candidateKinds != null && request.candidateKinds.length > 0) {
            for (int kind : request.candidateKinds) {
                candidateIds.addAll(findCandidates(request, kind));
            }
        } else {
            // 默认优先级：DMI > Macro > Skill
            candidateIds.addAll(findCandidates(request, IAgentAssetLibraryService.KIND_DMI));
            candidateIds.addAll(findCandidates(request, IAgentAssetLibraryService.KIND_MACRO));
            candidateIds.addAll(findCandidates(request, IAgentAssetLibraryService.KIND_SKILL));
        }
        
        if (candidateIds.isEmpty()) {
            Slog.w(TAG, "resolveForUse: no matching asset found");
            return IAgentAssetLibraryService.STATUS_NOT_FOUND;
        }
        
        // 选择首选资产
        String primaryId = candidateIds.get(0);
        AssetRecord primary = mAssetStore.get(primaryId);
        
        // 检查兼容性
        CompatibilityReport compat = checkCompatibility(primary, request);
        if (!compat.deviceModelMatch || !compat.appVersionMatch || !compat.osVersionMatch) {
            Slog.w(TAG, "resolveForUse: incompatible asset " + primaryId + ": " + compat.incompatReason);
            return IAgentAssetLibraryService.STATUS_INCOMPATIBLE;
        }
        
        if (!compat.signatureValid) {
            Slog.e(TAG, "resolveForUse: unsigned asset " + primaryId);
            return IAgentAssetLibraryService.STATUS_UNSIGNED;
        }
        
        if (primary.status == IAgentAssetLibraryService.ASSET_STATUS_DISABLED) {
            Slog.w(TAG, "resolveForUse: asset disabled " + primaryId);
            return IAgentAssetLibraryService.STATUS_DISABLED;
        }
        
        // 构建降级链（移除首选后的其他候选）
        List<String> fallbacks = new ArrayList<>(candidateIds.subList(1, candidateIds.size()));
        if (primary.manifest.fallbacks != null) {
            fallbacks.addAll(Arrays.asList(primary.manifest.fallbacks));
        }
        
        // 填充解析结果
        resolved.assetId = primary.assetId;
        resolved.version = primary.manifest.version;
        resolved.kind = primary.manifest.kind;
        resolved.capability = primary.manifest.capability;
        resolved.artifactRef = "asset://" + primary.assetId + "/" + primary.manifest.version;
        resolved.fallbackChain = fallbacks.toArray(new String[0]);
        resolved.descriptor = toDescriptor(primary);
        
        Slog.i(TAG, "resolveForUse: resolved to " + primaryId + " with " + fallbacks.size() + " fallbacks");
        return IAgentAssetLibraryService.STATUS_OK;
    }
    
    @Override
    public int registerAsset(AssetManifest manifest, byte[] signedPayload, String[] assetId) {
        if (manifest == null || signedPayload == null) {
            Slog.w(TAG, "registerAsset: null manifest or payload");
            return IAgentAssetLibraryService.STATUS_DENIED;
        }
        
        // 验证签名
        if (!verifySignature(signedPayload, manifest.checksum, manifest.signatureRef)) {
            Slog.e(TAG, "registerAsset: signature verification failed for " + manifest.id);
            return IAgentAssetLibraryService.STATUS_UNSIGNED;
        }
        
        // Macro红线检查：只能声明UI注入类能力
        if (manifest.kind == IAgentAssetLibraryService.KIND_MACRO) {
            if (!MACRO_ALLOWED_CAPABILITIES.contains(manifest.capability)) {
                Slog.e(TAG, "registerAsset: macro capability violation: " + manifest.capability);
                return IAgentAssetLibraryService.STATUS_DENIED;
            }
        }
        
        // 检查能力是否在动作注册表中（模拟检查）
        // TODO: 实际应查询ActionRegistry
        if (manifest.capability == null || manifest.capability.isEmpty()) {
            Slog.e(TAG, "registerAsset: missing capability");
            return IAgentAssetLibraryService.STATUS_DENIED;
        }
        
        // 创建资产记录
        AssetRecord record = new AssetRecord();
        record.assetId = manifest.id;
        record.manifest = manifest;
        record.signedPayload = signedPayload;
        record.registeredTimeMs = System.currentTimeMillis();
        
        // 第三方资产初始状态为staged（需回归测试）
        if (isPlatformSigned(manifest.signatureRef)) {
            record.status = IAgentAssetLibraryService.ASSET_STATUS_ENABLED;
        } else {
            record.status = IAgentAssetLibraryService.ASSET_STATUS_STAGED;
        }
        
        // 存储
        AssetRecord existing = mAssetStore.put(record.assetId, record);
        
        // A8.2: 注册到动作注册表（资产与动作注册表统一）
        if (record.status == IAgentAssetLibraryService.ASSET_STATUS_ENABLED) {
            registerAssetToActionRegistry(record);
        }
        
        // 通知变更
        int changeType = (existing == null) ? 0 : 1; // 0=added 1=updated
        notifyAssetChanged(record.assetId, changeType, manifest.version, record.status, 
                          "registered");
        
        assetId[0] = record.assetId;
        Slog.i(TAG, "registerAsset: " + record.assetId + " (status=" + record.status + ")");
        return IAgentAssetLibraryService.STATUS_OK;
    }
    
    @Override
    public int setAssetStatus(String assetId, int status) {
        AssetRecord record = mAssetStore.get(assetId);
        if (record == null) {
            Slog.w(TAG, "setAssetStatus: asset not found " + assetId);
            return IAgentAssetLibraryService.STATUS_NOT_FOUND;
        }
        
        int oldStatus = record.status;
        record.status = status;
        
        // A8.2: 同步到动作注册表
        if (status == IAgentAssetLibraryService.ASSET_STATUS_ENABLED && 
            oldStatus != IAgentAssetLibraryService.ASSET_STATUS_ENABLED) {
            // 启用：注册到动作注册表
            registerAssetToActionRegistry(record);
        } else if (status == IAgentAssetLibraryService.ASSET_STATUS_DISABLED &&
                   oldStatus == IAgentAssetLibraryService.ASSET_STATUS_ENABLED) {
            // 禁用：从动作注册表移除
            unregisterAssetFromActionRegistry(assetId);
        }
        
        String reason = (status == IAgentAssetLibraryService.ASSET_STATUS_DISABLED) 
                       ? "manual disable" : "manual enable";
        notifyAssetChanged(assetId, (status == IAgentAssetLibraryService.ASSET_STATUS_ENABLED) ? 3 : 2, 
                          record.manifest.version, status, reason);
        
        Slog.i(TAG, "setAssetStatus: " + assetId + " " + oldStatus + " → " + status);
        return IAgentAssetLibraryService.STATUS_OK;
    }
    
    @Override
    public int runRegression(String assetId, RegressionJob job) {
        // 创建回归任务
        String jobId = UUID.randomUUID().toString();
        job.jobId = jobId;
        job.scope = (assetId == null || assetId.isEmpty()) ? 1 : 0;
        job.progress = 0;
        job.passCount = 0;
        job.failCount = 0;
        job.failedAssetIds = new String[0];
        job.startTimeMs = System.currentTimeMillis();
        job.estimatedEndTimeMs = job.startTimeMs + 300000; // 5分钟估算
        
        mRegressionJobs.put(jobId, job);
        
        // 异步执行回归测试
        mHandler.post(() -> executeRegression(jobId, assetId));
        
        Slog.i(TAG, "runRegression: started job " + jobId + " (scope=" + job.scope + ")");
        return IAgentAssetLibraryService.STATUS_OK;
    }
    
    @Override
    public int reportExecution(ExecutionReport report) {
        if (report == null || report.assetId == null) {
            return IAgentAssetLibraryService.STATUS_DENIED;
        }
        
        AssetRecord record = mAssetStore.get(report.assetId);
        if (record == null) {
            return IAgentAssetLibraryService.STATUS_NOT_FOUND;
        }
        
        // 更新执行统计
        ExecutionStats stats = mExecutionStats.computeIfAbsent(
            report.assetId, k -> new ExecutionStats());
        
        stats.totalCount++;
        stats.lastExecutionMs = report.timestampMs;
        
        if (report.outcome == 0) { // success
            stats.successCount++;
            stats.consecutiveFailures = 0;
        } else {
            stats.failureCount++;
            stats.consecutiveFailures++;
            stats.lastFailureReason = report.failReason;
            stats.lastFailureTimeMs = report.timestampMs;
        }
        
        // 失败自动停用检查
        if (stats.consecutiveFailures >= FAILURE_THRESHOLD) {
            long timeSinceFirstFailure = report.timestampMs - 
                (stats.lastFailureTimeMs - (stats.consecutiveFailures - 1) * 60000);
            
            if (timeSinceFirstFailure <= FAILURE_WINDOW_MS) {
                Slog.w(TAG, "reportExecution: auto-disabling " + report.assetId + 
                       " (consecutive failures: " + stats.consecutiveFailures + ")");
                
                record.status = IAgentAssetLibraryService.ASSET_STATUS_DISABLED;
                notifyAssetChanged(report.assetId, 2, record.manifest.version, record.status,
                                  "auto-disabled: " + stats.consecutiveFailures + " consecutive failures");
                
                // TODO: 审计埋点
            }
        }
        
        Slog.d(TAG, "reportExecution: " + report.assetId + " outcome=" + report.outcome + 
               " (" + stats.successCount + "/" + stats.totalCount + ")");
        return IAgentAssetLibraryService.STATUS_OK;
    }
    
    @Override
    public ToolSchema[] getToolDescriptors(String agentId) {
        List<ToolSchema> tools = new ArrayList<>();
        
        for (AssetRecord record : mAssetStore.values()) {
            // 只返回enabled状态的资产
            if (record.status != IAgentAssetLibraryService.ASSET_STATUS_ENABLED) {
                continue;
            }
            
            // TODO: 按agentId过滤能力（查询策略引擎）
            
            ToolSchema tool = new ToolSchema();
            tool.assetId = record.assetId;
            tool.name = record.manifest.name;
            tool.description = record.manifest.description;
            tool.inputSchemaJson = record.manifest.inputSchemaJson;
            tool.outputSchemaJson = record.manifest.outputSchemaJson;
            tool.capability = record.manifest.capability;
            tool.riskLevel = record.manifest.riskLevel;
            tool.kind = record.manifest.kind;
            
            tools.add(tool);
        }
        
        Slog.d(TAG, "getToolDescriptors: returning " + tools.size() + " tools for " + agentId);
        return tools.toArray(new ToolSchema[0]);
    }
    
    @Override
    public int registerListener(IAgentAssetListener listener) {
        if (listener == null) {
            return IAgentAssetLibraryService.STATUS_DENIED;
        }
        
        mListeners.register(listener);
        Slog.d(TAG, "registerListener: " + listener.asBinder());
        return IAgentAssetLibraryService.STATUS_OK;
    }
    
    @Override
    public int unregisterListener(IAgentAssetListener listener) {
        if (listener == null) {
            return IAgentAssetLibraryService.STATUS_DENIED;
        }
        
        mListeners.unregister(listener);
        Slog.d(TAG, "unregisterListener: " + listener.asBinder());
        return IAgentAssetLibraryService.STATUS_OK;
    }
    
    @Override
    public CompatibilityReport getCompatibilityReport(String assetId) {
        AssetRecord record = mAssetStore.get(assetId);
        if (record == null) {
            CompatibilityReport report = new CompatibilityReport();
            report.assetId = assetId;
            report.incompatReason = "Asset not found";
            return report;
        }
        
        ResolveRequest dummyRequest = new ResolveRequest();
        dummyRequest.deviceModel = mDeviceModel;
        dummyRequest.targetApp = record.manifest.targetAppPackage;
        dummyRequest.targetAppVersion = getCurrentAppVersion(record.manifest.targetAppPackage);
        
        return checkCompatibility(record, dummyRequest);
    }
    
    // ==================== 内部方法 ====================
    
    private List<String> findCandidates(ResolveRequest request, int kind) {
        List<String> candidates = new ArrayList<>();
        
        for (AssetRecord record : mAssetStore.values()) {
            if (record.manifest.kind != kind) {
                continue;
            }
            
            if (record.status != IAgentAssetLibraryService.ASSET_STATUS_ENABLED) {
                continue;
            }
            
            // 包名匹配（Skill可以为空）
            if (request.targetApp != null && !request.targetApp.isEmpty()) {
                if (!request.targetApp.equals(record.manifest.targetAppPackage)) {
                    continue;
                }
            }
            
            // 机型匹配
            if (!isDeviceModelMatch(record.manifest.deviceModels, request.deviceModel)) {
                continue;
            }
            
            // 语义匹配（简单包含）
            if (request.actionDescription != null && !request.actionDescription.isEmpty()) {
                String lower = request.actionDescription.toLowerCase();
                if (!record.manifest.description.toLowerCase().contains(lower) &&
                    !record.manifest.name.toLowerCase().contains(lower)) {
                    continue;
                }
            }
            
            candidates.add(record.assetId);
        }
        
        return candidates;
    }
    
    private CompatibilityReport checkCompatibility(AssetRecord record, ResolveRequest request) {
        CompatibilityReport report = new CompatibilityReport();
        report.assetId = record.assetId;
        report.currentDeviceModel = mDeviceModel;
        report.currentOsVersion = mOsVersion;
        report.currentAppVersion = getCurrentAppVersion(record.manifest.targetAppPackage);
        
        // 机型匹配
        report.deviceModelMatch = isDeviceModelMatch(
            record.manifest.deviceModels, request.deviceModel);
        
        // App版本匹配
        if (record.manifest.targetAppPackage != null && 
            !record.manifest.targetAppPackage.isEmpty()) {
            report.appVersionMatch = isVersionMatch(
                request.targetAppVersion, record.manifest.appVersionRange);
        } else {
            report.appVersionMatch = true; // 系统级Skill无App版本要求
        }
        
        // OS版本匹配
        report.osVersionMatch = isVersionMatch(mOsVersion, record.manifest.osRange);
        
        // 签名有效性
        report.signatureValid = verifySignature(
            record.signedPayload, record.manifest.checksum, record.manifest.signatureRef);
        
        // 不兼容原因
        if (!report.deviceModelMatch) {
            report.incompatReason = "Device model mismatch";
        } else if (!report.appVersionMatch) {
            report.incompatReason = "App version mismatch";
        } else if (!report.osVersionMatch) {
            report.incompatReason = "OS version mismatch";
        } else if (!report.signatureValid) {
            report.incompatReason = "Signature invalid";
        }
        
        return report;
    }
    
    private boolean isDeviceModelMatch(String[] deviceModels, String targetModel) {
        if (deviceModels == null || deviceModels.length == 0) {
            return true; // 无限制
        }
        
        for (String model : deviceModels) {
            if (model.equals(targetModel) || model.equals("*")) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean isVersionMatch(String version, String range) {
        if (range == null || range.isEmpty()) {
            return true;
        }
        
        // 简单的版本范围解析（支持 >=4.2, <5）
        Pattern pattern = Pattern.compile("([><=]+)\\s*([\\d.]+)");
        Matcher matcher = pattern.matcher(range);
        
        while (matcher.find()) {
            String op = matcher.group(1);
            String targetVersion = matcher.group(2);
            
            int cmp = compareVersions(version, targetVersion);
            
            if (op.equals(">=") && cmp < 0) return false;
            if (op.equals(">") && cmp <= 0) return false;
            if (op.equals("<=") && cmp > 0) return false;
            if (op.equals("<") && cmp >= 0) return false;
            if (op.equals("=") && cmp != 0) return false;
        }
        
        return true;
    }
    
    private int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) {
            return 0;
        }
        
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        
        for (int i = 0; i < length; i++) {
            int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            
            if (p1 < p2) return -1;
            if (p1 > p2) return 1;
        }
        
        return 0;
    }
    
    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private boolean verifySignature(byte[] payload, String expectedChecksum, String signatureRef) {
        if (payload == null || expectedChecksum == null) {
            return false;
        }
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            String checksum = "sha256-" + bytesToHex(hash);
            
            boolean match = checksum.equals(expectedChecksum);
            if (!match) {
                Slog.w(TAG, "verifySignature: checksum mismatch");
                return false;
            }
            
            // TODO: 实际验证平台/开发者签名
            // 现在简单认为有signatureRef就是有效的
            return signatureRef != null && !signatureRef.isEmpty();
            
        } catch (Exception e) {
            Slog.e(TAG, "verifySignature: exception", e);
            return false;
        }
    }
    
    private boolean isPlatformSigned(String signatureRef) {
        return signatureRef != null && signatureRef.startsWith("platform:");
    }
    
    private String getCurrentAppVersion(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        
        try {
            PackageInfo info = mPackageManager.getPackageInfo(packageName, 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
    
    private boolean hasAnyLabel(String[] assetLabels, String[] queryLabels) {
        if (assetLabels == null || queryLabels == null) {
            return false;
        }
        
        for (String queryLabel : queryLabels) {
            for (String assetLabel : assetLabels) {
                if (queryLabel.equals(assetLabel)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private AssetDescriptor toDescriptor(AssetRecord record) {
        AssetDescriptor desc = new AssetDescriptor();
        desc.assetId = record.assetId;
        desc.kind = record.manifest.kind;
        desc.version = record.manifest.version;
        desc.name = record.manifest.name;
        desc.description = record.manifest.description;
        desc.targetAppPackage = record.manifest.targetAppPackage;
        desc.appVersionRange = record.manifest.appVersionRange;
        desc.deviceModels = record.manifest.deviceModels;
        desc.capability = record.manifest.capability;
        desc.riskLevel = record.manifest.riskLevel;
        desc.status = record.status;
        desc.labels = record.manifest.labels;
        
        ExecutionStats stats = mExecutionStats.get(record.assetId);
        if (stats != null) {
            desc.hitCount = stats.totalCount;
            desc.successRate = (stats.totalCount > 0) 
                ? (float) stats.successCount / stats.totalCount : 0.0f;
            desc.lastUsedMs = stats.lastExecutionMs;
        } else {
            desc.hitCount = 0;
            desc.successRate = 0.0f;
            desc.lastUsedMs = 0;
        }
        
        return desc;
    }
    
    private void notifyAssetChanged(String assetId, int changeType, String version, 
                                   int newStatus, String reason) {
        AssetUpdateInfo info = new AssetUpdateInfo();
        info.assetId = assetId;
        info.changeType = changeType;
        info.version = version;
        info.newStatus = newStatus;
        info.reason = reason;
        info.timestampMs = System.currentTimeMillis();
        
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onAssetChanged(info);
            } catch (RemoteException e) {
                Slog.w(TAG, "notifyAssetChanged: listener exception", e);
            }
        }
        mListeners.finishBroadcast();
        
        Slog.d(TAG, "notifyAssetChanged: " + assetId + " type=" + changeType);
    }
    
    private void executeRegression(String jobId, String targetAssetId) {
        RegressionJob job = mRegressionJobs.get(jobId);
        if (job == null) {
            return;
        }
        
        List<String> testAssets = new ArrayList<>();
        
        if (targetAssetId == null || targetAssetId.isEmpty()) {
            // 全部启用资产
            for (AssetRecord record : mAssetStore.values()) {
                if (record.status == IAgentAssetLibraryService.ASSET_STATUS_ENABLED ||
                    record.status == IAgentAssetLibraryService.ASSET_STATUS_STAGED) {
                    testAssets.add(record.assetId);
                }
            }
        } else {
            testAssets.add(targetAssetId);
        }
        
        List<String> failed = new ArrayList<>();
        int passCount = 0;
        
        for (int i = 0; i < testAssets.size(); i++) {
            String assetId = testAssets.get(i);
            
            // 模拟回归测试
            boolean pass = simulateRegressionTest(assetId);
            
            if (pass) {
                passCount++;
                // 通过测试的staged资产可以启用
                AssetRecord record = mAssetStore.get(assetId);
                if (record != null && record.status == IAgentAssetLibraryService.ASSET_STATUS_STAGED) {
                    record.status = IAgentAssetLibraryService.ASSET_STATUS_ENABLED;
                    
                    // A8.2: 注册到动作注册表
                    registerAssetToActionRegistry(record);
                    
                    notifyAssetChanged(assetId, 3, record.manifest.version, record.status, 
                                      "regression passed");
                }
            } else {
                failed.add(assetId);
            }
            
            // 更新进度
            job.progress = (i + 1) * 100 / testAssets.size();
            job.passCount = passCount;
            job.failCount = failed.size();
            job.failedAssetIds = failed.toArray(new String[0]);
        }
        
        Slog.i(TAG, "executeRegression: job " + jobId + " completed " +
               "(pass=" + passCount + ", fail=" + failed.size() + ")");
    }
    
    private boolean simulateRegressionTest(String assetId) {
        // TODO: 实际的回归测试（执行资产冒烟用例）
        // 现在简单模拟：90%通过率
        return Math.random() > 0.1;
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    // ==================== A8.2 动作注册表桥接 ====================
    
    /**
     * 将资产注册到动作注册表（A8.2 资产与动作注册表统一）
     * 
     * 将 Skill/DMI/Macro 资产作为 executor=skill|dmi|macro 的动作登记
     */
    private void registerAssetToActionRegistry(AssetRecord record) {
        try {
            // 获取 ActionRegistry 实例
            com.ainative.action.registry.ActionRegistry registry = 
                com.ainative.action.registry.ActionRegistry.getInstance();
            
            // 构造 ActionRegistryEntry
            org.json.JSONObject inputSchema = parseJsonSchema(record.manifest.inputSchemaJson);
            org.json.JSONObject outputSchema = parseJsonSchema(record.manifest.outputSchemaJson);
            
            String executorId = kindToExecutorId(record.manifest.kind);
            
            com.ainative.action.registry.ActionRegistryEntry entry = 
                new com.ainative.action.registry.ActionRegistryEntry(
                    record.assetId,                         // name (使用 assetId)
                    record.manifest.id,                     // displayName
                    executorId,                             // executorId: skill/dmi/macro
                    record.manifest.riskLevel,              // riskLevel
                    record.manifest.capability,             // requiredCapability
                    inputSchema,                            // inputSchema
                    outputSchema,                           // outputSchema
                    null                                    // inputSource (暂无)
                );
            
            // 注册到动作注册表
            boolean success = registry.registerAssetAction(record.assetId, entry);
            
            if (success) {
                Slog.i(TAG, "Asset registered to ActionRegistry: " + record.assetId + 
                       " (executor=" + executorId + ", capability=" + record.manifest.capability + ")");
            } else {
                Slog.w(TAG, "Failed to register asset to ActionRegistry: " + record.assetId);
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error registering asset to ActionRegistry: " + record.assetId, e);
        }
    }
    
    /**
     * 从动作注册表移除资产
     */
    private void unregisterAssetFromActionRegistry(String assetId) {
        try {
            com.ainative.action.registry.ActionRegistry registry = 
                com.ainative.action.registry.ActionRegistry.getInstance();
            
            boolean success = registry.unregisterAssetAction(assetId);
            
            if (success) {
                Slog.i(TAG, "Asset unregistered from ActionRegistry: " + assetId);
            } else {
                Slog.w(TAG, "Failed to unregister asset from ActionRegistry: " + assetId);
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error unregistering asset from ActionRegistry: " + assetId, e);
        }
    }
    
    /**
     * 将资产类型转换为执行器 ID
     */
    private String kindToExecutorId(int kind) {
        switch (kind) {
            case IAgentAssetLibraryService.KIND_SKILL:
                return "skill";
            case IAgentAssetLibraryService.KIND_DMI:
                return "dmi";
            case IAgentAssetLibraryService.KIND_MACRO:
                return "macro";
            default:
                return "unknown";
        }
    }
    
    /**
     * 解析 JSON Schema 字符串
     */
    private org.json.JSONObject parseJsonSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isEmpty()) {
            return new org.json.JSONObject();
        }
        
        try {
            return new org.json.JSONObject(schemaJson);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to parse JSON schema, using empty object", e);
            return new org.json.JSONObject();
        }
    }
    
    // ==================== 内部类 ====================
    
    private static class AssetRecord {
        String assetId;
        AssetManifest manifest;
        byte[] signedPayload;
        int status;  // ASSET_STATUS_*
        long registeredTimeMs;
    }
    
    private static class ExecutionStats {
        int totalCount;
        int successCount;
        int failureCount;
        int consecutiveFailures;
        long lastExecutionMs;
        long lastFailureTimeMs;
        String lastFailureReason;
    }
}
