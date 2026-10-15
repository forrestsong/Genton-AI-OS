package com.android.server.ainative;

import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;

import com.ainative.audit.IAuditService;
import com.ainative.audit.IAuditListener;
import com.ainative.audit.AuditEvent;
import com.ainative.audit.AuditQuery;
import com.ainative.audit.ChainStatus;
import com.ainative.audit.ActivityIndicator;

import java.io.File;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 审计服务（Audit Service）
 * 
 * 职责：
 * 1. 防篡改审计链写入（哈希链 + TEE 周期锚定）
 * 2. 按 viewerRole 脱敏查询
 * 3. 链完整性校验
 * 4. 活性流订阅
 * 
 * 架构特点：
 * - 不可变性：一旦写入，永不修改
 * - 哈希链：每条事件包含 prevHash
 * - TEE 锚定：周期性锚点写入安全存储
 * - 脱敏查询：USER 角色仅见摘要
 * 
 * 安全约束：
 * - 写权限：signature|privileged
 * - 读权限：按 viewerRole 脱敏
 * - Fail-secure：异常默认 DENY
 */
public class AuditService extends IAuditService.Stub {
    
    /**
     * 单点鉴权（WBS 1.3）：审计链是证据基础，record/query/verifyChain 此前
     * 无任何调用方校验，任意进程可注入伪造审计记录。
     */
    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws android.os.RemoteException {
        AgentCallerAuth.enforceTransaction(mContext, AgentCallerAuth.PERM_BIND_AUDIT,
                "AuditService", code);
        return super.onTransact(code, data, reply, flags);
    }
    private static final String TAG = "AuditService";
    
    // ========== 配置常量 ==========
    
    private static final String AUDIT_LOG_DIR = "/data/system/ainative/audit";
    private static final String AUDIT_LOG_FILE = "audit_chain.log";
    private static final String ANCHOR_FILE = "chain_anchor.dat";
    
    // TEE 锚定周期（每 N 条事件）
    private static final int ANCHOR_INTERVAL = 100;
    
    // 查询限制
    private static final int MAX_QUERY_LIMIT = 1000;
    
    // 初始 prevHash（创世区块）
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    
    // ========== 核心状态 ==========
    
    private final Context mContext;
    private final AtomicLong mNextSeq;
    private final ReadWriteLock mChainLock;
    private final RemoteCallbackList<IAuditListener> mActivityListeners;
    
    // 最近事件的哈希（用于计算 prevHash）
    private String mLastEventHash;
    
    // 最近锚点信息
    private String mLastAnchorRef;
    private long mLastAnchoredSeq;
    private long mLastAnchoredTime;
    
    // 统计
    private final AtomicLong mTotalEvents;
    private final AtomicLong mFailedWrites;
    
    // ========== 构造函数 ==========
    
    public AuditService(Context context) {
        mContext = context;
        mNextSeq = new AtomicLong(1);
        mChainLock = new ReentrantReadWriteLock();
        mActivityListeners = new RemoteCallbackList<>();
        
        mLastEventHash = GENESIS_HASH;
        mLastAnchorRef = "";
        mLastAnchoredSeq = 0;
        mLastAnchoredTime = 0;
        
        mTotalEvents = new AtomicLong(0);
        mFailedWrites = new AtomicLong(0);
        
        // 初始化存储
        initializeStorage();
        
        // 加载最近状态
        loadLastState();
        
        Slog.i(TAG, "AuditService initialized, nextSeq=" + mNextSeq.get());
    }
    
    // ========== IAuditService 接口实现 ==========
    
    @Override
    public int record(AuditEvent event, String[] outEventId) {
        mChainLock.writeLock().lock();
        try {
            // 1. 校验权限（TODO: 检查调用者权限）
            // if (!checkWritePermission()) {
            //     return STATUS_DENIED;
            // }
            
            // 2. 校验 category 有效性
            if (!isValidCategory(event.category)) {
                Slog.w(TAG, "Invalid category: " + event.category);
                return STATUS_INVALID_EVENT;
            }
            
            // 3. 填充服务端字段
            event.seq = mNextSeq.getAndIncrement();
            event.timestampRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            event.prevHash = mLastEventHash;
            
            // 4. 计算 eventId（内容哈希）
            event.eventId = computeEventHash(event);
            
            // 5. 周期性 TEE 锚定
            if (shouldAnchor(event.seq)) {
                anchorChain(event.seq);
                event.anchorRef = mLastAnchorRef;
            } else {
                event.anchorRef = mLastAnchorRef;  // 使用最近锚点
            }
            
            // 6. 持久化
            if (!persistEvent(event)) {
                mFailedWrites.incrementAndGet();
                return STATUS_INTERNAL_ERROR;
            }
            
            // 7. 更新链状态
            mLastEventHash = event.eventId;
            mTotalEvents.incrementAndGet();
            
            // 8. 输出 eventId
            outEventId[0] = event.eventId;
            
            // 9. 通知活性监听器
            notifyActivityListeners(event);
            
            Slog.d(TAG, String.format("Recorded event: seq=%d category=%d agentId=%s",
                event.seq, event.category, event.agentId));
            
            return STATUS_OK;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to record event", e);
            mFailedWrites.incrementAndGet();
            return STATUS_INTERNAL_ERROR;
        } finally {
            mChainLock.writeLock().unlock();
        }
    }
    
    @Override
    public AuditEvent[] query(AuditQuery query) {
        mChainLock.readLock().lock();
        try {
            // 1. 校验权限（TODO: 检查调用者权限）
            // if (!checkReadPermission()) {
            //     return new AuditEvent[0];
            // }
            
            // 2. 限制查询数量
            int limit = Math.min(query.limit, MAX_QUERY_LIMIT);
            
            // 3. 读取事件
            List<AuditEvent> events = loadEvents(query, limit);
            
            // 4. 按 viewerRole 脱敏
            List<AuditEvent> sanitized = new ArrayList<>();
            for (AuditEvent event : events) {
                sanitized.add(sanitizeEvent(event, query.viewerRole));
            }
            
            Slog.d(TAG, String.format("Query returned %d events (viewerRole=%d)",
                sanitized.size(), query.viewerRole));
            
            return sanitized.toArray(new AuditEvent[0]);
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to query events", e);
            return new AuditEvent[0];
        } finally {
            mChainLock.readLock().unlock();
        }
    }
    
    @Override
    public ChainStatus verifyChain(long fromSeq, long toSeq) {
        mChainLock.readLock().lock();
        try {
            ChainStatus status = new ChainStatus();
            status.fromSeq = fromSeq;
            status.toSeq = toSeq;
            status.verified = true;
            status.brokenAtSeq = 0;
            status.anchorRef = mLastAnchorRef;
            status.anchoredAtRealtimeNanos = mLastAnchoredTime;
            
            // 读取区间内的所有事件
            List<AuditEvent> events = loadEventRange(fromSeq, toSeq);
            
            if (events.isEmpty()) {
                Slog.w(TAG, "No events in range, cannot verify");
                status.verified = false;
                return status;
            }
            
            // 校验链完整性
            String expectedPrevHash = (fromSeq == 1) ? GENESIS_HASH : null;
            
            for (int i = 0; i < events.size(); i++) {
                AuditEvent event = events.get(i);
                
                // 校验 seq 连续性
                if (i > 0 && event.seq != events.get(i - 1).seq + 1) {
                    Slog.w(TAG, "Seq gap detected at " + event.seq);
                    status.verified = false;
                    status.brokenAtSeq = event.seq;
                    return status;
                }
                
                // 校验 prevHash
                if (expectedPrevHash != null && !event.prevHash.equals(expectedPrevHash)) {
                    Slog.w(TAG, "PrevHash mismatch at seq=" + event.seq);
                    status.verified = false;
                    status.brokenAtSeq = event.seq;
                    return status;
                }
                
                // 校验 eventId
                String computedHash = computeEventHash(event);
                if (!event.eventId.equals(computedHash)) {
                    Slog.w(TAG, "EventId mismatch at seq=" + event.seq);
                    status.verified = false;
                    status.brokenAtSeq = event.seq;
                    return status;
                }
                
                expectedPrevHash = event.eventId;
            }
            
            Slog.i(TAG, String.format("Chain verified: %d-%d, %d events",
                fromSeq, toSeq, events.size()));
            
            return status;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to verify chain", e);
            ChainStatus status = new ChainStatus();
            status.verified = false;
            return status;
        } finally {
            mChainLock.readLock().unlock();
        }
    }
    
    @Override
    public ChainStatus getChainAnchor() {
        ChainStatus status = new ChainStatus();
        status.verified = true;
        status.fromSeq = 0;
        status.toSeq = mLastAnchoredSeq;
        status.brokenAtSeq = 0;
        status.anchorRef = mLastAnchorRef;
        status.anchoredAtRealtimeNanos = mLastAnchoredTime;
        
        return status;
    }
    
    @Override
    public int registerActivityListener(IAuditListener listener) {
        if (listener == null) {
            return STATUS_INVALID_EVENT;
        }
        
        mActivityListeners.register(listener);
        Slog.d(TAG, "Activity listener registered");
        
        return STATUS_OK;
    }
    
    @Override
    public int unregisterActivityListener(IAuditListener listener) {
        if (listener == null) {
            return STATUS_INVALID_EVENT;
        }
        
        mActivityListeners.unregister(listener);
        Slog.d(TAG, "Activity listener unregistered");
        
        return STATUS_OK;
    }
    
    // ========== 内部辅助方法 ==========
    
    /**
     * 初始化存储
     */
    private void initializeStorage() {
        File auditDir = new File(AUDIT_LOG_DIR);
        if (!auditDir.exists()) {
            if (!auditDir.mkdirs()) {
                Slog.e(TAG, "Failed to create audit log directory");
            }
        }
    }
    
    /**
     * 加载最近状态
     */
    private void loadLastState() {
        File logFile = new File(AUDIT_LOG_DIR, AUDIT_LOG_FILE);
        if (!logFile.exists()) {
            Slog.i(TAG, "No existing audit log, starting fresh");
            return;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            AuditEvent lastEvent = null;
            long lineCount = 0;
            
            while ((line = reader.readLine()) != null) {
                lineCount++;
                try {
                    lastEvent = parseEventLine(line);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to parse line " + lineCount, e);
                }
            }
            
            if (lastEvent != null) {
                mNextSeq.set(lastEvent.seq + 1);
                mLastEventHash = lastEvent.eventId;
                mTotalEvents.set(lastEvent.seq);
                
                Slog.i(TAG, String.format("Loaded last state: seq=%d hash=%s",
                    lastEvent.seq, mLastEventHash.substring(0, 8)));
            }
            
        } catch (IOException e) {
            Slog.e(TAG, "Failed to load last state", e);
        }
        
        // 加载锚点信息
        File anchorFile = new File(AUDIT_LOG_DIR, ANCHOR_FILE);
        if (anchorFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(anchorFile))) {
                String line = reader.readLine();
                if (line != null) {
                    String[] parts = line.split(",");
                    if (parts.length >= 3) {
                        mLastAnchorRef = parts[0];
                        mLastAnchoredSeq = Long.parseLong(parts[1]);
                        mLastAnchoredTime = Long.parseLong(parts[2]);
                        
                        Slog.i(TAG, String.format("Loaded anchor: ref=%s seq=%d",
                            mLastAnchorRef, mLastAnchoredSeq));
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to load anchor", e);
            }
        }
    }
    
    /**
     * 校验 category 有效性
     */
    private boolean isValidCategory(int category) {
        return category >= CAT_PERCEPTION_READ && category <= CAT_SYSTEM;
    }
    
    /**
     * 计算事件哈希
     */
    private String computeEventHash(AuditEvent event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            // 哈希输入：seq + timestamp + category + agentId + summary + payloadHash
            String input = event.seq + "|" +
                          event.timestampRealtimeNanos + "|" +
                          event.category + "|" +
                          event.agentId + "|" +
                          event.summary + "|" +
                          (event.payloadHash != null ? event.payloadHash : "");
            
            byte[] hashBytes = digest.digest(input.getBytes());
            
            // 转换为十六进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString();
            
        } catch (NoSuchAlgorithmException e) {
            Slog.e(TAG, "SHA-256 not available", e);
            return "error";
        }
    }
    
    /**
     * 判断是否需要锚定
     */
    private boolean shouldAnchor(long seq) {
        return seq % ANCHOR_INTERVAL == 0;
    }
    
    /**
     * TEE 锚定（存根实现）
     */
    private void anchorChain(long seq) {
        // TODO: 真实 TEE 锚定实现
        // 1. 计算区间的默克尔根
        // 2. 调用 TEE 签名
        // 3. 写入安全存储
        
        // 临时：生成锚点引用
        long timestamp = SystemClock.elapsedRealtimeNanos();
        mLastAnchorRef = String.format("anchor_%d_%d", seq, timestamp);
        mLastAnchoredSeq = seq;
        mLastAnchoredTime = timestamp;
        
        // 持久化锚点信息
        File anchorFile = new File(AUDIT_LOG_DIR, ANCHOR_FILE);
        try (FileWriter writer = new FileWriter(anchorFile, false)) {
            writer.write(String.format("%s,%d,%d\n",
                mLastAnchorRef, mLastAnchoredSeq, mLastAnchoredTime));
        } catch (IOException e) {
            Slog.e(TAG, "Failed to persist anchor", e);
        }
        
        Slog.i(TAG, String.format("Chain anchored at seq=%d ref=%s", seq, mLastAnchorRef));
    }
    
    /**
     * 持久化事件
     */
    private boolean persistEvent(AuditEvent event) {
        File logFile = new File(AUDIT_LOG_DIR, AUDIT_LOG_FILE);
        
        try (FileWriter writer = new FileWriter(logFile, true)) {
            // 格式：seq|eventId|timestamp|category|agentId|intentId|summary|payloadHash|evidenceRefs|prevHash|anchorRef
            StringBuilder line = new StringBuilder();
            line.append(event.seq).append("|");
            line.append(event.eventId).append("|");
            line.append(event.timestampRealtimeNanos).append("|");
            line.append(event.category).append("|");
            line.append(event.agentId != null ? event.agentId : "").append("|");
            line.append(event.intentId != null ? event.intentId : "").append("|");
            line.append(event.summary != null ? event.summary.replace("|", "\\|") : "").append("|");
            line.append(event.payloadHash != null ? event.payloadHash : "").append("|");
            
            // evidenceRefs（逗号分隔）
            if (event.evidenceRefs != null && event.evidenceRefs.length > 0) {
                line.append(String.join(",", event.evidenceRefs));
            }
            line.append("|");
            
            line.append(event.prevHash).append("|");
            line.append(event.anchorRef != null ? event.anchorRef : "");
            line.append("\n");
            
            writer.write(line.toString());
            return true;
            
        } catch (IOException e) {
            Slog.e(TAG, "Failed to persist event", e);
            return false;
        }
    }
    
    /**
     * 解析事件行
     */
    private AuditEvent parseEventLine(String line) {
        String[] parts = line.split("\\|", -1);  // -1 保留空字段
        
        if (parts.length < 11) {
            throw new IllegalArgumentException("Invalid event line");
        }
        
        AuditEvent event = new AuditEvent();
        event.seq = Long.parseLong(parts[0]);
        event.eventId = parts[1];
        event.timestampRealtimeNanos = Long.parseLong(parts[2]);
        event.category = Integer.parseInt(parts[3]);
        event.agentId = parts[4].isEmpty() ? null : parts[4];
        event.intentId = parts[5].isEmpty() ? null : parts[5];
        event.summary = parts[6].isEmpty() ? null : parts[6].replace("\\|", "|");
        event.payloadHash = parts[7].isEmpty() ? null : parts[7];
        
        // evidenceRefs
        if (!parts[8].isEmpty()) {
            event.evidenceRefs = parts[8].split(",");
        } else {
            event.evidenceRefs = new String[0];
        }
        
        event.prevHash = parts[9];
        event.anchorRef = parts[10].isEmpty() ? null : parts[10];
        
        return event;
    }
    
    /**
     * 加载事件（带过滤）
     */
    private List<AuditEvent> loadEvents(AuditQuery query, int limit) {
        List<AuditEvent> result = new ArrayList<>();
        File logFile = new File(AUDIT_LOG_DIR, AUDIT_LOG_FILE);
        
        if (!logFile.exists()) {
            return result;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            
            while ((line = reader.readLine()) != null && result.size() < limit) {
                try {
                    AuditEvent event = parseEventLine(line);
                    
                    // 应用过滤条件
                    if (!matchesQuery(event, query)) {
                        continue;
                    }
                    
                    result.add(event);
                    
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to parse event line", e);
                }
            }
            
        } catch (IOException e) {
            Slog.e(TAG, "Failed to load events", e);
        }
        
        return result;
    }
    
    /**
     * 加载事件范围
     */
    private List<AuditEvent> loadEventRange(long fromSeq, long toSeq) {
        List<AuditEvent> result = new ArrayList<>();
        File logFile = new File(AUDIT_LOG_DIR, AUDIT_LOG_FILE);
        
        if (!logFile.exists()) {
            return result;
        }
        
        long actualToSeq = (toSeq == 0) ? Long.MAX_VALUE : toSeq;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            
            while ((line = reader.readLine()) != null) {
                try {
                    AuditEvent event = parseEventLine(line);
                    
                    if (event.seq >= fromSeq && event.seq <= actualToSeq) {
                        result.add(event);
                    }
                    
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to parse event line", e);
                }
            }
            
        } catch (IOException e) {
            Slog.e(TAG, "Failed to load event range", e);
        }
        
        return result;
    }
    
    /**
     * 检查事件是否匹配查询条件
     */
    private boolean matchesQuery(AuditEvent event, AuditQuery query) {
        // seq 范围
        if (query.seqFrom > 0 && event.seq < query.seqFrom) {
            return false;
        }
        if (query.seqTo > 0 && event.seq > query.seqTo) {
            return false;
        }
        
        // agentId 过滤
        if (query.agentIdFilter != null && !query.agentIdFilter.isEmpty()) {
            if (event.agentId == null || !event.agentId.equals(query.agentIdFilter)) {
                return false;
            }
        }
        
        // category 过滤
        if (query.categoryFilter > 0 && event.category != query.categoryFilter) {
            return false;
        }
        
        // intentId 过滤
        if (query.intentIdFilter != null && !query.intentIdFilter.isEmpty()) {
            if (event.intentId == null || !event.intentId.equals(query.intentIdFilter)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 按 viewerRole 脱敏事件
     */
    private AuditEvent sanitizeEvent(AuditEvent event, int viewerRole) {
        if (viewerRole == 1) {  // ADMIN_BRAIN：完整信息
            return event;
        }
        
        // USER：脱敏视图
        AuditEvent sanitized = new AuditEvent();
        sanitized.seq = event.seq;
        sanitized.eventId = event.eventId;
        sanitized.timestampRealtimeNanos = event.timestampRealtimeNanos;
        sanitized.category = event.category;
        sanitized.summary = event.summary;
        
        // 脱敏字段（不返回）
        sanitized.agentId = "";
        sanitized.intentId = "";
        sanitized.payloadHash = "";
        sanitized.evidenceRefs = new String[0];
        sanitized.prevHash = "";
        sanitized.anchorRef = "";
        
        return sanitized;
    }
    
    /**
     * 通知活性监听器
     */
    private void notifyActivityListeners(AuditEvent event) {
        // 只有特定分类触发活性指示器
        if (event.category != CAT_PERCEPTION_READ &&
            event.category != CAT_ACTION_EXECUTION &&
            event.category != CAT_SYSTEM) {
            return;
        }
        
        ActivityIndicator indicator = new ActivityIndicator();
        indicator.category = event.category;
        indicator.agentId = event.agentId != null ? event.agentId : "";
        indicator.active = true;  // 事件写入表示活动开始（结束事件需另外记录）
        indicator.timestampRealtimeNanos = event.timestampRealtimeNanos;
        
        // 广播到所有监听器
        int count = mActivityListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mActivityListeners.getBroadcastItem(i).onActivity(indicator);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify activity listener", e);
            }
        }
        mActivityListeners.finishBroadcast();
    }
    
    // ========== 统计与调试 ==========
    
    /**
     * 获取统计信息
     */
    public String getStatistics() {
        return String.format("AuditService Stats: total=%d failed=%d nextSeq=%d lastHash=%s",
            mTotalEvents.get(),
            mFailedWrites.get(),
            mNextSeq.get(),
            mLastEventHash.substring(0, 8));
    }
}
