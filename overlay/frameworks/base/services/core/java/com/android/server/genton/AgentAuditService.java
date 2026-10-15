package com.android.server.genton;

import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.json.JSONObject;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Agent 审计引擎服务（Agent Audit Service）
 * 
 * 职责：
 * 1. 记录所有 Agent 与系统行为事件（感知/执行/记忆/策略/确认/系统）
 * 2. 建立防篡改哈希链（每条事件包含 prevHash）
 * 3. 周期性 TEE 锚定（将哈希根写入安全存储）
 * 4. 提供链完整性校验（verifyChain）
 * 5. 按 viewerRole 脱敏查询（USER 仅见摘要，ADMIN_BRAIN 含详情）
 * 6. 实时活性指示器（驱动 SystemUI "在读屏/在操作"）
 * 
 * 核心特性：
 * - 只增不改：无 update/delete 接口
 * - 防篡改：哈希链 + TEE 锚点
 * - 可审计：所有写入均可追溯来源
 * - 隐私保护：按角色脱敏（用户仅见摘要）
 * 
 * 性能要求：
 * - 写入延迟 < 5ms（不阻塞业务）
 * - 查询 1000 条 < 50ms
 * - 链校验 10000 条 < 500ms
 * 
 * 安全约束：
 * - 写权限：WRITE_AUDIT（signature|privileged）
 * - 读权限：READ_AUDIT（signature|privileged）
 * - TEE 锚定：依赖 Keystore TEE 能力
 * 
 * @see IAgentAuditService
 */
public class AgentAuditService {
    private static final String TAG = "AgentAuditService";
    
    // 状态码
    public static final int STATUS_OK = 0;
    public static final int STATUS_DENIED = 1;
    public static final int STATUS_INVALID_EVENT = 2;
    public static final int STATUS_INTERNAL_ERROR = -1;
    
    // 事件分类
    public static final int CAT_PERCEPTION_READ = 1;   // 感知：读屏/UI树/通知
    public static final int CAT_ACTION_EXECUTE = 2;    // 执行：动作提交/成功/失败
    public static final int CAT_MEMORY_ACCESS = 3;     // 记忆：read/write/forget
    public static final int CAT_POLICY_DECISION = 4;   // 策略：令牌发放/拒绝/确认结果
    public static final int CAT_CAPABILITY_GRANT = 5;  // 能力授予
    public static final int CAT_CONFIRMATION = 6;      // 用户确认
    public static final int CAT_SYSTEM = 7;            // 服务启动/升级/挂起
    
    // Viewer 角色
    public static final int VIEWER_USER = 0;           // 用户（脱敏摘要）
    public static final int VIEWER_ADMIN_BRAIN = 1;    // 管理员/大脑（含详情）
    
    // TEE 锚定周期（每 N 条事件）
    private static final int ANCHOR_INTERVAL = 100;
    
    private final Context mContext;
    
    // 审计链（内存 + 持久化）
    private final List<AuditEvent> mEventChain;
    private final ReentrantReadWriteLock mChainLock;
    
    // 序列号生成器
    private final AtomicLong mSeqGenerator;
    
    // ===== 持久化（WBS 1.6）=====
    // 审计链此前是纯内存 ArrayList：进程重启即全丢，"可回溯"的信任基础不成立。
    private static final String AUDIT_DIR = "/data/system/genton/audit";
    private static final String AGENT_CHAIN_FILE = "agent_audit_chain.jsonl";
    private final File mChainFile;
    
    // TEE/Keystore 锚点管理器（此前被调用但从未实例化 → 每次锚定 NPE 后被吞掉）
    private final TeeAnchorManager mTeeAnchorManager;
    
    // 最近锚点
    private ChainAnchor mLastAnchor;
    
    // 活性指示器监听器
    private final RemoteCallbackList<IAgentAuditListener> mActivityListeners;
    
    // 统计
    private long mTotalEvents = 0;
    private long mVerifySuccessCount = 0;
    private long mVerifyFailureCount = 0;
    
    public AgentAuditService(Context context) {
        mContext = context;
        mEventChain = new ArrayList<>();
        mChainLock = new ReentrantReadWriteLock();
        mSeqGenerator = new AtomicLong(0);
        mActivityListeners = new RemoteCallbackList<>();
        
        // 初始化锚点
        mLastAnchor = new ChainAnchor();
        mLastAnchor.seq = 0;
        mLastAnchor.anchorRef = "genesis";
        mLastAnchor.anchoredAtRealtimeNanos = System.nanoTime();
        mLastAnchor.anchorHash = computeHash("genesis");
        
        // 持久化与锚点管理器（WBS 1.6）
        mChainFile = new File(AUDIT_DIR, AGENT_CHAIN_FILE);
        mTeeAnchorManager = new TeeAnchorManager(mContext);
        
        // 启动时恢复链并校验连续性：重启后 seq 必须继续单调递增，
        // 且 prevHash 必须与上一条 eventId 相接（断链会被显式记录）
        restoreChainFromDisk();
        
        Slog.i(TAG, "AgentAuditService initialized with genesis anchor");
    }
    
    /**
     * 记录审计事件
     * 
     * @param event 事件对象
     * @return eventId（事件哈希）
     */
    public String recordEvent(AuditEvent event) {
        // 1. 调用方边界（WBS 1.6）
        //    本类**不是** Binder 服务，只有 system_server 进程内可调用；
        //    对外暴露的 IAuditService 由 AuditService 承担，其 onTransact 已做
        //    BIND_AUDIT_SERVICE 鉴权（WBS 1.3）。因此这里不做重复的 UID 判定，
        //    但必须保持"不注册为服务"这一前提：一旦有人把本类挂到 ServiceManager，
        //    就等于开出一个无鉴权的审计写入接口。
        if (mTeeAnchorManager == null) {
            Slog.w(TAG, "Audit anchoring unavailable; events still persist");
        }
        
        // 2. 校验事件有效性
        if (!isValidEvent(event)) {
            Slog.w(TAG, "Invalid event: category=" + event.category);
            return null;
        }
        
        mChainLock.writeLock().lock();
        try {
            // 3. 签发序列号
            event.seq = mSeqGenerator.incrementAndGet();
            event.timestampRealtimeNanos = System.nanoTime();
            
            // 4. 计算 prevHash
            if (!mEventChain.isEmpty()) {
                AuditEvent prevEvent = mEventChain.get(mEventChain.size() - 1);
                event.prevHash = prevEvent.eventId;
            } else {
                event.prevHash = mLastAnchor.anchorHash;
            }
            
            // 5. 计算 payloadHash
            event.payloadHash = computePayloadHash(event);
            
            // 6. 计算 eventId（防篡改哈希）
            event.eventId = computeEventId(event);
            
            // 7. 关联锚点
            event.anchorRef = mLastAnchor.anchorRef;
            
            // 8. 追加到链
            mEventChain.add(event);
            mTotalEvents++;
            
            // 8.5 落盘 + fsync（WBS 1.6）：证据必须在进程被杀/掉电后仍存在
            if (!persistEvent(event)) {
                Slog.e(TAG, "Audit event NOT persisted: seq=" + event.seq
                        + " eventId=" + event.eventId);
            }
            
            // 9. 周期性 TEE 锚定
            if (event.seq % ANCHOR_INTERVAL == 0) {
                performTeeAnchoring(event);
            }
            
            // 10. 发布活性指示器
            if (shouldIndicateActivity(event.category)) {
                notifyActivityIndicator(event, true);
            }
            
            Slog.d(TAG, String.format("Recorded event: seq=%d, cat=%d, agent=%s",
                event.seq, event.category, event.agentId));
            
            return event.eventId;
            
        } finally {
            mChainLock.writeLock().unlock();
        }
    }
    
    /**
     * 查询审计事件
     * 
     * @param query 查询条件
     * @return 事件列表（按 viewerRole 脱敏）
     */
    public List<AuditEvent> queryEvents(AuditQuery query) {
        mChainLock.readLock().lock();
        try {
            List<AuditEvent> results = new ArrayList<>();
            
            int startIdx = query.seqFrom > 0 ? (int)(query.seqFrom - 1) : 0;
            int endIdx = query.seqTo > 0 ? (int)query.seqTo : mEventChain.size();
            
            endIdx = Math.min(endIdx, mEventChain.size());
            
            for (int i = startIdx; i < endIdx && results.size() < query.limit; i++) {
                AuditEvent event = mEventChain.get(i);
                
                // 过滤
                if (query.agentIdFilter != null && !query.agentIdFilter.isEmpty() &&
                    !query.agentIdFilter.equals(event.agentId)) {
                    continue;
                }
                
                if (query.categoryFilter > 0 && query.categoryFilter != event.category) {
                    continue;
                }
                
                if (query.intentIdFilter != null && !query.intentIdFilter.isEmpty() &&
                    !query.intentIdFilter.equals(event.intentId)) {
                    continue;
                }
                
                // 脱敏
                AuditEvent sanitized = sanitizeEvent(event, query.viewerRole);
                results.add(sanitized);
            }
            
            return results;
            
        } finally {
            mChainLock.readLock().unlock();
        }
    }
    
    /**
     * 校验链完整性
     * 
     * @param fromSeq 起始序列号
     * @param toSeq 结束序列号
     * @return 链状态
     */
    public ChainStatus verifyChain(long fromSeq, long toSeq) {
        long startNanos = System.nanoTime();
        
        ChainStatus status = new ChainStatus();
        status.fromSeq = fromSeq;
        status.toSeq = toSeq;
        status.verified = true;
        status.brokenAtSeq = 0;
        status.anchorRef = mLastAnchor.anchorRef;
        status.anchoredAtRealtimeNanos = mLastAnchor.anchoredAtRealtimeNanos;
        
        mChainLock.readLock().lock();
        try {
            int startIdx = (int)(fromSeq - 1);
            int endIdx = (int)toSeq;
            
            if (startIdx < 0 || endIdx > mEventChain.size()) {
                status.verified = false;
                Slog.w(TAG, "Invalid seq range: " + fromSeq + " - " + toSeq);
                mVerifyFailureCount++;
                return status;
            }
            
            // 校验链
            String expectedPrevHash = (startIdx == 0) ? mLastAnchor.anchorHash : null;
            
            for (int i = startIdx; i < endIdx; i++) {
                AuditEvent event = mEventChain.get(i);
                
                // 校验 prevHash
                if (expectedPrevHash != null && !expectedPrevHash.equals(event.prevHash)) {
                    status.verified = false;
                    status.brokenAtSeq = event.seq;
                    Slog.e(TAG, String.format("Chain broken at seq=%d: expected prevHash=%s, actual=%s",
                        event.seq, expectedPrevHash, event.prevHash));
                    mVerifyFailureCount++;
                    return status;
                }
                
                // 校验 eventId
                String computedEventId = computeEventId(event);
                if (!computedEventId.equals(event.eventId)) {
                    status.verified = false;
                    status.brokenAtSeq = event.seq;
                    Slog.e(TAG, String.format("Event tampered at seq=%d: computed=%s, stored=%s",
                        event.seq, computedEventId, event.eventId));
                    mVerifyFailureCount++;
                    return status;
                }
                
                expectedPrevHash = event.eventId;
            }
            
            long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
            Slog.d(TAG, String.format("Chain verified: [%d, %d] (%dµs)", fromSeq, toSeq, elapsedMicros));
            
            mVerifySuccessCount++;
            return status;
            
        } finally {
            mChainLock.readLock().unlock();
        }
    }
    
    /**
     * 获取链末端锚点信息
     */
    public ChainStatus getChainAnchor() {
        ChainStatus status = new ChainStatus();
        status.verified = true;
        status.fromSeq = mLastAnchor.seq;
        status.toSeq = mSeqGenerator.get();
        status.brokenAtSeq = 0;
        status.anchorRef = mLastAnchor.anchorRef;
        status.anchoredAtRealtimeNanos = mLastAnchor.anchoredAtRealtimeNanos;
        return status;
    }
    
    /**
     * 注册活性监听器
     */
    public int registerActivityListener(IAgentAuditListener listener) {
        if (listener == null) {
            return STATUS_INVALID_EVENT;
        }
        
        mActivityListeners.register(listener);
        Slog.d(TAG, "Activity listener registered");
        return STATUS_OK;
    }
    
    /**
     * 注销活性监听器
     */
    public int unregisterActivityListener(IAgentAuditListener listener) {
        if (listener == null) {
            return STATUS_INVALID_EVENT;
        }
        
        mActivityListeners.unregister(listener);
        Slog.d(TAG, "Activity listener unregistered");
        return STATUS_OK;
    }
    
    // ========== 私有方法 ==========
    
    /**
     * 校验事件有效性
     */
    private boolean isValidEvent(AuditEvent event) {
        if (event == null) {
            return false;
        }
        
        if (event.category < CAT_PERCEPTION_READ || event.category > CAT_SYSTEM) {
            return false;
        }
        
        if (event.summary == null || event.summary.isEmpty()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 计算负载哈希
     */
    private String computePayloadHash(AuditEvent event) {
        try {
            // 对敏感负载计算哈希（不存明文）
            String payload = event.summary + "|" + event.agentId + "|" + event.intentId;
            return computeHash(payload);
        } catch (Exception e) {
            Slog.e(TAG, "computePayloadHash failed", e);
            return "";
        }
    }
    
    /**
     * 计算事件 ID（防篡改哈希）
     */
    private String computeEventId(AuditEvent event) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(event.seq).append("|");
            sb.append(event.timestampRealtimeNanos).append("|");
            sb.append(event.category).append("|");
            sb.append(event.agentId != null ? event.agentId : "").append("|");
            sb.append(event.intentId != null ? event.intentId : "").append("|");
            sb.append(event.summary).append("|");
            sb.append(event.payloadHash).append("|");
            sb.append(event.prevHash != null ? event.prevHash : "");
            
            return computeHash(sb.toString());
        } catch (Exception e) {
            Slog.e(TAG, "computeEventId failed", e);
            return "";
        }
    }
    
    /**
     * 计算 SHA-256 哈希
     */
    private String computeHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hash).substring(0, 32);
        } catch (Exception e) {
            Slog.e(TAG, "computeHash failed", e);
            return "";
        }
    }
    
    // ==================== 持久化（WBS 1.6）====================
    
    /**
     * 追加落盘并 fsync。
     * 采用 JSONL（一行一事件）追加写：崩溃只会丢"最后一条未完成的写入"，
     * 不会破坏已写入的历史；fsync 保证返回后数据已到介质。
     */
    private boolean persistEvent(AuditEvent event) {
        try {
            File dir = mChainFile.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                Slog.e(TAG, "Cannot create audit dir: " + dir);
                return false;
            }
            try (FileOutputStream fos = new FileOutputStream(mChainFile, true);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(toJsonLine(event));
                writer.write("\n");
                writer.flush();
                fos.getFD().sync();
            }
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to persist audit event", e);
            return false;
        }
    }
    
    private String toJsonLine(AuditEvent event) {
        try {
            JSONObject o = new JSONObject();
            o.put("seq", event.seq);
            o.put("eventId", event.eventId == null ? JSONObject.NULL : event.eventId);
            o.put("prevHash", event.prevHash == null ? JSONObject.NULL : event.prevHash);
            o.put("payloadHash", event.payloadHash == null ? JSONObject.NULL : event.payloadHash);
            o.put("category", event.category);
            o.put("agentId", event.agentId == null ? JSONObject.NULL : event.agentId);
            o.put("intentId", event.intentId == null ? JSONObject.NULL : event.intentId);
            o.put("summary", event.summary == null ? JSONObject.NULL : event.summary);
            o.put("tsNanos", event.timestampRealtimeNanos);
            o.put("anchorRef", event.anchorRef == null ? JSONObject.NULL : event.anchorRef);
            return o.toString();
        } catch (Exception e) {
            // 兜底：至少留下 seq + eventId，绝不静默丢事件
            return "{\"seq\":" + event.seq + ",\"eventId\":\"" + event.eventId + "\"}";
        }
    }
    
    /**
     * 启动时从磁盘恢复链（WBS 1.6）。
     *
     * 恢复的不只是"事件列表"，还包括**连续性证据**：seq 必须连续、
     * prevHash 必须与上一条 eventId 相接，否则记录断链处。
     * 这样"重启"不再是抹掉历史的手段。
     */
    private void restoreChainFromDisk() {
        if (!mChainFile.exists()) {
            Slog.i(TAG, "No persisted agent audit chain; starting from genesis");
            return;
        }
        long restored = 0;
        long broken = 0;
        String expectedPrev = mLastAnchor.anchorHash;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(mChainFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                JSONObject o = new JSONObject(line);
                AuditEvent e = new AuditEvent();
                e.seq = o.optLong("seq");
                e.eventId = o.isNull("eventId") ? null : o.optString("eventId");
                e.prevHash = o.isNull("prevHash") ? null : o.optString("prevHash");
                e.payloadHash = o.isNull("payloadHash") ? null : o.optString("payloadHash");
                e.category = o.optInt("category");
                e.agentId = o.isNull("agentId") ? null : o.optString("agentId");
                e.intentId = o.isNull("intentId") ? null : o.optString("intentId");
                e.summary = o.isNull("summary") ? null : o.optString("summary");
                e.timestampRealtimeNanos = o.optLong("tsNanos");
                e.anchorRef = o.isNull("anchorRef") ? null : o.optString("anchorRef");
                
                if (e.seq != restored + 1
                        || (expectedPrev != null && !expectedPrev.equals(e.prevHash))) {
                    broken++;
                    Slog.e(TAG, String.format("Chain discontinuity at seq=%d (expected seq=%d)",
                            e.seq, restored + 1));
                }
                mEventChain.add(e);
                expectedPrev = e.eventId;
                restored = e.seq;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to restore agent audit chain", e);
        }
        
        mSeqGenerator.set(restored);
        mTotalEvents = restored;
        if (!mEventChain.isEmpty()) {
            mLastAnchor.anchorHash = mEventChain.get(mEventChain.size() - 1).eventId;
            mLastAnchor.seq = restored;
            mLastAnchor.anchorRef = "restored";
        }
        Slog.i(TAG, String.format("Agent audit chain restored: events=%d lastSeq=%d broken=%d",
                restored, restored, broken));
    }
    
    /**
     * 执行 TEE 锚定
     */
    private void performTeeAnchoring(AuditEvent event) {
        try {
            // 使用 TeeAnchorManager 创建锚点
            String anchorRef = mTeeAnchorManager.createAnchor(event.seq, event.eventId);
            
            // 更新最近锚点
            mLastAnchor = new ChainAnchor();
            mLastAnchor.seq = event.seq;
            mLastAnchor.anchorRef = anchorRef;
            mLastAnchor.anchoredAtRealtimeNanos = System.nanoTime();
            mLastAnchor.anchorHash = event.eventId;
            
            Slog.i(TAG, String.format("TEE anchor created: seq=%d, ref=%s", event.seq, anchorRef));
            
        } catch (Exception e) {
            Slog.e(TAG, "TEE anchoring failed", e);
        }
    }
    
    /**
     * 按角色脱敏事件
     */
    private AuditEvent sanitizeEvent(AuditEvent event, int viewerRole) {
        AuditEvent sanitized = new AuditEvent();
        sanitized.seq = event.seq;
        sanitized.eventId = event.eventId;
        sanitized.timestampRealtimeNanos = event.timestampRealtimeNanos;
        sanitized.category = event.category;
        sanitized.summary = event.summary;
        sanitized.prevHash = event.prevHash;
        sanitized.anchorRef = event.anchorRef;
        
        if (viewerRole == VIEWER_ADMIN_BRAIN) {
            // 管理员/大脑可见详情
            sanitized.agentId = event.agentId;
            sanitized.intentId = event.intentId;
            sanitized.payloadHash = event.payloadHash;
            sanitized.evidenceRefs = event.evidenceRefs;
        } else {
            // 用户仅见脱敏摘要
            sanitized.agentId = null;
            sanitized.intentId = null;
            sanitized.payloadHash = null;
            sanitized.evidenceRefs = null;
        }
        
        return sanitized;
    }
    
    /**
     * 判断是否需要活性指示
     */
    private boolean shouldIndicateActivity(int category) {
        // 仅对用户可感知的操作发送活性指示
        return category == CAT_PERCEPTION_READ || 
               category == CAT_ACTION_EXECUTE ||
               category == CAT_CONFIRMATION;
    }
    
    /**
     * 通知活性指示器
     */
    private void notifyActivityIndicator(AuditEvent event, boolean active) {
        int count = mActivityListeners.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    IAgentAuditListener listener = mActivityListeners.getBroadcastItem(i);
                    
                    ActivityIndicator indicator = new ActivityIndicator();
                    indicator.category = event.category;
                    indicator.agentId = event.agentId;
                    indicator.active = active;
                    indicator.timestampRealtimeNanos = event.timestampRealtimeNanos;
                    
                    listener.onActivity(indicator);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify activity listener", e);
                }
            }
        } finally {
            mActivityListeners.finishBroadcast();
        }
    }
    
    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("Events: %d, Verifies: %d success / %d failed, Chain length: %d",
            mTotalEvents, mVerifySuccessCount, mVerifyFailureCount, mEventChain.size());
    }
    
    // ========== 数据结构 ==========
    
    /**
     * 审计事件
     */
    public static class AuditEvent {
        public long seq;
        public String eventId;
        public long timestampRealtimeNanos;
        public int category;
        public String agentId;
        public String intentId;
        public String summary;
        public String payloadHash;
        public String[] evidenceRefs;
        public String prevHash;
        public String anchorRef;
    }
    
    /**
     * 查询条件
     */
    public static class AuditQuery {
        public int viewerRole = VIEWER_USER;
        public long seqFrom = 0;
        public long seqTo = 0;
        public String agentIdFilter;
        public int categoryFilter = 0;
        public String intentIdFilter;
        public int limit = 200;
    }
    
    /**
     * 链状态
     */
    public static class ChainStatus {
        public boolean verified;
        public long fromSeq;
        public long toSeq;
        public long brokenAtSeq;
        public String anchorRef;
        public long anchoredAtRealtimeNanos;
    }
    
    /**
     * 活性指示器
     */
    public static class ActivityIndicator {
        public int category;
        public String agentId;
        public boolean active;
        public long timestampRealtimeNanos;
    }
    
    /**
     * 链锚点
     */
    private static class ChainAnchor {
        long seq;
        String anchorRef;
        long anchoredAtRealtimeNanos;
        String anchorHash;
    }
    
    /**
     * 审计监听器接口（模拟）
     */
    public interface IAgentAuditListener {
        void onActivity(ActivityIndicator indicator) throws RemoteException;
    }
}
