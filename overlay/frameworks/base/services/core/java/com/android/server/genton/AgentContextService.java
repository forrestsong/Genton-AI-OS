package com.android.server.genton;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.view.inputmethod.InputMethodManager;

import com.genton.context.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上下文服务 - OS 统一持有"当前世界状态"的结构化上下文索引
 * 
 * 核心功能：
 * 1. 热缓存预订阅：预订阅 UiTree/事件流/通知等，维持热缓存帧
 * 2. ContextFrame 差分：只推送变化的字段，节省带宽
 * 3. TOP_UI_SUMMARY 源头掩码：UI摘要层做PII掩码
 * 4. 多 Agent 可见性：按授权过滤字段
 * 5. 订阅治理：限流、优先级、审计
 * 
 * @author AI Native OS Team
 * @since A2 Milestone
 */
public class AgentContextService extends IAgentContextService.Stub {
    private static final String TAG = "AgentContextService";

    // 帧缓存窗口（300ms 内重复请求返回缓存帧）
    private static final long FRAME_CACHE_WINDOW_MS = 300;

    // 历史帧环形缓冲区大小
    private static final int HISTORY_BUFFER_SIZE = 100;

    // 最大订阅数限制
    private static final int MAX_SUBSCRIPTIONS = 50;

    private final Context mContext;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;

    // 帧序列号生成器
    private final AtomicLong mSequenceId = new AtomicLong(0);

    // 当前上下文帧（热缓存）
    private ContextFrame mCurrentFrame;
    private long mLastFrameUpdateMs;

    // 历史帧环形缓冲区
    private final LinkedList<ContextFrame> mHistoryFrames = new LinkedList<>();

    // 订阅管理
    private final Map<String, SubscriptionContext> mSubscriptions = new HashMap<>();
    private final RemoteCallbackList<IAgentContextListener> mListeners = new RemoteCallbackList<>();

    // 上游数据源（预订阅）
    private AgentUiTreeService mUiTreeService;
    private AgentEventStreamService mEventStreamService;
    private IAgentNotificationService mNotificationService;

    // 审计埋点
    private AuditInstrumentation mAuditInstrumentation;

    /**
     * 订阅上下文
     */
    private static class SubscriptionContext {
        String subscriptionId;
        String subscriberId;
        ContextSubscription subscription;
        IAgentContextListener listener;
        long createdAtMs;
        int framesPushed;
        long lastPushMs;
        ContextFrame lastFrame; // 上次推送的帧（用于差分）
    }

    public AgentContextService(Context context) {
        mContext = context;

        // 创建后台线程
        mHandlerThread = new HandlerThread("AgentContextService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        // 初始化审计埋点
        mAuditInstrumentation = new AuditInstrumentation(context);

        // 初始化当前帧
        mCurrentFrame = createEmptyFrame();
        mLastFrameUpdateMs = System.currentTimeMillis();

        Slog.i(TAG, "AgentContextService initialized");
    }

    /**
     * 启动服务（在 SystemServer 中调用）
     */
    public void start() {
        // 预订阅上游数据源
        subscribeToUpstreamSources();

        // 启动周期性帧更新任务
        mHandler.post(this::updateContextFrame);

        Slog.i(TAG, "AgentContextService started");
    }

    @Override
    public int getCurrentFrame(int fields, ContextFrame frame) {
        // 权限检查（需要 context.read 能力）
        if (!checkPermission("context.read")) {
            Slog.w(TAG, "getCurrentFrame denied: missing capability");
            return IAgentContextService.STATUS_DENIED;
        }

        synchronized (this) {
            // 检查缓存窗口
            long now = System.currentTimeMillis();
            if (now - mLastFrameUpdateMs > FRAME_CACHE_WINDOW_MS) {
                // 缓存过期，触发更新
                updateContextFrameSync();
            }

            // 按字段掩码过滤
            ContextFrame filtered = filterFrameByFields(mCurrentFrame, fields);
            copyFrame(filtered, frame);

            // 审计埋点
            String summary = String.format("读取上下文帧 fields=0x%X seq=%d", 
                fields, frame.sequenceId);
            mAuditInstrumentation.recordPerceptionRead(
                "context_frame",
                getCallingAgentId(),
                null, // intentId
                summary,
                "frame_" + frame.sequenceId
            );
        }

        return IAgentContextService.STATUS_OK;
    }

    @Override
    public int subscribe(ContextSubscription subscription, 
                        IAgentContextListener listener,
                        ContextSubscriptionHandle handle) {
        // 权限检查
        if (!checkPermission("context.subscribe")) {
            Slog.w(TAG, "subscribe denied: missing capability");
            return IAgentContextService.STATUS_DENIED;
        }

        // 参数校验
        if (subscription == null || listener == null || handle == null) {
            return IAgentContextService.STATUS_INVALID_REQ;
        }

        if (subscription.fields == 0) {
            Slog.w(TAG, "subscribe failed: no fields specified");
            return IAgentContextService.STATUS_INVALID_REQ;
        }

        synchronized (mSubscriptions) {
            // 检查订阅数限制
            if (mSubscriptions.size() >= MAX_SUBSCRIPTIONS) {
                Slog.w(TAG, "subscribe failed: max subscriptions reached");
                return IAgentContextService.STATUS_DENIED;
            }

            // 创建订阅上下文
            SubscriptionContext ctx = new SubscriptionContext();
            ctx.subscriptionId = UUID.randomUUID().toString();
            ctx.subscriberId = subscription.subscriberId;
            ctx.subscription = subscription;
            ctx.listener = listener;
            ctx.createdAtMs = System.currentTimeMillis();
            ctx.framesPushed = 0;
            ctx.lastPushMs = 0;
            ctx.lastFrame = null;

            mSubscriptions.put(ctx.subscriptionId, ctx);
            mListeners.register(listener);

            // 填充句柄
            handle.subscriptionId = ctx.subscriptionId;
            handle.subscriberId = ctx.subscriberId;
            handle.createdAtMs = ctx.createdAtMs;

            Slog.i(TAG, String.format("Subscription created: id=%s subscriber=%s fields=0x%X",
                ctx.subscriptionId, ctx.subscriberId, subscription.fields));

            // 审计埋点
            String summary = String.format("订阅上下文 fields=0x%X minInterval=%dms", 
                subscription.fields, subscription.minIntervalMs);
            mAuditInstrumentation.recordSystemEvent(
                "agent_context",
                "subscription_created",
                summary
            );
        }

        return IAgentContextService.STATUS_OK;
    }

    @Override
    public int unsubscribe(ContextSubscriptionHandle handle) {
        if (handle == null || handle.subscriptionId == null) {
            return IAgentContextService.STATUS_INVALID_REQ;
        }

        synchronized (mSubscriptions) {
            SubscriptionContext ctx = mSubscriptions.remove(handle.subscriptionId);
            if (ctx == null) {
                return IAgentContextService.STATUS_INVALID_REQ;
            }

            mListeners.unregister(ctx.listener);

            Slog.i(TAG, String.format("Subscription removed: id=%s subscriber=%s",
                handle.subscriptionId, ctx.subscriberId));

            // 审计埋点
            String summary = String.format("取消订阅 pushed=%d", ctx.framesPushed);
            mAuditInstrumentation.recordSystemEvent(
                "agent_context",
                "subscription_removed",
                summary
            );
        }

        return IAgentContextService.STATUS_OK;
    }

    @Override
    public ContextFrame[] getHistory(int fields, long sinceRealtimeNanos) {
        // 权限检查
        if (!checkPermission("context.read")) {
            return new ContextFrame[0];
        }

        synchronized (mHistoryFrames) {
            List<ContextFrame> result = new ArrayList<>();
            long sinceMs = sinceRealtimeNanos / 1_000_000;

            for (ContextFrame frame : mHistoryFrames) {
                if (frame.timestampNanos / 1_000_000 >= sinceMs) {
                    ContextFrame filtered = filterFrameByFields(frame, fields);
                    result.add(filtered);
                }
            }

            return result.toArray(new ContextFrame[0]);
        }
    }

    @Override
    public SubscriptionInfo[] listSubscriptions() {
        // 权限检查（需要管理员权限）
        if (!checkPermission("context.admin")) {
            return new SubscriptionInfo[0];
        }

        synchronized (mSubscriptions) {
            List<SubscriptionInfo> result = new ArrayList<>();

            for (SubscriptionContext ctx : mSubscriptions.values()) {
                SubscriptionInfo info = new SubscriptionInfo();
                info.subscriptionId = ctx.subscriptionId;
                info.subscriberId = ctx.subscriberId;
                info.fields = ctx.subscription.fields;
                info.minIntervalMs = ctx.subscription.minIntervalMs;
                info.createdAtMs = ctx.createdAtMs;
                info.framesPushed = ctx.framesPushed;
                info.lastPushMs = ctx.lastPushMs;

                result.add(info);
            }

            return result.toArray(new SubscriptionInfo[0]);
        }
    }

    // ===== 内部方法 =====

    /**
     * 预订阅上游数据源
     */
    private void subscribeToUpstreamSources() {
        // 1. 订阅 AgentUiTreeService 的 UI 树变化（获取 TOP_UI_SUMMARY）
        try {
            mUiTreeService = (AgentUiTreeService) ServiceManager.getService("agent_uitree");
            if (mUiTreeService != null) {
                // 订阅 UI 树变化，只消费摘要层输出（已做 PII 掩码）
                mUiTreeService.subscribeUiSummary(new IUiSummaryListener.Stub() {
                    @Override
                    public void onSummaryChanged(String summary) {
                        // UI 摘要变化时触发帧更新
                        mHandler.post(() -> updateContextFrame());
                    }
                });
                Slog.d(TAG, "Subscribed to UiTreeService");
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to subscribe to UiTreeService", e);
        }

        // 2. 订阅 AgentEventStreamService 的前台应用变化事件
        try {
            mEventStreamService = (AgentEventStreamService) ServiceManager.getService("agent_eventstream");
            if (mEventStreamService != null) {
                // 订阅前台应用切换事件
                mEventStreamService.subscribe(
                    "foreground.changed",
                    new IEventStreamListener.Stub() {
                        @Override
                        public void onEvent(EventPayload event) {
                            // 前台应用变化时触发帧更新
                            mHandler.post(() -> updateContextFrame());
                        }
                    }
                );
                Slog.d(TAG, "Subscribed to EventStreamService (foreground)");
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to subscribe to EventStreamService", e);
        }

        // 3. 订阅通知服务的通知变化
        try {
            mNotificationService = IAgentNotificationService.Stub.asInterface(
                ServiceManager.getService("agent_notification")
            );
            if (mNotificationService != null) {
                // 订阅通知摘要变化
                mNotificationService.subscribeDigest(new INotificationDigestListener.Stub() {
                    @Override
                    public void onDigestChanged() {
                        mHandler.post(() -> updateContextFrame());
                    }
                });
                Slog.d(TAG, "Subscribed to NotificationService");
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to subscribe to NotificationService", e);
        }

        // 4. 监听前台应用变化（ActivityManagerService）
        // 通过 ActivityManagerService 的 ProcessObserver 监听
        // 实际实现由 EventStreamService 封装，已在步骤2订阅

        // 5. 监听媒体会话变化（MediaSessionManager）
        // 通过 MediaSessionManager.addOnActiveSessionsChangedListener
        // 在 updateMedia() 方法中直接查询即可

        // 6. 监听传感器状态变化（BatteryManager/NetworkManager等）
        // 通过广播接收器监听系统状态变化
        registerSystemStateReceivers();

        Slog.i(TAG, "Upstream sources subscribed");
    }

    /**
     * 注册系统状态广播接收器
     */
    private void registerSystemStateReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 系统状态变化时触发帧更新
                mHandler.post(() -> updateContextFrame());
            }
        }, filter);

        Slog.d(TAG, "System state receivers registered");
    }

    /**
     * 周期性更新上下文帧
     */
    private void updateContextFrame() {
        updateContextFrameSync();

        // 调度下次更新（100ms 周期）
        mHandler.postDelayed(this::updateContextFrame, 100);
    }

    /**
     * 同步更新上下文帧
     */
    private void updateContextFrameSync() {
        synchronized (this) {
            // 创建新帧
            ContextFrame newFrame = createEmptyFrame();
            newFrame.sequenceId = mSequenceId.incrementAndGet();
            newFrame.timestampNanos = System.nanoTime();

            // 更新各字段（从上游数据源聚合）
            updateForegroundApp(newFrame);
            updateTopUiSummary(newFrame);
            updateMedia(newFrame);
            updateNotificationsDigest(newFrame);
            updateSensors(newFrame);
            updateLocation(newFrame);
            updateAgentTasks(newFrame);
            updateActiveInput(newFrame);

            // 计算变化掩码
            newFrame.fieldChangedMask = computeChangeMask(mCurrentFrame, newFrame);

            // 更新当前帧
            mCurrentFrame = newFrame;
            mLastFrameUpdateMs = System.currentTimeMillis();

            // 加入历史缓冲区
            addToHistory(newFrame);

            // 推送给订阅者
            pushToSubscribers(newFrame);
        }
    }

    /**
     * 更新前台应用信息
     */
    private void updateForegroundApp(ContextFrame frame) {
        // TODO: 从 ActivityManagerService 获取前台应用
        frame.foregroundApp = new ForegroundAppInfo();
        frame.foregroundApp.packageName = "com.example.app";
        frame.foregroundApp.activityName = ".MainActivity";
        frame.foregroundApp.appLabel = "Example App";
        frame.foregroundApp.foregroundSinceMs = System.currentTimeMillis();
        frame.foregroundApp.isSystemUi = false;

        frame.fieldAvailableMask |= IAgentContextService.FIELD_FOREGROUND_APP;
    }

    /**
     * 更新TOP_UI_SUMMARY（源头掩码）
     */
    private void updateTopUiSummary(ContextFrame frame) {
        // TODO: 从 AgentUiTreeService 获取 UI 树，通过小模型生成摘要
        // 重要：在此处做 PII 掩码，屏蔽密码/键盘/安全窗口区域
        frame.topUiSummary = "主屏幕显示应用列表";

        frame.fieldAvailableMask |= IAgentContextService.FIELD_TOP_UI_SUMMARY;
    }

    /**
     * 更新媒体会话信息
     */
    private void updateMedia(ContextFrame frame) {
        try {
            // 从 MediaSessionManager 获取活跃媒体会话
            MediaSessionManager msm = (MediaSessionManager) mContext.getSystemService(
                Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                List<MediaController> controllers = msm.getActiveSessions(null);
                if (!controllers.isEmpty()) {
                    MediaController controller = controllers.get(0);
                    MediaMetadata metadata = controller.getMetadata();
                    PlaybackState state = controller.getPlaybackState();
                    
                    frame.media = new MediaInfo();
                    frame.media.packageName = controller.getPackageName();
                    frame.media.isPlaying = state != null && 
                        state.getState() == PlaybackState.STATE_PLAYING;
                    
                    if (metadata != null) {
                        // 标题脱敏（可配置是否隐藏）
                        frame.media.title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                        frame.media.artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    }
                    
                    frame.fieldAvailableMask |= IAgentContextService.FIELD_MEDIA;
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get media info", e);
        }
    }

    /**
     * 更新通知摘要
     */
    private void updateNotificationsDigest(ContextFrame frame) {
        // TODO: 从 AgentNotificationService 获取通知摘要
        frame.notificationsDigest = new NotificationsDigest();
        frame.notificationsDigest.unreadCount = 0;
        frame.notificationsDigest.sourcePackages = new String[0];
        frame.notificationsDigest.summary = null;
        frame.notificationsDigest.lastNotificationMs = 0;
        frame.notificationsDigest.hasHighPriority = false;

        frame.fieldAvailableMask |= IAgentContextService.FIELD_NOTIFICATIONS_DIGEST;
    }

    /**
     * 更新传感器状态
     */
    private void updateSensors(ContextFrame frame) {
        // TODO: 从各系统服务获取传感器状态
        frame.sensors = new SensorState();
        frame.sensors.screenState = 1; // 打开
        frame.sensors.batteryPercent = 80;
        frame.sensors.isCharging = false;
        frame.sensors.networkType = "wifi";
        frame.sensors.ringerMode = "normal";
        frame.sensors.hasHeadset = false;
        frame.sensors.isDoNotDisturb = false;

        frame.fieldAvailableMask |= IAgentContextService.FIELD_SENSORS;
    }

    /**
     * 更新粗位置标签（需授权）
     */
    private void updateLocation(ContextFrame frame) {
        // TODO: 从 LocationManager 获取粗位置，转换为标签（家/公司/通勤）
        // 重要：细坐标绝不入帧
        frame.locationLabel = null; // 未授权

        // frame.fieldAvailableMask |= IAgentContextService.FIELD_LOCATION;
    }

    /**
     * 更新活跃Agent任务
     */
    private void updateAgentTasks(ContextFrame frame) {
        // TODO: 从 AgentRuntimeService 获取活跃任务
        frame.agentTasks = new AgentTaskInfo[0];

        frame.fieldAvailableMask |= IAgentContextService.FIELD_AGENT_TASKS;
    }

    /**
     * 更新输入状态
     */
    private void updateActiveInput(ContextFrame frame) {
        // TODO: 从 InputMethodService 获取输入框焦点状态
        frame.activeInput = false;

        frame.fieldAvailableMask |= IAgentContextService.FIELD_ACTIVE_INPUT;
    }

    /**
     * 计算变化掩码
     */
    private int computeChangeMask(ContextFrame oldFrame, ContextFrame newFrame) {
        int mask = 0;

        // 比较各字段是否变化
        if (!equals(oldFrame.foregroundApp, newFrame.foregroundApp)) {
            mask |= IAgentContextService.FIELD_FOREGROUND_APP;
        }
        if (!equals(oldFrame.topUiSummary, newFrame.topUiSummary)) {
            mask |= IAgentContextService.FIELD_TOP_UI_SUMMARY;
        }
        if (!equals(oldFrame.media, newFrame.media)) {
            mask |= IAgentContextService.FIELD_MEDIA;
        }
        if (!equals(oldFrame.notificationsDigest, newFrame.notificationsDigest)) {
            mask |= IAgentContextService.FIELD_NOTIFICATIONS_DIGEST;
        }
        if (!equals(oldFrame.sensors, newFrame.sensors)) {
            mask |= IAgentContextService.FIELD_SENSORS;
        }
        if (!equals(oldFrame.locationLabel, newFrame.locationLabel)) {
            mask |= IAgentContextService.FIELD_LOCATION;
        }
        if (!equalsArray(oldFrame.agentTasks, newFrame.agentTasks)) {
            mask |= IAgentContextService.FIELD_AGENT_TASKS;
        }
        if (oldFrame.activeInput != newFrame.activeInput) {
            mask |= IAgentContextService.FIELD_ACTIVE_INPUT;
        }

        return mask;
    }

    /**
     * 加入历史缓冲区
     */
    private void addToHistory(ContextFrame frame) {
        synchronized (mHistoryFrames) {
            mHistoryFrames.addLast(frame);

            // 保持环形缓冲区大小
            while (mHistoryFrames.size() > HISTORY_BUFFER_SIZE) {
                mHistoryFrames.removeFirst();
            }
        }
    }

    /**
     * 推送给订阅者
     */
    private void pushToSubscribers(ContextFrame frame) {
        synchronized (mSubscriptions) {
            for (SubscriptionContext ctx : mSubscriptions.values()) {
                // 检查节流间隔
                long now = System.currentTimeMillis();
                if (now - ctx.lastPushMs < ctx.subscription.minIntervalMs) {
                    continue;
                }

                // 检查变化
                if (ctx.subscription.onChangeOnly) {
                    int subscribedFields = ctx.subscription.fields;
                    int changedFields = frame.fieldChangedMask & subscribedFields;
                    if (changedFields == 0) {
                        continue; // 无变化，跳过
                    }
                }

                // 按订阅字段过滤
                ContextFrame filtered = filterFrameByFields(frame, ctx.subscription.fields);

                // 异步推送
                final ContextSubscriptionHandle handle = new ContextSubscriptionHandle();
                handle.subscriptionId = ctx.subscriptionId;
                handle.subscriberId = ctx.subscriberId;
                handle.createdAtMs = ctx.createdAtMs;

                mHandler.post(() -> {
                    try {
                        ctx.listener.onFrameChanged(handle, filtered);
                        ctx.framesPushed++;
                        ctx.lastPushMs = System.currentTimeMillis();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to push frame to subscriber: " + ctx.subscriberId, e);
                    }
                });
            }
        }
    }

    /**
     * 按字段掩码过滤帧
     */
    private ContextFrame filterFrameByFields(ContextFrame frame, int fields) {
        ContextFrame filtered = createEmptyFrame();
        filtered.sequenceId = frame.sequenceId;
        filtered.timestampNanos = frame.timestampNanos;
        filtered.fieldChangedMask = frame.fieldChangedMask & fields;
        filtered.fieldAvailableMask = frame.fieldAvailableMask & fields;

        if ((fields & IAgentContextService.FIELD_FOREGROUND_APP) != 0) {
            filtered.foregroundApp = frame.foregroundApp;
        }
        if ((fields & IAgentContextService.FIELD_TOP_UI_SUMMARY) != 0) {
            filtered.topUiSummary = frame.topUiSummary;
        }
        if ((fields & IAgentContextService.FIELD_MEDIA) != 0) {
            filtered.media = frame.media;
        }
        if ((fields & IAgentContextService.FIELD_NOTIFICATIONS_DIGEST) != 0) {
            filtered.notificationsDigest = frame.notificationsDigest;
        }
        if ((fields & IAgentContextService.FIELD_SENSORS) != 0) {
            filtered.sensors = frame.sensors;
        }
        if ((fields & IAgentContextService.FIELD_LOCATION) != 0) {
            filtered.locationLabel = frame.locationLabel;
        }
        if ((fields & IAgentContextService.FIELD_AGENT_TASKS) != 0) {
            filtered.agentTasks = frame.agentTasks;
        }
        if ((fields & IAgentContextService.FIELD_ACTIVE_INPUT) != 0) {
            filtered.activeInput = frame.activeInput;
        }

        return filtered;
    }

    /**
     * 创建空帧
     */
    private ContextFrame createEmptyFrame() {
        ContextFrame frame = new ContextFrame();
        frame.sequenceId = 0;
        frame.timestampNanos = 0;
        frame.fieldChangedMask = 0;
        frame.fieldAvailableMask = 0;
        frame.foregroundApp = null;
        frame.topUiSummary = null;
        frame.media = null;
        frame.notificationsDigest = null;
        frame.sensors = null;
        frame.locationLabel = null;
        frame.agentTasks = null;
        frame.activeInput = false;
        return frame;
    }

    /**
     * 复制帧
     */
    private void copyFrame(ContextFrame src, ContextFrame dst) {
        dst.sequenceId = src.sequenceId;
        dst.timestampNanos = src.timestampNanos;
        dst.fieldChangedMask = src.fieldChangedMask;
        dst.fieldAvailableMask = src.fieldAvailableMask;
        dst.foregroundApp = src.foregroundApp;
        dst.topUiSummary = src.topUiSummary;
        dst.media = src.media;
        dst.notificationsDigest = src.notificationsDigest;
        dst.sensors = src.sensors;
        dst.locationLabel = src.locationLabel;
        dst.agentTasks = src.agentTasks;
        dst.activeInput = src.activeInput;
    }

    /**
     * 权限检查（简化版）
     */
    private boolean checkPermission(String capability) {
        // TODO: 集成 PolicyEngineService 进行权限检查
        return true;
    }

    /**
     * 获取调用者 Agent ID
     */
    private String getCallingAgentId() {
        // TODO: 从 Binder 调用上下文获取 Agent ID
        return "unknown";
    }

    // 简化的对象比较方法
    private boolean equals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private boolean equalsArray(Object[] a, Object[] b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!equals(a[i], b[i])) return false;
        }
        return true;
    }
}
