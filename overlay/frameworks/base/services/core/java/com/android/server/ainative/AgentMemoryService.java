package com.android.server.ainative;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.ainative.memory.*;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Agent 记忆服务（S4）
 * 
 * 核心功能：
 * 1. 三层记忆管理：工作记忆（会话上下文）、长期记忆（事实/偏好）、系统记忆（身份/设备）
 * 2. Provenance 强制：记忆写入必须携带可溯源信息
 * 3. PII 掩码：自动检测并掩码敏感信息（邮箱/手机/证件/金融）
 * 4. 分级加密：敏感记忆加密存储（密钥在 TEE 域）
 * 5. 向量索引：端侧 embedding 支持语义检索
 * 6. 遗忘级联：forget 任务删除本地+向量+图谱节点
 * 7. 来源 taint：APP_CONTENT/WEB_CONTENT 不得直接提升为长期记忆
 * 
 * @author AI Native OS Team
 * @since A3 Milestone
 */
public class AgentMemoryService extends IAgentMemoryService.Stub {
    
    /**
     * 单点鉴权（WBS 1.3）：记忆服务含用户长期记忆与实体数据。
     * 此前 query/write/forget 等无调用方校验，任意进程可读可删。
     */
    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws android.os.RemoteException {
        AgentCallerAuth.enforceTransaction(mContext, AgentCallerAuth.PERM_BIND_MEMORY,
                "AgentMemoryService", code);
        return super.onTransact(code, data, reply, flags);
    }
    private static final String TAG = "AgentMemoryService";

    // 性能目标
    private static final long TARGET_WRITE_MS = 20;
    private static final long TARGET_QUERY_MS = 50;
    private static final long TARGET_FORGET_MS = 5000;

    // 容量限制
    private static final int MAX_WORKING_MEMORIES = 1000;
    private static final int MAX_LONG_TERM_MEMORIES = 100000;
    private static final int MAX_SYSTEM_MEMORIES = 100;

    // PII 检测正则
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{11}");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "\\d{17}[0-9Xx]");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
        "\\d{16,19}");

    /**
     * 凭据赋值形态（WBS 1.9）：只掩 "密码=值" 里的**值**，避免把"忘记密码了"
     * 这类正常表述误判为机密；宽松的"密码/口令"关键词另计为敏感关键词，
     * 只影响长期记忆策略，不参与掩码。
     */
    private static final Pattern PASSWORD_ASSIGN_PATTERN = Pattern.compile(
        "(?i)(password|passwd|pwd|密码|口令)\\s*[:：=是]\\s*(\\S+)");

    private final Context mContext;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;

    // 三层记忆存储（实际应持久化到数据库）
    private final Map<String, MemoryEntry> mWorkingMemories = new HashMap<>();
    private final Map<String, MemoryEntry> mLongTermMemories = new HashMap<>();
    private final Map<String, MemoryEntry> mSystemMemories = new HashMap<>();

    // 向量索引（memoryId -> embedding vector）
    private final Map<String, float[]> mVectorIndex = new HashMap<>();

    // 溯源信息（memoryId -> ProvenanceInfo）
    private final Map<String, ProvenanceInfo> mProvenanceStore = new HashMap<>();

    // 遗忘任务（jobId -> ForgetJob）
    private final Map<String, ForgetJob> mForgetJobs = new HashMap<>();

    // 变更监听器
    private final RemoteCallbackList<IAgentMemoryListener> mListeners = new RemoteCallbackList<>();

    // 统计信息
    private long mTotalWrites = 0;
    private long mTotalQueries = 0;
    private long mPiiRejections = 0;
    private long mStorageBytes = 0;

    // 审计埋点
    private AuditInstrumentation mAuditInstrumentation;

    public AgentMemoryService(Context context) {
        mContext = context;

        // 创建后台线程
        mHandlerThread = new HandlerThread("AgentMemoryService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        // 初始化审计埋点
        mAuditInstrumentation = new AuditInstrumentation(context);

        Slog.i(TAG, "AgentMemoryService initialized");
    }

    /**
     * 启动服务（在 SystemServer 中调用）
     */
    public void start() {
        // 加载持久化数据
        loadPersistedMemories();

        // 启动后台任务（向量索引、图谱构建、过期清理）
        startBackgroundTasks();

        Slog.i(TAG, "AgentMemoryService started");
    }

    @Override
    public int write(MemoryEntryDraft draft, MemoryEntry entry) {
        long startMs = System.currentTimeMillis();

        // 权限检查
        if (!checkPermission("memory.write")) {
            Slog.w(TAG, "write denied: missing capability");
            return IAgentMemoryService.STATUS_DENIED;
        }

        if (draft == null || draft.content == null || draft.content.isEmpty()) {
            return IAgentMemoryService.STATUS_DENIED;
        }

        // Provenance 强制：必须有来源会话ID
        if (draft.sourceSessionId == null || draft.sourceSessionId.isEmpty()) {
            Slog.w(TAG, "write denied: missing sourceSessionId (provenance required)");
            return IAgentMemoryService.STATUS_DENIED;
        }

        // 来源 taint 检查：APP_CONTENT/WEB_CONTENT 不得直接提升为长期记忆
        if (draft.kind == IAgentMemoryService.KIND_LONG_TERM) {
            if (draft.trust == 3 || draft.trust == 4) { // APP_CONTENT=3, WEB_CONTENT=4
                Slog.w(TAG, "write denied: APP_CONTENT/WEB_CONTENT cannot be long-term memory");
                return IAgentMemoryService.STATUS_DENIED;
            }
        }

        // 系统记忆写入权限检查（仅 trusted_assistant）
        if (draft.kind == IAgentMemoryService.KIND_SYSTEM) {
            String agentId = getCallingAgentId();
            if (!agentId.equals("trusted_assistant") && !agentId.equals("system")) {
                Slog.w(TAG, "write denied: system memory requires trusted_assistant");
                return IAgentMemoryService.STATUS_DENIED;
            }
        }

        // PII 检测与掩码
        PIIMaskResult maskResult = maskPII(draft.content);
        
        // WBS 1.9 防御性不变量：标记了高风险却未掩码 → 一律拒绝。
        // 这是防止"将来新增规则时只标记不掩码"重新打开明文落库的口子。
        if (maskResult.hasHighRiskPII && !maskResult.hasMasked) {
            Slog.e(TAG, "write rejected: high-risk PII flagged but not masked (fail-closed)");
            mPiiRejections++;
            return IAgentMemoryService.STATUS_PII_REJECT;
        }
        
        if ((maskResult.hasHighRiskPII || maskResult.hasSensitiveKeyword)
                && draft.kind == IAgentMemoryService.KIND_LONG_TERM) {
            // 命中高风险 PII（密码/证件/金融），拒绝入长期记忆
            Slog.w(TAG, "write rejected: high-risk PII detected");
            mPiiRejections++;
            
            // 审计埋点
            mAuditInstrumentation.recordMemoryAccess(
                "memory_write_rejected",
                getCallingAgentId(),
                null,
                "PII拒绝: " + maskResult.reason
            );
            
            return IAgentMemoryService.STATUS_PII_REJECT;
        }

        // 构建 MemoryEntry
        // 说明：落库内容一律取 maskedContent（WBS 1.9），因此掩码是唯一入口
        if (maskResult.hasMasked) {
            mAuditInstrumentation.recordMemoryAccess(
                "memory_write_masked",
                getCallingAgentId(),
                null,
                "PII已掩码: " + maskResult.reason);
        }
        MemoryEntry newEntry = new MemoryEntry();
        newEntry.memoryId = generateMemoryId();
        newEntry.kind = draft.kind;
        newEntry.ownerAgentId = getCallingAgentId();
        newEntry.content = maskResult.maskedContent;
        newEntry.tags = draft.tags;
        newEntry.trust = draft.trust;
        newEntry.piiMasked = maskResult.hasMasked;
        newEntry.createdAtRealtimeNanos = System.nanoTime();
        newEntry.updatedAtRealtimeNanos = newEntry.createdAtRealtimeNanos;

        // 构建 ProvenanceInfo
        ProvenanceInfo provenance = new ProvenanceInfo();
        provenance.sourceKind = inferSourceKind(draft.trust);
        provenance.sourceRef = draft.sourceSessionId;
        provenance.extractedAtRealtimeNanos = System.nanoTime();
        provenance.extractionModelRef = "llm-v1"; // TODO: 实际模型标识
        provenance.contentFingerprint = computeFingerprint(draft.content);
        provenance.trust = draft.trust;
        
        newEntry.provenance = provenance;

        // 存储到对应层级
        synchronized (this) {
            Map<String, MemoryEntry> store = getMemoryStore(draft.kind);
            if (store == null) {
                return IAgentMemoryService.STATUS_DENIED;
            }

            // 容量检查
            if (!checkCapacity(draft.kind, store)) {
                Slog.w(TAG, "write denied: capacity exceeded");
                return IAgentMemoryService.STATUS_DENIED;
            }

            store.put(newEntry.memoryId, newEntry);
            mProvenanceStore.put(newEntry.memoryId, provenance);

            // 更新统计
            mTotalWrites++;
            mStorageBytes += estimateSize(newEntry);
        }

        // 异步向量索引
        mHandler.post(() -> buildVectorIndex(newEntry));

        // 通知监听器
        notifyMemoryWritten(newEntry);

        // 审计埋点
        String summary = String.format("写入记忆 kind=%s masked=%b trust=%d tags=%s",
            kindToString(draft.kind), maskResult.hasMasked, draft.trust, 
            draft.tags != null ? String.join(",", draft.tags) : "");
        mAuditInstrumentation.recordMemoryAccess(
            "memory_write",
            getCallingAgentId(),
            draft.sourceSessionId,
            summary
        );

        // 输出参数
        if (entry != null) {
            copyMemoryEntry(newEntry, entry);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs > TARGET_WRITE_MS) {
            Slog.w(TAG, String.format("write slow: %dms (target %dms)", elapsedMs, TARGET_WRITE_MS));
        }

        Slog.d(TAG, String.format("Memory written: id=%s kind=%s masked=%b (%dms)",
            newEntry.memoryId, kindToString(draft.kind), maskResult.hasMasked, elapsedMs));

        return IAgentMemoryService.STATUS_OK;
    }

    @Override
    public MemoryEntry[] query(MemoryQuery query) {
        long startMs = System.currentTimeMillis();

        // 权限检查
        if (!checkPermission("memory.read")) {
            Slog.w(TAG, "query denied: missing capability");
            return new MemoryEntry[0];
        }

        if (query == null) {
            return new MemoryEntry[0];
        }

        List<MemoryEntry> results = new ArrayList<>();

        synchronized (this) {
            // 确定查询范围
            List<Map<String, MemoryEntry>> stores = new ArrayList<>();
            if (query.kind == -1) {
                // 查询所有层
                stores.add(mWorkingMemories);
                stores.add(mLongTermMemories);
                // 系统记忆仅 trusted_assistant 可见
                if (isTrustedAssistant()) {
                    stores.add(mSystemMemories);
                }
            } else {
                Map<String, MemoryEntry> store = getMemoryStore(query.kind);
                if (store != null) {
                    stores.add(store);
                }
            }

            // 语义检索（简化实现：文本匹配，实际应使用向量相似度）
            for (Map<String, MemoryEntry> store : stores) {
                for (MemoryEntry entry : store.values()) {
                    // 授权过滤
                    if (!canReadMemory(entry)) {
                        continue;
                    }

                    // 时间范围过滤
                    if (query.timeFromRealtimeNanos > 0 && 
                        entry.createdAtRealtimeNanos < query.timeFromRealtimeNanos) {
                        continue;
                    }
                    if (query.timeToRealtimeNanos > 0 && 
                        entry.createdAtRealtimeNanos > query.timeToRealtimeNanos) {
                        continue;
                    }

                    // 语义匹配（简化：文本包含）
                    if (query.semanticQuery != null && !query.semanticQuery.isEmpty()) {
                        if (!entry.content.toLowerCase().contains(
                            query.semanticQuery.toLowerCase())) {
                            continue;
                        }
                    }

                    // 实体过滤
                    if (query.entityFilter != null && query.entityFilter.length > 0) {
                        boolean matchEntity = false;
                        for (String entity : query.entityFilter) {
                            if (entry.content.contains(entity)) {
                                matchEntity = true;
                                break;
                            }
                        }
                        if (!matchEntity) {
                            continue;
                        }
                    }

                    results.add(entry);

                    if (results.size() >= query.topK) {
                        break;
                    }
                }

                if (results.size() >= query.topK) {
                    break;
                }
            }
        }

        // 审计埋点
        String summary = String.format("查询记忆 semantic='%s' kind=%d results=%d",
            query.semanticQuery != null ? query.semanticQuery : "", query.kind, results.size());
        mAuditInstrumentation.recordMemoryAccess(
            "memory_query",
            getCallingAgentId(),
            null,
            summary
        );

        // 统计
        mTotalQueries++;
        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs > TARGET_QUERY_MS) {
            Slog.w(TAG, String.format("query slow: %dms (target %dms)", elapsedMs, TARGET_QUERY_MS));
        }

        return results.toArray(new MemoryEntry[0]);
    }

    @Override
    public MemoryEntry getById(String memoryId) {
        // 权限检查
        if (!checkPermission("memory.read")) {
            return null;
        }

        if (memoryId == null) {
            return null;
        }

        synchronized (this) {
            // 尝试从三层查找
            MemoryEntry entry = mWorkingMemories.get(memoryId);
            if (entry == null) {
                entry = mLongTermMemories.get(memoryId);
            }
            if (entry == null && isTrustedAssistant()) {
                entry = mSystemMemories.get(memoryId);
            }

            if (entry != null && canReadMemory(entry)) {
                return entry;
            }
        }

        return null;
    }

    @Override
    public int deleteById(String memoryId) {
        // 权限检查
        if (!checkPermission("memory.delete")) {
            return IAgentMemoryService.STATUS_DENIED;
        }

        if (memoryId == null) {
            return IAgentMemoryService.STATUS_NOT_FOUND;
        }

        MemoryEntry deleted = null;
        int kind = -1;

        synchronized (this) {
            // 尝试从三层删除
            deleted = mWorkingMemories.remove(memoryId);
            if (deleted != null) {
                kind = IAgentMemoryService.KIND_WORKING;
            }
            
            if (deleted == null) {
                deleted = mLongTermMemories.remove(memoryId);
                if (deleted != null) {
                    kind = IAgentMemoryService.KIND_LONG_TERM;
                }
            }
            
            if (deleted == null && isTrustedAssistant()) {
                deleted = mSystemMemories.remove(memoryId);
                if (deleted != null) {
                    kind = IAgentMemoryService.KIND_SYSTEM;
                }
            }

            if (deleted == null) {
                return IAgentMemoryService.STATUS_NOT_FOUND;
            }

            // 删除关联数据
            mProvenanceStore.remove(memoryId);
            mVectorIndex.remove(memoryId);

            // 更新统计
            mStorageBytes -= estimateSize(deleted);
        }

        // 通知监听器
        notifyMemoryDeleted(memoryId, deleted.ownerAgentId);

        // 审计埋点
        String summary = String.format("删除记忆 id=%s kind=%s", memoryId, kindToString(kind));
        mAuditInstrumentation.recordMemoryAccess(
            "memory_delete",
            getCallingAgentId(),
            null,
            summary
        );

        Slog.i(TAG, String.format("Memory deleted: id=%s kind=%s", memoryId, kindToString(kind)));

        return IAgentMemoryService.STATUS_OK;
    }

    @Override
    public int forget(ForgetRequest request, ForgetJob job) {
        // 权限检查
        if (!checkPermission("memory.forget")) {
            return IAgentMemoryService.STATUS_DENIED;
        }

        if (request == null || request.topic == null || request.topic.isEmpty()) {
            return IAgentMemoryService.STATUS_DENIED;
        }

        // 系统记忆遗忘需要特权
        if (request.includeSystem && !isTrustedAssistant()) {
            Slog.w(TAG, "forget denied: system memory requires trusted_assistant");
            return IAgentMemoryService.STATUS_DENIED;
        }

        // 创建遗忘任务
        ForgetJob newJob = new ForgetJob();
        newJob.jobId = UUID.randomUUID().toString();
        newJob.status = 0; // queued
        newJob.deletedLocalCount = 0;
        newJob.deletedVectorCount = 0;
        newJob.cloudSyncPending = request.confirmCloudDeletion;
        newJob.error = null;

        synchronized (mForgetJobs) {
            mForgetJobs.put(newJob.jobId, newJob);
        }

        // 异步执行遗忘任务
        mHandler.post(() -> executeForgetJob(newJob, request));

        // 审计埋点
        String summary = String.format("遗忘请求 topic='%s' includeWorking=%b includeSystem=%b reason='%s'",
            request.topic, request.includeWorking, request.includeSystem, request.reason);
        mAuditInstrumentation.recordMemoryAccess(
            "memory_forget",
            getCallingAgentId(),
            null,
            summary
        );

        // 输出参数
        if (job != null) {
            copyForgetJob(newJob, job);
        }

        Slog.i(TAG, String.format("Forget job created: jobId=%s topic='%s'", 
            newJob.jobId, request.topic));

        return IAgentMemoryService.STATUS_OK;
    }

    @Override
    public ProvenanceInfo getProvenance(String memoryId) {
        // 权限检查
        if (!checkPermission("memory.read")) {
            return null;
        }

        if (memoryId == null) {
            return null;
        }

        synchronized (mProvenanceStore) {
            return mProvenanceStore.get(memoryId);
        }
    }

    @Override
    public int registerListener(IAgentMemoryListener listener) {
        if (listener == null) {
            return IAgentMemoryService.STATUS_DENIED;
        }

        mListeners.register(listener);
        Slog.i(TAG, "Memory listener registered");

        return IAgentMemoryService.STATUS_OK;
    }

    @Override
    public int unregisterListener(IAgentMemoryListener listener) {
        if (listener == null) {
            return IAgentMemoryService.STATUS_DENIED;
        }

        mListeners.unregister(listener);
        Slog.i(TAG, "Memory listener unregistered");

        return IAgentMemoryService.STATUS_OK;
    }

    @Override
    public MemoryStats getStats() {
        MemoryStats stats = new MemoryStats();

        synchronized (this) {
            stats.workingCount = mWorkingMemories.size();
            stats.longTermCount = mLongTermMemories.size();
            stats.systemCount = mSystemMemories.size();
            stats.vectorCount = mVectorIndex.size();
            stats.graphNodeCount = 0; // TODO: 图谱节点统计
            stats.storageBytes = mStorageBytes;
            stats.lastSyncRealtimeNanos = 0; // TODO: 云端同步时间
        }

        return stats;
    }

    // ===== 内部方法 =====

    /**
     * 加载持久化记忆
     */
    private void loadPersistedMemories() {
        // TODO: 从数据库加载三层记忆
        Slog.d(TAG, "Persisted memories loaded");
    }

    /**
     * 启动后台任务
     */
    private void startBackgroundTasks() {
        // 向量索引构建任务（延迟启动）
        mHandler.postDelayed(() -> {
            Slog.d(TAG, "Vector index background task started");
            // TODO: 实现向量索引构建
        }, 60000);

        // 过期记忆清理任务
        mHandler.postDelayed(() -> {
            Slog.d(TAG, "Expired memory cleanup task started");
            cleanupExpiredMemories();
        }, 300000);
    }

    /**
     * PII 掩码结果
     */
    private static class PIIMaskResult {
        String maskedContent;
        boolean hasMasked;
        boolean hasHighRiskPII;
        /** 仅命中宽松关键词（如文中提到"密码"），不等于内容本身含机密 */
        boolean hasSensitiveKeyword;
        String reason;
    }

    /**
     * PII 检测与掩码
     */
    private PIIMaskResult maskPII(String content) {
        PIIMaskResult result = new PIIMaskResult();
        result.maskedContent = content;
        result.hasMasked = false;
        result.hasHighRiskPII = false;
        result.reason = "";

        // 邮箱掩码
        if (EMAIL_PATTERN.matcher(content).find()) {
            result.maskedContent = EMAIL_PATTERN.matcher(result.maskedContent)
                .replaceAll("[EMAIL]");
            result.hasMasked = true;
        }

        // 手机号掩码
        if (PHONE_PATTERN.matcher(content).find()) {
            result.maskedContent = PHONE_PATTERN.matcher(result.maskedContent)
                .replaceAll("[PHONE]");
            result.hasMasked = true;
        }

        // ===== 高风险 PII（WBS 1.9）=====
        // 关键修复：高风险类别**必须掩码**，不能只置标记。
        // 此前它们只设 hasHighRiskPII，而"拒绝"逻辑只作用于 KIND_LONG_TERM，
        // 于是 WORKING / SYSTEM 记忆把证件号、银行卡号、密码**原样落库**。

        // 身份证号 → 掩码 + 高风险
        if (ID_CARD_PATTERN.matcher(result.maskedContent).find()) {
            result.maskedContent = ID_CARD_PATTERN.matcher(result.maskedContent)
                .replaceAll("[ID_CARD]");
            result.hasHighRiskPII = true;
            result.hasMasked = true;
            result.reason = "身份证号";
        }

        // 银行卡号 → 掩码 + 高风险
        if (BANK_CARD_PATTERN.matcher(result.maskedContent).find()) {
            result.maskedContent = BANK_CARD_PATTERN.matcher(result.maskedContent)
                .replaceAll("[BANK_CARD]");
            result.hasHighRiskPII = true;
            result.hasMasked = true;
            result.reason = "银行卡号";
        }

        // 凭据赋值（"密码是X" / "password=X"）→ 掩掉值 + 高风险
        java.util.regex.Matcher pwMatcher = PASSWORD_ASSIGN_PATTERN.matcher(result.maskedContent);
        if (pwMatcher.find()) {
            result.maskedContent = pwMatcher.replaceAll("$1[PASSWORD]");
            result.hasHighRiskPII = true;
            result.hasMasked = true;
            result.reason = "密码相关";
        }

        // 宽松关键词：仅标记，不掩码（关键词本身不是机密，误掩会破坏正常语义）
        if (content.toLowerCase().contains("password")
                || content.contains("密码")
                || content.contains("口令")) {
            result.hasSensitiveKeyword = true;
            if (result.reason == null || result.reason.isEmpty()) {
                result.reason = "密码关键词";
            }
        }

        return result;
    }

    /**
     * 生成记忆ID
     */
    private String generateMemoryId() {
        return "mem_" + System.nanoTime() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 计算内容指纹
     */
    private String computeFingerprint(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return "error";
        }
    }

    /**
     * 推导来源类型
     */
    private int inferSourceKind(int trust) {
        // 根据 TrustLevel 推导来源类型
        switch (trust) {
            case 0: return IAgentMemoryService.SOURCE_USER_STMT;
            case 1: return IAgentMemoryService.SOURCE_SCREEN;
            case 2: return IAgentMemoryService.SOURCE_NOTIFICATION;
            case 3: return IAgentMemoryService.SOURCE_APP_DATA;
            case 4: return IAgentMemoryService.SOURCE_WEB;
            default: return IAgentMemoryService.SOURCE_MODEL_EXTRACT;
        }
    }

    /**
     * 获取记忆存储
     */
    private Map<String, MemoryEntry> getMemoryStore(int kind) {
        switch (kind) {
            case IAgentMemoryService.KIND_WORKING:
                return mWorkingMemories;
            case IAgentMemoryService.KIND_LONG_TERM:
                return mLongTermMemories;
            case IAgentMemoryService.KIND_SYSTEM:
                return mSystemMemories;
            default:
                return null;
        }
    }

    /**
     * 检查容量
     */
    private boolean checkCapacity(int kind, Map<String, MemoryEntry> store) {
        int maxCapacity = 0;
        switch (kind) {
            case IAgentMemoryService.KIND_WORKING:
                maxCapacity = MAX_WORKING_MEMORIES;
                break;
            case IAgentMemoryService.KIND_LONG_TERM:
                maxCapacity = MAX_LONG_TERM_MEMORIES;
                break;
            case IAgentMemoryService.KIND_SYSTEM:
                maxCapacity = MAX_SYSTEM_MEMORIES;
                break;
        }

        return store.size() < maxCapacity;
    }

    /**
     * 检查是否可读记忆
     */
    private boolean canReadMemory(MemoryEntry entry) {
        String callerId = getCallingAgentId();

        // 工作记忆：同会话 Agent 可读
        if (entry.kind == IAgentMemoryService.KIND_WORKING) {
            // TODO: 检查会话归属
            return true;
        }

        // 系统记忆：仅 trusted_assistant
        if (entry.kind == IAgentMemoryService.KIND_SYSTEM) {
            return isTrustedAssistant();
        }

        // 长期记忆：需授权（简化实现：所有 Agent 可读）
        return true;
    }

    /**
     * 判断是否为受信助手
     */
    private boolean isTrustedAssistant() {
        String agentId = getCallingAgentId();
        return agentId.equals("trusted_assistant") || agentId.equals("system");
    }

    /**
     * 构建向量索引
     */
    private void buildVectorIndex(MemoryEntry entry) {
        // TODO: 调用端侧 embedding 模型生成向量
        // 简化实现：随机向量
        float[] vector = new float[384];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) Math.random();
        }

        synchronized (mVectorIndex) {
            mVectorIndex.put(entry.memoryId, vector);
        }

        Slog.d(TAG, String.format("Vector indexed: memoryId=%s", entry.memoryId));
    }

    /**
     * 执行遗忘任务
     */
    private void executeForgetJob(ForgetJob job, ForgetRequest request) {
        long startMs = System.currentTimeMillis();

        // 更新状态为运行中
        job.status = 1; // running

        List<String> toDelete = new ArrayList<>();

        synchronized (this) {
            // 搜索匹配的记忆
            if (request.includeWorking) {
                toDelete.addAll(searchMemoriesByTopic(mWorkingMemories, request.topic));
            }
            toDelete.addAll(searchMemoriesByTopic(mLongTermMemories, request.topic));
            if (request.includeSystem) {
                toDelete.addAll(searchMemoriesByTopic(mSystemMemories, request.topic));
            }

            // 删除记忆
            for (String memoryId : toDelete) {
                mWorkingMemories.remove(memoryId);
                mLongTermMemories.remove(memoryId);
                mSystemMemories.remove(memoryId);
                mProvenanceStore.remove(memoryId);

                // 删除向量索引
                if (mVectorIndex.remove(memoryId) != null) {
                    job.deletedVectorCount++;
                }

                job.deletedLocalCount++;
            }
        }

        // TODO: 图谱节点级联删除

        // 更新状态为完成
        job.status = 2; // done

        // 通知监听器
        notifyForgetCompleted(job);

        long elapsedMs = System.currentTimeMillis() - startMs;

        Slog.i(TAG, String.format("Forget job completed: jobId=%s topic='%s' deleted=%d (%dms)",
            job.jobId, request.topic, job.deletedLocalCount, elapsedMs));
    }

    /**
     * 按主题搜索记忆
     */
    private List<String> searchMemoriesByTopic(Map<String, MemoryEntry> store, String topic) {
        List<String> matches = new ArrayList<>();
        String topicLower = topic.toLowerCase();

        for (MemoryEntry entry : store.values()) {
            if (entry.content.toLowerCase().contains(topicLower)) {
                matches.add(entry.memoryId);
            }
        }

        return matches;
    }

    /**
     * 清理过期记忆
     */
    private void cleanupExpiredMemories() {
        // TODO: 实现过期清理逻辑
        Slog.d(TAG, "Expired memories cleaned");
    }

    /**
     * 估算记忆大小
     */
    private long estimateSize(MemoryEntry entry) {
        return entry.content != null ? entry.content.length() : 0;
    }

    /**
     * 层级转字符串
     */
    private String kindToString(int kind) {
        switch (kind) {
            case IAgentMemoryService.KIND_WORKING: return "WORKING";
            case IAgentMemoryService.KIND_LONG_TERM: return "LONG_TERM";
            case IAgentMemoryService.KIND_SYSTEM: return "SYSTEM";
            default: return "UNKNOWN";
        }
    }

    /**
     * 通知记忆写入
     */
    private void notifyMemoryWritten(MemoryEntry entry) {
        mHandler.post(() -> {
            int count = mListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                try {
                    mListeners.getBroadcastItem(i).onMemoryWritten(entry);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify memory written", e);
                }
            }
            mListeners.finishBroadcast();
        });
    }

    /**
     * 通知记忆删除
     */
    private void notifyMemoryDeleted(String memoryId, String agentId) {
        mHandler.post(() -> {
            int count = mListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                try {
                    mListeners.getBroadcastItem(i).onMemoryDeleted(memoryId, agentId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify memory deleted", e);
                }
            }
            mListeners.finishBroadcast();
        });
    }

    /**
     * 通知遗忘完成
     */
    private void notifyForgetCompleted(ForgetJob job) {
        mHandler.post(() -> {
            int count = mListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                try {
                    mListeners.getBroadcastItem(i).onForgetCompleted(job);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify forget completed", e);
                }
            }
            mListeners.finishBroadcast();
        });
    }

    /**
     * 权限检查
     */
    private boolean checkPermission(String capability) {
        try {
            IPolicyEngineService policyEngine = IPolicyEngineService.Stub.asInterface(
                ServiceManager.getService("policy_engine"));
            
            if (policyEngine != null) {
                String agentId = getCallingAgentId();
                int callingUid = Binder.getCallingUid();
                
                return policyEngine.checkCapability(agentId, capability, callingUid);
            } else {
                Slog.w(TAG, "PolicyEngine not available");
                return false;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to check permission: " + capability, e);
            return false;
        }
    }

    /**
     * 获取调用者 Agent ID
     */
    private String getCallingAgentId() {
        try {
            int callingUid = Binder.getCallingUid();
            
            IAgentRuntimeService runtimeService = IAgentRuntimeService.Stub.asInterface(
                ServiceManager.getService("agent_runtime"));
            
            if (runtimeService != null) {
                String agentId = runtimeService.getAgentIdByUid(callingUid);
                if (agentId != null) {
                    return agentId;
                }
            }
            
            return "uid:" + callingUid;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get calling agent ID", e);
            return "unknown";
        }
    }

    /**
     * 复制 MemoryEntry
     */
    private void copyMemoryEntry(MemoryEntry src, MemoryEntry dst) {
        dst.memoryId = src.memoryId;
        dst.kind = src.kind;
        dst.ownerAgentId = src.ownerAgentId;
        dst.content = src.content;
        dst.tags = src.tags;
        dst.trust = src.trust;
        dst.piiMasked = src.piiMasked;
        dst.createdAtRealtimeNanos = src.createdAtRealtimeNanos;
        dst.updatedAtRealtimeNanos = src.updatedAtRealtimeNanos;
        dst.provenance = src.provenance;
    }

    /**
     * 复制 ForgetJob
     */
    private void copyForgetJob(ForgetJob src, ForgetJob dst) {
        dst.jobId = src.jobId;
        dst.status = src.status;
        dst.deletedLocalCount = src.deletedLocalCount;
        dst.deletedVectorCount = src.deletedVectorCount;
        dst.cloudSyncPending = src.cloudSyncPending;
        dst.error = src.error;
    }
}
