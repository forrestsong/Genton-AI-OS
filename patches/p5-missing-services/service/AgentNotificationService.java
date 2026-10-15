package com.android.server.genton;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.genton.perception.IAgentNotificationService;
import com.genton.perception.IAgentNotificationListener;
import com.genton.perception.NotificationEnvelope;
import com.genton.perception.NotificationQuery;
import com.genton.perception.NotificationAction;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentNotificationService - 通知感知服务
 * 
 * 为 AI Native OS 提供通知读取、清除和动作执行能力。
 * 
 * 核心功能：
 * 1. 查询通知栈（支持包名过滤、重要性过滤、PII 掩码）
 * 2. 清除通知（dismissNotification）
 * 3. 执行内联动作（performNotificationAction，包括 inline reply）
 * 4. 实时监听通知事件（onNotificationPosted/Removed）
 * 
 * 三层鉴权：
 * - 权限检查：READ_NOTIFICATIONS 权限（signature|privileged）
 * - 包名白名单：敏感应用（银行/支付）通知受限
 * - Reply 源检查：USER 源 reply 强制（防止 Agent 伪造用户回复）
 * 
 * PII 掩码机制：
 * - 自动识别并掩码：电话号码、邮箱、身份证号
 * - 掩码模式：FULL（完全掩码）/ PARTIAL（部分保留）/ NONE（不掩码）
 * 
 * @author AI Native OS Platform Team
 * @version 1.0
 */
public class AgentNotificationService extends SystemService {
    private static final String TAG = "AgentNotificationService";
    private static final boolean DEBUG = false;

    // 权限
    private static final String READ_NOTIFICATIONS = "com.genton.permission.READ_NOTIFICATIONS";
    private static final String DISMISS_NOTIFICATIONS = "com.genton.permission.DISMISS_NOTIFICATIONS";
    private static final String PERFORM_NOTIFICATION_ACTIONS = "com.genton.permission.PERFORM_NOTIFICATION_ACTIONS";

    // 受限应用包名前缀（银行/支付类）
    private static final String[] RESTRICTED_PACKAGE_PREFIXES = {
        "com.eg.android.AlipayGphone",  // 支付宝
        "com.tencent.mm",                // 微信
        "com.unionpay",                  // 银联
        "com.icbc",                      // 工商银行
        "com.chinamworld.main",          // 招商银行
    };

    // PII 识别正则
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "\\b1[3-9]\\d{9}\\b|\\b\\d{3}-\\d{4}-\\d{4}\\b"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    );
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "\\b\\d{17}[0-9Xx]\\b|\\b\\d{15}\\b"
    );

    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final PackageManager mPackageManager;
    
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    
    // 监听器注册表（agentId -> listener）
    private final Map<String, IAgentNotificationListener> mListeners = new ConcurrentHashMap<>();
    
    // 通知缓存（key -> envelope）
    private final Map<String, NotificationEnvelope> mNotificationCache = new ConcurrentHashMap<>();
    
    // 内部 NotificationListenerService 实现
    private GentonNotificationListener mNotificationListener;

    public AgentNotificationService(Context context) {
        super(context);
        mContext = context;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mPackageManager = context.getPackageManager();
    }

    /** [P5] 暴露 binder，供 GentonServiceRegistry 以规范服务名注册 */
    public android.os.IBinder getBinder() {
        return mBinder;
    }

    @Override
    public void onStart() {
        publishBinderService("genton_notification", mBinder);
        
        mHandlerThread = new HandlerThread("AgentNotificationService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        
        // 注册内部 NotificationListener
        mNotificationListener = new GentonNotificationListener();
        try {
            mNotificationListener.registerAsSystemService(
                mContext,
                new android.content.ComponentName(mContext, GentonNotificationListener.class),
                android.os.UserHandle.USER_ALL
            );
        } catch (android.os.RemoteException e) {
            Slog.w(TAG, "registerAsSystemService failed", e);
        }
        
        Slog.i(TAG, "AgentNotificationService started");
    }

    private final IAgentNotificationService.Stub mBinder = new IAgentNotificationService.Stub() {
        @Override
        public void subscribeDigest(com.genton.perception.INotificationDigestListener listener) {
            // 摘要订阅（当前实现不推送，占位满足契约）
        }

        
        @Override
        public List<NotificationEnvelope> queryNotifications(NotificationQuery query) {
            checkReadPermission();
            
            final long startTime = System.currentTimeMillis();
            
            // 获取当前活跃通知
            StatusBarNotification[] activeNotifications = 
                mNotificationListener.getActiveNotifications();
            
            if (activeNotifications == null) {
                Slog.w(TAG, "Failed to get active notifications");
                return new ArrayList<>();
            }
            
            List<NotificationEnvelope> results = new ArrayList<>();
            Set<String> packageFilter = query.packageFilter != null && query.packageFilter.length > 0
                ? new HashSet<>(Arrays.asList(query.packageFilter))
                : null;
            
            for (StatusBarNotification sbn : activeNotifications) {
                // 包名过滤
                if (packageFilter != null && !packageFilter.contains(sbn.getPackageName())) {
                    continue;
                }
                
                // 受限包名检查
                if (isRestrictedPackage(sbn.getPackageName())) {
                    if (DEBUG) {
                        Slog.d(TAG, "Skip restricted package: " + sbn.getPackageName());
                    }
                    continue;
                }
                
                // 重要性过滤
                Notification notification = sbn.getNotification();
                int importance = getNotificationImportance(sbn);
                if (importance < query.importanceMin) {
                    continue;
                }
                
                // 构建 Envelope
                NotificationEnvelope envelope = buildEnvelope(sbn, query);
                if (envelope != null) {
                    results.add(envelope);
                    
                    // 达到最大数量
                    if (results.size() >= query.maxItems) {
                        break;
                    }
                }
            }
            
            final long elapsed = System.currentTimeMillis() - startTime;
            if (DEBUG) {
                Slog.d(TAG, "queryNotifications: " + results.size() + " results in " + elapsed + "ms");
            }
            
            // 审计日志
            logAuditEvent("query_notifications", 
                "count=" + results.size() + ",elapsed=" + elapsed + "ms");
            
            return results;
        }
        
        @Override
        public void dismissNotification(String key) {
            checkDismissPermission();
            
            if (TextUtils.isEmpty(key)) {
                throw new IllegalArgumentException("Notification key cannot be empty");
            }
            
            // 检查通知是否属于受限应用
            NotificationEnvelope envelope = mNotificationCache.get(key);
            if (envelope != null && isRestrictedPackage(envelope.packageName)) {
                throw new SecurityException("Cannot dismiss notification from restricted package: " 
                    + envelope.packageName);
            }
            
            // 清除通知
            mHandler.post(() -> {
                try {
                    mNotificationListener.cancelNotification(key);
                    mNotificationCache.remove(key);
                    
                    if (DEBUG) {
                        Slog.d(TAG, "Dismissed notification: " + key);
                    }
                    
                    logAuditEvent("dismiss_notification", "key=" + key);
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to dismiss notification: " + key, e);
                }
            });
        }
        
        @Override
        public void performNotificationAction(String key, NotificationAction action) {
            checkPerformActionPermission();
            
            if (TextUtils.isEmpty(key) || action == null) {
                throw new IllegalArgumentException("Invalid notification key or action");
            }
            
            // 检查通知是否属于受限应用
            NotificationEnvelope envelope = mNotificationCache.get(key);
            if (envelope != null && isRestrictedPackage(envelope.packageName)) {
                throw new SecurityException("Cannot perform action on notification from restricted package: " 
                    + envelope.packageName);
            }
            
            // USER 源 Reply 强制检查
            if (action.requiresInput) {
                int callingUid = Binder.getCallingUid();
                if (!isUserSourcedReply(callingUid)) {
                    throw new SecurityException(
                        "Inline reply must be USER sourced (uid=" + callingUid + ")");
                }
            }
            
            // 执行动作
            mHandler.post(() -> {
                try {
                    executeNotificationAction(key, action);
                    
                    if (DEBUG) {
                        Slog.d(TAG, "Performed action: " + action.actionId + " on " + key);
                    }
                    
                    logAuditEvent("perform_action", 
                        "key=" + key + ",action=" + action.actionId + 
                        ",requiresInput=" + action.requiresInput);
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to perform action: " + action.actionId, e);
                }
            });
        }
        
        @Override
        public void registerListener(String agentId, IAgentNotificationListener listener) {
            checkReadPermission();
            
            if (TextUtils.isEmpty(agentId) || listener == null) {
                throw new IllegalArgumentException("Invalid agentId or listener");
            }
            
            mListeners.put(agentId, listener);
            
            if (DEBUG) {
                Slog.d(TAG, "Registered listener for agent: " + agentId);
            }
        }
        
        @Override
        public void unregisterListener(String agentId) {
            checkReadPermission();
            
            mListeners.remove(agentId);
            
            if (DEBUG) {
                Slog.d(TAG, "Unregistered listener for agent: " + agentId);
            }
        }
    };

    /**
     * 构建 NotificationEnvelope
     */
    private NotificationEnvelope buildEnvelope(StatusBarNotification sbn, NotificationQuery query) {
        try {
            Notification notification = sbn.getNotification();
            NotificationEnvelope envelope = new NotificationEnvelope();
            
            envelope.key = sbn.getKey();
            envelope.packageName = sbn.getPackageName();
            envelope.appLabel = getAppLabel(sbn.getPackageName());
            envelope.category = notification.category;
            envelope.postTime = sbn.getPostTime();
            envelope.importance = getNotificationImportance(sbn);
            
            // 提取标题和文本
            CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            
            envelope.title = title != null ? title.toString() : "";
            envelope.text = text != null ? text.toString() : "";
            
            // PII 掩码
            if (query.maskPii) {
                envelope.title = maskPii(envelope.title, query.maskMode);
                if (query.includeText) {
                    envelope.text = maskPii(envelope.text, query.maskMode);
                } else {
                    envelope.text = "";  // 不包含文本
                }
            } else if (!query.includeText) {
                envelope.text = "";
            }
            
            // 标志位
            envelope.ongoing = (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            envelope.autoCancel = (notification.flags & Notification.FLAG_AUTO_CANCEL) != 0;
            envelope.hasContentIntent = notification.contentIntent != null;
            
            // 提取动作
            if (query.includeActions && notification.actions != null) {
                envelope.actions = extractActions(notification);
            }
            
            // 缓存 Envelope
            mNotificationCache.put(envelope.key, envelope);
            
            return envelope;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to build envelope for: " + sbn.getKey(), e);
            return null;
        }
    }

    /**
     * 提取通知动作
     */
    private NotificationAction[] extractActions(Notification notification) {
        if (notification.actions == null || notification.actions.length == 0) {
            return new NotificationAction[0];
        }
        
        List<NotificationAction> actions = new ArrayList<>();
        for (int i = 0; i < notification.actions.length; i++) {
            Notification.Action action = notification.actions[i];
            
            NotificationAction na = new NotificationAction();
            na.actionId = "action_" + i;
            na.label = action.title != null ? action.title.toString() : "";
            na.requiresInput = action.getRemoteInputs() != null && action.getRemoteInputs().length > 0;
            
            actions.add(na);
        }
        
        return actions.toArray(new NotificationAction[0]);
    }

    /**
     * 执行通知动作
     */
    private void executeNotificationAction(String key, NotificationAction action) {
        StatusBarNotification[] activeNotifications = mNotificationListener.getActiveNotifications();
        if (activeNotifications == null) {
            Slog.w(TAG, "No active notifications");
            return;
        }
        
        for (StatusBarNotification sbn : activeNotifications) {
            if (sbn.getKey().equals(key)) {
                Notification notification = sbn.getNotification();
                if (notification.actions != null) {
                    int actionIndex = Integer.parseInt(action.actionId.replace("action_", ""));
                    if (actionIndex >= 0 && actionIndex < notification.actions.length) {
                        Notification.Action notificationAction = notification.actions[actionIndex];
                        try {
                            // 发送 PendingIntent
                            notificationAction.actionIntent.send();
                            
                            if (DEBUG) {
                                Slog.d(TAG, "Sent action intent for: " + key);
                            }
                        } catch (Exception e) {
                            Slog.e(TAG, "Failed to send action intent", e);
                        }
                    }
                }
                break;
            }
        }
    }

    /**
     * PII 掩码处理
     */
    private String maskPii(String input, int maskMode) {
        if (TextUtils.isEmpty(input)) {
            return input;
        }
        
        String result = input;
        
        // 电话号码掩码
        Matcher phoneMatcher = PHONE_PATTERN.matcher(result);
        while (phoneMatcher.find()) {
            String phone = phoneMatcher.group();
            String masked = maskMode == 1 ? maskPartial(phone, 3, 4) : maskFull(phone);
            result = result.replace(phone, masked);
        }
        
        // 邮箱掩码
        Matcher emailMatcher = EMAIL_PATTERN.matcher(result);
        while (emailMatcher.find()) {
            String email = emailMatcher.group();
            String masked = maskMode == 1 ? maskEmail(email) : maskFull(email);
            result = result.replace(email, masked);
        }
        
        // 身份证号掩码
        Matcher idMatcher = ID_CARD_PATTERN.matcher(result);
        while (idMatcher.find()) {
            String idCard = idMatcher.group();
            String masked = maskMode == 1 ? maskPartial(idCard, 6, 4) : maskFull(idCard);
            result = result.replace(idCard, masked);
        }
        
        return result;
    }

    /**
     * 部分掩码（保留前后部分）
     */
    private String maskPartial(String value, int prefixLen, int suffixLen) {
        if (value.length() <= prefixLen + suffixLen) {
            return maskFull(value);
        }
        return value.substring(0, prefixLen) + "***" + value.substring(value.length() - suffixLen);
    }

    /**
     * 完全掩码
     */
    private String maskFull(String value) {
        return "***";
    }

    /**
     * 邮箱掩码（保留域名）
     */
    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return maskFull(email);
        }
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        
        if (localPart.length() <= 2) {
            return "***" + domain;
        }
        return localPart.charAt(0) + "***" + domain;
    }

    /**
     * 获取通知重要性
     */
    private int getNotificationImportance(StatusBarNotification sbn) {
        try {
            return mNotificationManager.getImportance();
        } catch (Exception e) {
            // 降级：使用优先级估算
            Notification notification = sbn.getNotification();
            if (notification.priority >= Notification.PRIORITY_HIGH) {
                return NotificationManager.IMPORTANCE_HIGH;
            } else if (notification.priority >= Notification.PRIORITY_DEFAULT) {
                return NotificationManager.IMPORTANCE_DEFAULT;
            } else {
                return NotificationManager.IMPORTANCE_LOW;
            }
        }
    }

    /**
     * 获取应用标签
     */
    private String getAppLabel(String packageName) {
        try {
            ApplicationInfo appInfo = mPackageManager.getApplicationInfo(packageName, 0);
            return mPackageManager.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    /**
     * 检查是否为受限包名
     */
    private boolean isRestrictedPackage(String packageName) {
        for (String prefix : RESTRICTED_PACKAGE_PREFIXES) {
            if (packageName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否为用户源 Reply（防止 Agent 伪造）
     */
    private boolean isUserSourcedReply(int callingUid) {
        // TODO: 实现 USER 源检查逻辑
        // 简化实现：仅允许 SystemUI 或特定 UID
        return callingUid == android.os.Process.SYSTEM_UID;
    }

    /**
     * 权限检查
     */
    private void checkReadPermission() {
        if (mContext.checkCallingPermission(READ_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires READ_NOTIFICATIONS permission");
        }
    }

    private void checkDismissPermission() {
        if (mContext.checkCallingPermission(DISMISS_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DISMISS_NOTIFICATIONS permission");
        }
    }

    private void checkPerformActionPermission() {
        if (mContext.checkCallingPermission(PERFORM_NOTIFICATION_ACTIONS) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires PERFORM_NOTIFICATION_ACTIONS permission");
        }
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
     * 内部 NotificationListenerService 实现
     */
    private class GentonNotificationListener extends NotificationListenerService {
        
        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
            if (DEBUG) {
                Slog.d(TAG, "onNotificationPosted: " + sbn.getKey());
            }
            
            // 构建 Envelope
            NotificationQuery defaultQuery = new NotificationQuery();
            defaultQuery.maskPii = true;
            defaultQuery.includeText = true;
            defaultQuery.includeActions = true;
            defaultQuery.maskMode = 1;  // PARTIAL
            
            NotificationEnvelope envelope = buildEnvelope(sbn, defaultQuery);
            if (envelope == null) {
                return;
            }
            
            // 通知所有监听器
            for (Map.Entry<String, IAgentNotificationListener> entry : mListeners.entrySet()) {
                try {
                    entry.getValue().onNotificationPosted(envelope, REASON_APP_CANCEL);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify listener: " + entry.getKey(), e);
                    mListeners.remove(entry.getKey());
                }
            }
        }
        
        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
            if (DEBUG) {
                Slog.d(TAG, "onNotificationRemoved: " + sbn.getKey() + ", reason=" + reason);
            }
            
            String key = sbn.getKey();
            mNotificationCache.remove(key);
            
            // 通知所有监听器
            for (Map.Entry<String, IAgentNotificationListener> entry : mListeners.entrySet()) {
                try {
                    entry.getValue().onNotificationRemoved(key, reason);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify listener: " + entry.getKey(), e);
                    mListeners.remove(entry.getKey());
                }
            }
        }
    }

    /**
     * Lifecycle for SystemServer
     */
    public static class Lifecycle extends SystemService {
        private AgentNotificationService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new AgentNotificationService(getContext());
            mService.onStart();
        }
    }
}
