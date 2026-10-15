package com.android.server.genton;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;

import com.genton.events.EventSubscription;
import com.genton.events.IAgentEventSink;
import com.genton.events.IAgentEventStreamService;
import com.genton.events.SubscriptionHandle;
import com.genton.events.SubscriptionInfo;
import com.genton.events.SystemEvent;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AgentEventStreamService - 统一事件总线
 * 
 * 为 AI Native OS 提供主动感知能力，聚合 9 组事件源（通知/前台/屏幕/通话/日程/系统/位置/Agent/语义）。
 * 
 * 核心功能：
 * 1. 事件订阅管理（subscribe/updateSubscription/unsubscribe/setSubscriptionPaused）
 * 2. 令牌桶限流（maxEventsPerMinute + maxBurst）
 * 3. 队列背压控制（maxQueue，超出→丢弃+onEventsDropped）
 * 4. 优先级门控（PRIORITY_LOW/NORMAL/HIGH）
 * 5. 敏感事件授权（S2 位置类需 capability + 用户开关）
 * 6. TTL 治理（临时订阅自动过期）
 * 7. 审计日志（订阅/事件投递计数）
 * 
 * QoS 保证：
 * - 有序投递（每订阅内 seq 单调递增）
 * - 显式丢弃上报（限流/队列满/TTL 过期必须告知）
 * - 无通配符订阅（防止敏感信息广播扩散）
 * 
 * @author AI Native OS Platform Team
 * @version 1.0
 */
public class AgentEventStreamService extends SystemService {

    /** 按事件类型订阅（供同进程服务调用） */
    public void subscribeByType(String eventType, com.genton.events.IEventStreamListener listener) {
        if (mBinder instanceof com.genton.events.IAgentEventStreamService) {
            try {
                ((com.genton.events.IAgentEventStreamService) mBinder).subscribeByType(eventType, listener);
            } catch (android.os.RemoteException e) {
                android.util.Slog.w("AgentEventStream", "subscribeByType failed", e);
            }
        }
    }
    private static final String TAG = "AgentEventStreamService";
    private static final boolean DEBUG = false;

    // 权限
    private static final String SUBSCRIBE_EVENTS = "com.genton.permission.SUBSCRIBE_EVENTS";

    // 事件类型白名单（9 组）
    private static final Set<String> EVENT_TYPE_WHITELIST = new HashSet<>(Arrays.asList(
        // 通知组
        "notification.posted", "notification.removed", "notification.replied",
        // 前台组
        "foreground.changed", "app.installed", "app.removed",
        // 屏幕组
        "screen.on", "screen.off", "device.locked", "device.unlocked",
        // 通话组
        "telephony.call_state",
        // 日程组
        "schedule.near", "schedule.started",
        // 系统组
        "power.low", "power.state_changed", "network.changed", 
        "audio.route_changed", "storage.low",
        // 位置组（S2 敏感）
        "location.coarse_significant",
        // Agent 组
        "agent.goal_progress", "agent.state_changed", "agent.budget_warning",
        // 语义组
        "semantic.entity_updated", "semantic.memory_page_invalidated"
    ));

    // S2 敏感事件（需额外授权）
    private static final Set<String> S2_SENSITIVE_EVENTS = new HashSet<>(Arrays.asList(
        "location.coarse_significant"
    ));

    // 每 Agent 订阅配额上限
    private static final int MAX_SUBSCRIPTIONS_PER_AGENT = 10;

    private final Context mContext;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    // 订阅管理（handleId -> Subscription）
    private final Map<Long, Subscription> mSubscriptions = new ConcurrentHashMap<>();
    private final AtomicLong mHandleIdGenerator = new AtomicLong(1);
    private final AtomicLong mEventIdGenerator = new AtomicLong(1);

    // Agent 订阅计数（agentId -> count）
    private final Map<String, Integer> mAgentSubscriptionCounts = new ConcurrentHashMap<>();

    public AgentEventStreamService(Context context) {
        super(context);
        mContext = context;
    }

    /** [P5] 暴露 binder，供 GentonServiceRegistry 以规范服务名注册 */
    public android.os.IBinder getBinder() {
        return mBinder;
    }

    @Override
    public void onStart() {
        publishBinderService("genton_event_stream", mBinder);

        mHandlerThread = new HandlerThread("AgentEventStreamService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        // 注册系统事件监听器
        registerSystemEventListeners();

        Slog.i(TAG, "AgentEventStreamService started");
    }

    private final IAgentEventStreamService.Stub mBinder = new IAgentEventStreamService.Stub() {
        @Override
        public void subscribeByType(String eventType, com.genton.events.IEventStreamListener listener) {
            // 按类型订阅（当前实现不推送，占位满足契约）
        }


        @Override
        public int subscribe(EventSubscription subscription, IAgentEventSink sink,
                           SubscriptionHandle handle) {
            // 权限检查
            if (!checkSubscribePermission()) {
                return IAgentEventStreamService.STATUS_DENIED;
            }

            // 参数验证
            if (subscription == null || sink == null || subscription.eventTypes == null
                    || subscription.eventTypes.length == 0) {
                return IAgentEventStreamService.STATUS_INVALID_SUB;
            }

            // 事件类型白名单检查
            for (String eventType : subscription.eventTypes) {
                if (!EVENT_TYPE_WHITELIST.contains(eventType)) {
                    Slog.w(TAG, "Invalid event type: " + eventType);
                    return IAgentEventStreamService.STATUS_INVALID_SUB;
                }
            }

            // S2 敏感事件授权检查
            for (String eventType : subscription.eventTypes) {
                if (S2_SENSITIVE_EVENTS.contains(eventType)) {
                    if (subscription.highSensitivityGrant != 1) {
                        Slog.w(TAG, "S2 event requires highSensitivityGrant: " + eventType);
                        return IAgentEventStreamService.STATUS_DENIED;
                    }
                    // TODO: 检查用户全局开关
                }
            }

            // 获取调用方 Agent ID
            int callingUid = Binder.getCallingUid();
            String agentId = getAgentIdFromUid(callingUid);

            // 检查订阅配额
            int currentCount = mAgentSubscriptionCounts.getOrDefault(agentId, 0);
            if (currentCount >= MAX_SUBSCRIPTIONS_PER_AGENT) {
                Slog.w(TAG, "Subscription quota exceeded for agent: " + agentId);
                return IAgentEventStreamService.STATUS_OVERLOADED;
            }

            // 创建订阅
            long handleId = mHandleIdGenerator.getAndIncrement();
            Subscription sub = new Subscription(handleId, agentId, subscription, sink);

            mSubscriptions.put(handleId, sub);
            mAgentSubscriptionCounts.put(agentId, currentCount + 1);

            // 填充 handle
            if (handle != null) {
                handle.handleId = handleId;
                handle.ownerAgentId = agentId;
            }

            if (DEBUG) {
                Slog.d(TAG, "Subscribed: handleId=" + handleId + ", agent=" + agentId
                    + ", types=" + Arrays.toString(subscription.eventTypes));
            }

            // 审计日志
            logAuditEvent("subscribe", "handleId=" + handleId + ",agent=" + agentId
                + ",types=" + subscription.eventTypes.length);

            // TTL 处理
            if (subscription.ttlRealtimeNanos > 0) {
                long ttlMs = subscription.ttlRealtimeNanos / 1_000_000;
                mHandler.postDelayed(() -> expireSubscription(handleId), ttlMs);
            }

            return IAgentEventStreamService.STATUS_OK;
        }

        @Override
        public int updateSubscription(SubscriptionHandle handle, EventSubscription newSubscription) {
            if (!checkSubscribePermission()) {
                return IAgentEventStreamService.STATUS_DENIED;
            }

            if (handle == null || newSubscription == null) {
                return IAgentEventStreamService.STATUS_INVALID_SUB;
            }

            Subscription sub = mSubscriptions.get(handle.handleId);
            if (sub == null) {
                return IAgentEventStreamService.STATUS_HANDLE_NOT_FOUND;
            }

            // 验证所有权
            if (!sub.agentId.equals(handle.ownerAgentId)) {
                Slog.w(TAG, "Ownership mismatch for handle: " + handle.handleId);
                return IAgentEventStreamService.STATUS_DENIED;
            }

            // 更新订阅配置
            synchronized (sub) {
                sub.subscription = newSubscription;
                sub.paused = newSubscription.paused;
                sub.eventTypeSet = new HashSet<>(Arrays.asList(newSubscription.eventTypes));
                // 重置限流器
                sub.tokenBucket = new TokenBucket(newSubscription.maxEventsPerMinute,
                    newSubscription.maxBurst);
            }

            if (DEBUG) {
                Slog.d(TAG, "Updated subscription: " + handle.handleId);
            }

            logAuditEvent("update_subscription", "handleId=" + handle.handleId);

            return IAgentEventStreamService.STATUS_OK;
        }

        @Override
        public int unsubscribe(SubscriptionHandle handle) {
            if (!checkSubscribePermission()) {
                return IAgentEventStreamService.STATUS_DENIED;
            }

            if (handle == null) {
                return IAgentEventStreamService.STATUS_INVALID_SUB;
            }

            Subscription sub = mSubscriptions.remove(handle.handleId);
            if (sub == null) {
                return IAgentEventStreamService.STATUS_HANDLE_NOT_FOUND;
            }

            // 验证所有权
            if (!sub.agentId.equals(handle.ownerAgentId)) {
                Slog.w(TAG, "Ownership mismatch for handle: " + handle.handleId);
                mSubscriptions.put(handle.handleId, sub);  // 恢复
                return IAgentEventStreamService.STATUS_DENIED;
            }

            // 更新计数
            int count = mAgentSubscriptionCounts.getOrDefault(sub.agentId, 1);
            if (count > 1) {
                mAgentSubscriptionCounts.put(sub.agentId, count - 1);
            } else {
                mAgentSubscriptionCounts.remove(sub.agentId);
            }

            if (DEBUG) {
                Slog.d(TAG, "Unsubscribed: " + handle.handleId);
            }

            logAuditEvent("unsubscribe", "handleId=" + handle.handleId);

            return IAgentEventStreamService.STATUS_OK;
        }

        @Override
        public int setSubscriptionPaused(SubscriptionHandle handle, boolean paused) {
            if (!checkSubscribePermission()) {
                return IAgentEventStreamService.STATUS_DENIED;
            }

            if (handle == null) {
                return IAgentEventStreamService.STATUS_INVALID_SUB;
            }

            Subscription sub = mSubscriptions.get(handle.handleId);
            if (sub == null) {
                return IAgentEventStreamService.STATUS_HANDLE_NOT_FOUND;
            }

            // 验证所有权
            if (!sub.agentId.equals(handle.ownerAgentId)) {
                return IAgentEventStreamService.STATUS_DENIED;
            }

            synchronized (sub) {
                sub.paused = paused;
            }

            if (DEBUG) {
                Slog.d(TAG, "Set paused=" + paused + " for handle: " + handle.handleId);
            }

            logAuditEvent("set_paused", "handleId=" + handle.handleId + ",paused=" + paused);

            return IAgentEventStreamService.STATUS_OK;
        }

        @Override
        public SubscriptionInfo[] listSubscriptions() {
            if (!checkSubscribePermission()) {
                return new SubscriptionInfo[0];
            }

            int callingUid = Binder.getCallingUid();
            String agentId = getAgentIdFromUid(callingUid);

            List<SubscriptionInfo> results = new ArrayList<>();
            for (Subscription sub : mSubscriptions.values()) {
                // 仅返回调用方的订阅
                if (sub.agentId.equals(agentId)) {
                    SubscriptionInfo info = new SubscriptionInfo();
                    info.handleId = sub.handleId;
                    info.ownerAgentId = sub.agentId;
                    info.eventTypes = sub.subscription.eventTypes;
                    info.paused = sub.paused;
                    info.eventsDeliveredTotal = sub.eventsDeliveredCount;
                    info.eventsDroppedTotal = sub.eventsDroppedCount;
                    results.add(info);
                }
            }

            return results.toArray(new SubscriptionInfo[0]);
        }
    };

    /**
     * 订阅对象
     */
    private static class Subscription {
        final long handleId;
        final String agentId;
        EventSubscription subscription;
        final IAgentEventSink sink;
        Set<String> eventTypeSet;
        boolean paused;

        // 序列号生成器（保证有序投递）
        final AtomicLong seqGenerator = new AtomicLong(1);

        // 令牌桶限流器
        TokenBucket tokenBucket;

        // 事件队列
        final Queue<SystemEvent> eventQueue;

        // 统计信息
        long eventsDeliveredCount;
        long eventsDroppedCount;

        Subscription(long handleId, String agentId, EventSubscription subscription,
                    IAgentEventSink sink) {
            this.handleId = handleId;
            this.agentId = agentId;
            this.subscription = subscription;
            this.sink = sink;
            this.eventTypeSet = new HashSet<>(Arrays.asList(subscription.eventTypes));
            this.paused = subscription.paused;
            this.tokenBucket = new TokenBucket(subscription.maxEventsPerMinute,
                subscription.maxBurst);
            this.eventQueue = new LinkedBlockingQueue<>(subscription.maxQueue);
        }
    }

    /**
     * 令牌桶限流器
     */
    private static class TokenBucket {
        final double tokensPerMinute;
        final int maxBurst;
        double tokens;
        long lastRefillTime;

        TokenBucket(int tokensPerMinute, int maxBurst) {
            this.tokensPerMinute = tokensPerMinute;
            this.maxBurst = maxBurst;
            this.tokens = maxBurst;
            this.lastRefillTime = SystemClock.elapsedRealtime();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = SystemClock.elapsedRealtime();
            long elapsed = now - lastRefillTime;
            if (elapsed > 0) {
                double newTokens = (elapsed / 60000.0) * tokensPerMinute;
                tokens = Math.min(tokens + newTokens, maxBurst);
                lastRefillTime = now;
            }
        }
    }

    /**
     * 分发事件到所有订阅者
     */
    public void dispatchEvent(String eventType, int priority, int sensitivity,
                             String sourcePackage, Bundle payload, boolean fromUserTurn) {
        if (!EVENT_TYPE_WHITELIST.contains(eventType)) {
            Slog.w(TAG, "Attempted to dispatch unlisted event type: " + eventType);
            return;
        }

        long eventId = mEventIdGenerator.getAndIncrement();
        long timestamp = SystemClock.elapsedRealtimeNanos();

        for (Subscription sub : mSubscriptions.values()) {
            // 订阅类型匹配
            if (!sub.eventTypeSet.contains(eventType)) {
                continue;
            }

            // 暂停状态
            if (sub.paused) {
                continue;
            }

            // 优先级过滤
            if (priority < sub.subscription.minPriority) {
                continue;
            }

            // 包名过滤（仅通知/前台类）
            if (sub.subscription.packageFilter != null && sub.subscription.packageFilter.length > 0) {
                if (sourcePackage == null || !Arrays.asList(sub.subscription.packageFilter)
                        .contains(sourcePackage)) {
                    continue;
                }
            }

            // 令牌桶限流
            if (!sub.tokenBucket.tryConsume()) {
                sub.eventsDroppedCount++;
                // 异步通知丢弃
                mHandler.post(() -> notifyEventsDropped(sub, 1, "rate_limited"));
                continue;
            }

            // 构建事件
            SystemEvent event = new SystemEvent();
            event.eventId = eventId;
            event.seq = sub.seqGenerator.getAndIncrement();
            event.type = eventType;
            event.priority = priority;
            event.sensitivity = sensitivity;
            event.timestampRealtimeNanos = timestamp;
            event.sourcePackage = sourcePackage;
            event.payload = payload;
            event.trust = 0;  // TODO: TrustLevel
            event.fromUserTurn = fromUserTurn;

            // 队列满检查
            if (!sub.eventQueue.offer(event)) {
                sub.eventsDroppedCount++;
                mHandler.post(() -> notifyEventsDropped(sub, 1, "queue_full"));
                continue;
            }

            // 异步投递
            mHandler.post(() -> deliverEvent(sub));
        }
    }

    /**
     * 投递事件给订阅者
     */
    private void deliverEvent(Subscription sub) {
        SystemEvent event = sub.eventQueue.poll();
        if (event == null) {
            return;
        }

        try {
            SubscriptionHandle handle = new SubscriptionHandle();
            handle.handleId = sub.handleId;
            handle.ownerAgentId = sub.agentId;

            sub.sink.onEvent(handle, event);
            sub.eventsDeliveredCount++;

            if (DEBUG) {
                Slog.d(TAG, "Delivered event: " + event.type + " to handle " + sub.handleId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver event to handle " + sub.handleId, e);
            // 订阅者死亡，自动清理
            mHandler.post(() -> cleanupDeadSubscription(sub.handleId));
        }
    }

    /**
     * 通知事件丢弃
     */
    private void notifyEventsDropped(Subscription sub, long count, String reason) {
        try {
            SubscriptionHandle handle = new SubscriptionHandle();
            handle.handleId = sub.handleId;
            handle.ownerAgentId = sub.agentId;

            sub.sink.onEventsDropped(handle, count, reason);

            if (DEBUG) {
                Slog.d(TAG, "Notified dropped: " + count + " events (" + reason
                    + ") to handle " + sub.handleId);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to notify dropped events to handle " + sub.handleId, e);
        }
    }

    /**
     * 订阅过期处理
     */
    private void expireSubscription(long handleId) {
        Subscription sub = mSubscriptions.remove(handleId);
        if (sub != null) {
            // 更新计数
            int count = mAgentSubscriptionCounts.getOrDefault(sub.agentId, 1);
            if (count > 1) {
                mAgentSubscriptionCounts.put(sub.agentId, count - 1);
            } else {
                mAgentSubscriptionCounts.remove(sub.agentId);
            }

            // 通知丢弃（TTL 过期）
            notifyEventsDropped(sub, 0, "ttl_expired");

            if (DEBUG) {
                Slog.d(TAG, "Subscription expired: " + handleId);
            }

            logAuditEvent("expire_subscription", "handleId=" + handleId);
        }
    }

    /**
     * 清理死亡订阅
     */
    private void cleanupDeadSubscription(long handleId) {
        Subscription sub = mSubscriptions.remove(handleId);
        if (sub != null) {
            int count = mAgentSubscriptionCounts.getOrDefault(sub.agentId, 1);
            if (count > 1) {
                mAgentSubscriptionCounts.put(sub.agentId, count - 1);
            } else {
                mAgentSubscriptionCounts.remove(sub.agentId);
            }

            Slog.i(TAG, "Cleaned up dead subscription: " + handleId);
        }
    }

    /**
     * 注册系统事件监听器
     */
    private void registerSystemEventListeners() {
        // 屏幕开关事件
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    dispatchEvent("screen.on", IAgentEventStreamService.PRIORITY_NORMAL,
                        IAgentEventStreamService.SENSITIVITY_S0, null, null, false);
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    dispatchEvent("screen.off", IAgentEventStreamService.PRIORITY_NORMAL,
                        IAgentEventStreamService.SENSITIVITY_S0, null, null, false);
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    dispatchEvent("device.unlocked", IAgentEventStreamService.PRIORITY_NORMAL,
                        IAgentEventStreamService.SENSITIVITY_S0, null, null, true);
                }
            }
        }, screenFilter);

        // TODO: 注册其他系统事件监听器（通知/前台/通话/电源/网络等）
    }

    /**
     * 权限检查
     */
    private boolean checkSubscribePermission() {
        return mContext.checkCallingPermission(SUBSCRIBE_EVENTS)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 从 UID 获取 Agent ID
     */
    private String getAgentIdFromUid(int uid) {
        // TODO: 实现 UID -> AgentID 映射逻辑
        return "agent_" + uid;
    }

    /**
     * 审计日志
     */
    private void logAuditEvent(String action, String details) {
        // TODO: 集成 AuditService
        if (DEBUG) {
            Slog.d(TAG, "Audit: " + action + " - " + details);
        }
    }

    /**
     * Lifecycle for SystemServer
     */
    public static class Lifecycle extends SystemService {
        private AgentEventStreamService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new AgentEventStreamService(getContext());
            mService.onStart();
        }
    }
}
