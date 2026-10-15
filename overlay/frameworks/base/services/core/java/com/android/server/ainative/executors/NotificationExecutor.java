package com.android.server.ainative.executors;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import android.util.Slog;

import com.ainative.action.ActionRequest;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 通知执行器
 * 
 * 核心职责：
 * 1. 发送系统通知（Agent 生成通知）
 * 2. 清除通知（按 key/package/全部）
 * 3. 响应通知 Action（点击/回复/取消）
 * 4. 修改通知状态（静音/延后提醒）
 * 
 * 安全约束：
 * - Agent 通知必须显示来源标识
 * - 不可冒充系统/其他应用通知
 * - 敏感通知（银行/支付）不可清除
 * - Inline reply 需要 USER 来源 Taint 检查
 * 
 * 性能要求：
 * - 发送延迟 P50 < 30ms
 * - 清除延迟 P50 < 10ms
 * 
 * @see NotificationManager
 * @see AgentNotificationService
 */
public class NotificationExecutor implements ActionExecutor {
    private static final String TAG = "NotificationExecutor";
    
    // Agent 通知渠道
    private static final String AGENT_CHANNEL_ID = "agent_notifications";
    private static final String AGENT_CHANNEL_NAME = "AI Agent Notifications";
    
    // 受保护包名（不可清除通知）
    private static final String[] PROTECTED_PACKAGES = {
        "com.android.systemui",
        "com.android.vending",  // Play Store
        // 银行/支付应用（待配置化）
    };
    
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    
    public NotificationExecutor(Context context) {
        mContext = context;
        mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        // 创建 Agent 通知渠道
        createAgentNotificationChannel();
        
        Slog.i(TAG, "NotificationExecutor initialized");
    }
    
    @Override
    public String getExecutorType() {
        return "notification";
    }
    
    /**
     * 执行通知操作
     * 
     * 支持动作：
     * - notification.post：发送通知
     * - notification.cancel：取消通知
     * - notification.clear_all：清除全部通知
     * - notification.reply：回复通知（Inline Reply）
     * - notification.action：执行通知 Action
     */
    @Override
    public ExecutionResult execute(ActionRequest request, ExecutionContext context) {
        long startNanos = System.nanoTime();
        
        try {
            JSONObject args = new JSONObject(request.argumentsJson);
            String actionId = request.actionId;
            
            ExecutionResult result;
            
            switch (actionId) {
                case "notification.post":
                    result = executePost(args, request);
                    break;
                case "notification.cancel":
                    result = executeCancel(args);
                    break;
                case "notification.clear_all":
                    result = executeClearAll(args);
                    break;
                case "notification.reply":
                    result = executeReply(args, request);
                    break;
                case "notification.action":
                    result = executeAction(args);
                    break;
                default:
                    return ExecutionResult.failure("Unsupported action: " + actionId);
            }
            
            if (result.success) {
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                JSONObject resultJson = new JSONObject(result.resultJson);
                resultJson.put("elapsed_us", elapsedMicros);
                result.resultJson = resultJson.toString();
            }
            
            return result;
            
        } catch (Exception e) {
            Slog.e(TAG, "Notification execution failed", e);
            return ExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }
    
    /**
     * 发送通知
     * 
     * 参数：
     * {
     *   "title": "提醒",
     *   "text": "您有新消息",
     *   "priority": "default" | "low" | "high",  // 可选
     *   "autoCancel": true,  // 可选
     *   "actions": [  // 可选
     *     {"label": "查看", "actionId": "view"},
     *     {"label": "忽略", "actionId": "dismiss"}
     *   ]
     * }
     */
    private ExecutionResult executePost(JSONObject args, ActionRequest request) throws Exception {
        if (!args.has("title") || !args.has("text")) {
            return ExecutionResult.failure("Missing required parameters: title, text");
        }
        
        String title = args.getString("title");
        String text = args.getString("text");
        
        // 构建通知
        Notification.Builder builder = new Notification.Builder(mContext, AGENT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true);
        
        // 添加 Agent 来源标识
        builder.setSubText("AI Agent");
        
        // 优先级
        if (args.has("priority")) {
            String priority = args.getString("priority");
            switch (priority) {
                case "low":
                    builder.setPriority(Notification.PRIORITY_LOW);
                    break;
                case "high":
                    builder.setPriority(Notification.PRIORITY_HIGH);
                    break;
                default:
                    builder.setPriority(Notification.PRIORITY_DEFAULT);
            }
        }
        
        // 自动取消
        if (args.optBoolean("autoCancel", true)) {
            builder.setAutoCancel(true);
        }
        
        // 添加 Actions
        if (args.has("actions")) {
            JSONArray actionsArray = args.getJSONArray("actions");
            for (int i = 0; i < actionsArray.length(); i++) {
                JSONObject actionJson = actionsArray.getJSONObject(i);
                String label = actionJson.getString("label");
                String actionId = actionJson.getString("actionId");
                
                // 创建 PendingIntent
                Intent actionIntent = new Intent("com.ainative.NOTIFICATION_ACTION");
                actionIntent.putExtra("actionId", actionId);
                
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    mContext,
                    actionId.hashCode(),
                    actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                
                builder.addAction(new Notification.Action.Builder(
                    0,  // icon
                    label,
                    pendingIntent
                ).build());
            }
        }
        
        // 发送通知
        int notificationId = generateNotificationId(request.actionRequestId);
        mNotificationManager.notify(notificationId, builder.build());
        
        JSONObject result = new JSONObject();
        result.put("notification_id", notificationId);
        result.put("title", title);
        
        Slog.i(TAG, "Notification posted: id=" + notificationId);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 取消通知
     * 
     * 参数：
     * {
     *   "key": "0|com.example|123|null|10001",  // 通知 key
     *   // 或
     *   "package": "com.example",  // 包名（清除该应用所有通知）
     *   // 或
     *   "notification_id": 123  // Agent 发送的通知 ID
     * }
     */
    private ExecutionResult executeCancel(JSONObject args) throws Exception {
        int cancelledCount = 0;
        
        if (args.has("notification_id")) {
            // 取消 Agent 发送的通知
            int notificationId = args.getInt("notification_id");
            mNotificationManager.cancel(notificationId);
            cancelledCount = 1;
            
        } else if (args.has("key")) {
            // 取消指定 key 的通知
            String key = args.getString("key");
            
            // 检查是否为受保护通知
            if (isProtectedNotification(key)) {
                return ExecutionResult.failure("Cannot cancel protected notification: " + key);
            }
            
            mNotificationManager.cancel(key);
            cancelledCount = 1;
            
        } else if (args.has("package")) {
            // 取消指定包的所有通知
            String packageName = args.getString("package");
            
            // 检查是否为受保护包
            if (isProtectedPackage(packageName)) {
                return ExecutionResult.failure("Cannot cancel notifications from protected package: " + packageName);
            }
            
            StatusBarNotification[] notifications = mNotificationManager.getActiveNotifications();
            for (StatusBarNotification sbn : notifications) {
                if (sbn.getPackageName().equals(packageName)) {
                    mNotificationManager.cancel(sbn.getKey());
                    cancelledCount++;
                }
            }
            
        } else {
            return ExecutionResult.failure("Missing parameter: notification_id, key, or package");
        }
        
        JSONObject result = new JSONObject();
        result.put("cancelled_count", cancelledCount);
        
        Slog.i(TAG, "Notifications cancelled: count=" + cancelledCount);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 清除全部通知
     * 
     * 参数：
     * {
     *   "exclude_ongoing": true  // 可选，排除常驻通知
     * }
     */
    private ExecutionResult executeClearAll(JSONObject args) throws Exception {
        boolean excludeOngoing = args.optBoolean("exclude_ongoing", true);
        
        StatusBarNotification[] notifications = mNotificationManager.getActiveNotifications();
        int cancelledCount = 0;
        
        for (StatusBarNotification sbn : notifications) {
            // 跳过受保护通知
            if (isProtectedPackage(sbn.getPackageName())) {
                continue;
            }
            
            // 跳过常驻通知
            if (excludeOngoing && sbn.isOngoing()) {
                continue;
            }
            
            mNotificationManager.cancel(sbn.getKey());
            cancelledCount++;
        }
        
        JSONObject result = new JSONObject();
        result.put("cancelled_count", cancelledCount);
        result.put("total_count", notifications.length);
        
        Slog.i(TAG, "All notifications cleared: cancelled=" + cancelledCount + ", total=" + notifications.length);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 回复通知（Inline Reply）
     * 
     * 参数：
     * {
     *   "key": "0|com.example|123|null|10001",
     *   "replyText": "收到"
     * }
     */
    private ExecutionResult executeReply(JSONObject args, ActionRequest request) throws Exception {
        if (!args.has("key") || !args.has("replyText")) {
            return ExecutionResult.failure("Missing parameters: key, replyText");
        }
        
        // Taint 校验：replyText 必须来自 USER 来源
        if (request.argTaints != null) {
            for (com.ainative.policy.TaintMapEntry taint : request.argTaints) {
                if ("replyText".equals(taint.argumentKey) && taint.sourceLevel > 1) {  // > USER
                    Slog.w(TAG, "Reply text from untrusted source: level=" + taint.sourceLevel);
                    return ExecutionResult.failure("Reply text must come from USER source");
                }
            }
        }
        
        String key = args.getString("key");
        String replyText = args.getString("replyText");
        
        // 查找通知
        StatusBarNotification targetNotification = null;
        for (StatusBarNotification sbn : mNotificationManager.getActiveNotifications()) {
            if (sbn.getKey().equals(key)) {
                targetNotification = sbn;
                break;
            }
        }
        
        if (targetNotification == null) {
            return ExecutionResult.failure("Notification not found: " + key);
        }
        
        // 查找 RemoteInput Action
        Notification notification = targetNotification.getNotification();
        Notification.Action replyAction = null;
        android.app.RemoteInput remoteInput = null;
        
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
                    replyAction = action;
                    remoteInput = action.getRemoteInputs()[0];
                    break;
                }
            }
        }
        
        if (replyAction == null || remoteInput == null) {
            return ExecutionResult.failure("Notification does not support inline reply");
        }
        
        // 构建 RemoteInput Intent
        Intent replyIntent = new Intent();
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putCharSequence(remoteInput.getResultKey(), replyText);
        android.app.RemoteInput.addResultsToIntent(new android.app.RemoteInput[]{remoteInput}, replyIntent, bundle);
        
        // 发送回复
        try {
            replyAction.actionIntent.send(mContext, 0, replyIntent);
        } catch (PendingIntent.CanceledException e) {
            return ExecutionResult.failure("Failed to send reply: PendingIntent cancelled");
        }
        
        JSONObject result = new JSONObject();
        result.put("key", key);
        result.put("reply_text", replyText);
        
        Slog.i(TAG, "Notification reply sent: key=" + key);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 执行通知 Action
     * 
     * 参数：
     * {
     *   "key": "0|com.example|123|null|10001",
     *   "actionIndex": 0  // Action 索引
     * }
     */
    private ExecutionResult executeAction(JSONObject args) throws Exception {
        if (!args.has("key") || !args.has("actionIndex")) {
            return ExecutionResult.failure("Missing parameters: key, actionIndex");
        }
        
        String key = args.getString("key");
        int actionIndex = args.getInt("actionIndex");
        
        // 查找通知
        StatusBarNotification targetNotification = null;
        for (StatusBarNotification sbn : mNotificationManager.getActiveNotifications()) {
            if (sbn.getKey().equals(key)) {
                targetNotification = sbn;
                break;
            }
        }
        
        if (targetNotification == null) {
            return ExecutionResult.failure("Notification not found: " + key);
        }
        
        // 获取 Action
        Notification notification = targetNotification.getNotification();
        if (notification.actions == null || actionIndex >= notification.actions.length) {
            return ExecutionResult.failure("Action index out of bounds: " + actionIndex);
        }
        
        Notification.Action action = notification.actions[actionIndex];
        
        // 执行 Action
        try {
            action.actionIntent.send();
        } catch (PendingIntent.CanceledException e) {
            return ExecutionResult.failure("Failed to execute action: PendingIntent cancelled");
        }
        
        JSONObject result = new JSONObject();
        result.put("key", key);
        result.put("action_index", actionIndex);
        result.put("action_title", action.title);
        
        Slog.i(TAG, "Notification action executed: key=" + key + ", action=" + action.title);
        return ExecutionResult.success(result.toString());
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 创建 Agent 通知渠道
     */
    private void createAgentNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            AGENT_CHANNEL_ID,
            AGENT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Notifications generated by AI Agent");
        channel.enableVibration(true);
        channel.setShowBadge(true);
        
        mNotificationManager.createNotificationChannel(channel);
    }
    
    /**
     * 生成通知 ID
     */
    private int generateNotificationId(String actionRequestId) {
        return ("agent_" + actionRequestId).hashCode();
    }
    
    /**
     * 检查是否为受保护包
     */
    private boolean isProtectedPackage(String packageName) {
        for (String protectedPkg : PROTECTED_PACKAGES) {
            if (protectedPkg.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否为受保护通知
     */
    private boolean isProtectedNotification(String key) {
        // 从 key 解析包名（格式：userId|pkg|id|tag|uid）
        String[] parts = key.split("\\|");
        if (parts.length >= 2) {
            String packageName = parts[1];
            return isProtectedPackage(packageName);
        }
        return false;
    }
    
    @Override
    public boolean supportsAction(String actionId) {
        return actionId.startsWith("notification.");
    }
}
