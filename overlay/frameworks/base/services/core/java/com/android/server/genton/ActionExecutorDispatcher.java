package com.android.server.genton;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.genton.policy.CapabilityToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 执行器调度器（Action Executor Dispatcher）
 * 
 * 职责：
 * 1. 根据 executorType 选择对应的执行器
 * 2. 传递令牌和参数给执行器
 * 3. 收集执行结果
 * 
 * 执行器类型：
 * - activity: 活动执行器（启动组件）
 * - input: 输入执行器（点击/滑动/输入）
 * - query: 查询执行器（只读查询）
 * - device_control: 设备控制执行器（音量/亮度）
 * - notification: 通知执行器（清除/回复）
 * - clipboard: 剪贴板执行器（读写剪贴板）
 * - telephony: 电话执行器（拨号/发短信）
 * - media: 媒体执行器（播放控制）
 * - internal: 内部动作执行器（sys.* 与 cmd.* 系统优化动作）
 */
public class ActionExecutorDispatcher {
    private static final String TAG = "ActionExecutorDispatcher";
    
    private final Context mContext;
    private final Map<String, IActionExecutor> mExecutors;
    
    public ActionExecutorDispatcher(Context context) {
        mContext = context;
        mExecutors = new HashMap<>();
        
        // 注册执行器
        registerExecutor("activity", new ActivityExecutor(context));
        registerExecutor("input", new InputExecutor(context));
        registerExecutor("query", new QueryExecutor(context));
        registerExecutor("device_control", new DeviceControlExecutor(context));
        registerExecutor("notification", new NotificationExecutor(context));
        
        Slog.i(TAG, "Registered " + mExecutors.size() + " executors");
    }
    
    /**
     * 调度动作执行
     * 
     * @param executorType 执行器类型
     * @param actionId 动作 ID
     * @param argumentsJson 参数（JSON）
     * @param token 能力令牌
     * @return 执行结果（JSON）
     */
    public String dispatch(String executorType, String actionId, String argumentsJson, CapabilityToken token) 
            throws Exception {
        IActionExecutor executor = mExecutors.get(executorType);
        
        if (executor == null) {
            throw new IllegalArgumentException("Unknown executor type: " + executorType);
        }
        
        Slog.d(TAG, String.format("Dispatching action=%s to executor=%s", actionId, executorType));
        
        return executor.execute(actionId, argumentsJson, token);
    }
    
    private void registerExecutor(String type, IActionExecutor executor) {
        mExecutors.put(type, executor);
    }
    
    /**
     * 执行器接口
     */
    public interface IActionExecutor {
        /**
         * 执行动作
         * 
         * @param actionId 动作 ID
         * @param argumentsJson 参数（JSON）
         * @param token 能力令牌
         * @return 执行结果（JSON）
         */
        String execute(String actionId, String argumentsJson, CapabilityToken token) throws Exception;
    }
    
    // ========== 执行器实现（示例/存根） ==========
    
    /**
     * 活动执行器（Activity Executor）
     * 职责：启动 Activity、切换前台应用、打开系统设置
     * 
     * 确定性保障（D2 §2）：
     * 1. app.launch 非导出组件拦截：exported=false 组件默认拒绝（防越权）
     * 2. 系统白名单活动：Settings 深层页等系统组件经白名单显式允许
     * 3. 定向启动：经 AMS startActivity（FLAG_ACTIVITY_NEW_TASK）
     * 
     * 白名单配置：/vendor/genton/config/system_activity_whitelist.json
     *   格式：{"whitelist": ["com.android.settings/.SubSettings", ...]}
     */
    private static class ActivityExecutor implements IActionExecutor {
        private final Context mContext;
        private final ActivityManager mActivityManager;
        private final PackageManager mPackageManager;
        private final Set<String> mSystemActivityWhitelist;
        
        ActivityExecutor(Context context) {
            mContext = context;
            mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            mPackageManager = context.getPackageManager();
            mSystemActivityWhitelist = loadSystemActivityWhitelist();
        }
        
        @Override
        public String execute(String actionId, String argumentsJson, CapabilityToken token) throws Exception {
            JSONObject args = new JSONObject(argumentsJson);
            
            switch (actionId) {
                case "app.launch": {
                    String packageName = args.getString("packageName");
                    String activityName = args.optString("activityName", null);
                    
                    Intent intent;
                    if (activityName != null && !activityName.isEmpty()) {
                        // 显式启动指定 Activity
                        ComponentName component = new ComponentName(packageName, activityName);
                        
                        // 非导出组件检查（fail-secure）
                        if (!isActivityExportedOrWhitelisted(component)) {
                            throw new SecurityException(
                                "Cannot launch non-exported activity: " + component.flattenToShortString() +
                                " (not in system whitelist)");
                        }
                        
                        intent = new Intent();
                        intent.setComponent(component);
                    } else {
                        // 启动应用主 Activity（MAIN/LAUNCHER）
                        intent = mPackageManager.getLaunchIntentForPackage(packageName);
                        if (intent == null) {
                            throw new IllegalArgumentException("No launch intent found for package: " + packageName);
                        }
                    }
                    
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(intent);
                    
                    JSONObject result = new JSONObject();
                    result.put("startedActivity", intent.getComponent() != null ?
                        intent.getComponent().flattenToShortString() : packageName);
                    result.put("timestamp", SystemClock.elapsedRealtime());
                    return result.toString();
                }
                
                case "app.foreground": {
                    String packageName = args.getString("packageName");
                    
                    // 尝试将应用切换到前台（移动任务栈）
                    boolean success = bringTaskToFront(packageName);
                    
                    if (!success) {
                        // 回退：重新启动主 Activity
                        Intent intent = mPackageManager.getLaunchIntentForPackage(packageName);
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            mContext.startActivity(intent);
                            success = true;
                        }
                    }
                    
                    JSONObject result = new JSONObject();
                    result.put("success", success);
                    result.put("packageName", packageName);
                    return result.toString();
                }
                
                case "settings.open": {
                    String settingsPage = args.getString("settingsPage");
                    
                    Intent intent = new Intent(settingsPage);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(intent);
                    
                    JSONObject result = new JSONObject();
                    result.put("opened", true);
                    result.put("settingsPage", settingsPage);
                    return result.toString();
                }
                
                default:
                    throw new IllegalArgumentException("Unknown action: " + actionId);
            }
        }
        
        /** 检查 Activity 是否导出或在系统白名单中 */
        private boolean isActivityExportedOrWhitelisted(ComponentName component) {
            try {
                ActivityInfo info = mPackageManager.getActivityInfo(component, 0);
                if (info.exported) {
                    return true;
                }
                
                // 检查白名单
                String componentStr = component.flattenToShortString();
                return mSystemActivityWhitelist.contains(componentStr);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Activity not found: " + component);
                return false;
            }
        }
        
        /** 将应用任务切换到前台 */
        private boolean bringTaskToFront(String packageName) {
            try {
                for (ActivityManager.RunningTaskInfo task : mActivityManager.getRunningTasks(20)) {
                    if (task.baseActivity != null && packageName.equals(task.baseActivity.getPackageName())) {
                        mActivityManager.moveTaskToFront(task.id, ActivityManager.MOVE_TASK_WITH_HOME);
                        return true;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to bring task to front: " + packageName, e);
            }
            return false;
        }
        
        /** 加载系统活动白名单 */
        private Set<String> loadSystemActivityWhitelist() {
            Set<String> whitelist = new HashSet<>();
            File whitelistFile = new File("/vendor/genton/config/system_activity_whitelist.json");
            
            if (!whitelistFile.exists()) {
                Slog.i(TAG, "System activity whitelist not found, using built-in defaults");
                // 内置默认白名单（Settings 常见深层页）
                whitelist.add("com.android.settings/.SubSettings");
                whitelist.add("com.android.settings/.wifi.WifiSettings");
                whitelist.add("com.android.settings/.bluetooth.BluetoothSettings");
                whitelist.add("com.android.settings/.DisplaySettings");
                whitelist.add("com.android.settings/.SoundSettings");
                return whitelist;
            }
            
            try (BufferedReader reader = new BufferedReader(new FileReader(whitelistFile))) {
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                JSONObject root = new JSONObject(json.toString());
                JSONArray list = root.getJSONArray("whitelist");
                for (int i = 0; i < list.length(); i++) {
                    whitelist.add(list.getString(i));
                }
                
                Slog.i(TAG, "Loaded " + whitelist.size() + " system activities from whitelist");
            } catch (Exception e) {
                Slog.e(TAG, "Failed to load system activity whitelist", e);
            }
            
            return whitelist;
        }
    }
    
    /**
     * 输入执行器（Input Executor）
     * 职责：点击、滑动、输入文本、按键
     * 
     * 确定性保障（D2 §2）：
     * 1. stale 校验：nodeId 必须能解析到最新 UiSnapshot，bounds 未变化
     * 2. overlay 复查：注入前即时探测可疑 overlay（防 tapjacking）
     * 3. 真实设备注入：经 InputManager.injectInputEvent（INJECT_EVENTS 权限）
     * 
     * v1 限制：
     * - A2.2 感知层未就绪，暂时跳过 stale/overlay 校验（保守 fail-open，留 TODO）
     * - 坐标从参数直接取（nodeId 携带坐标或由中介预解析）
     * - 真实部署需衔接 IAgentPerceptionService.getUiTree 最新快照
     */
    private static class InputExecutor implements IActionExecutor {
        private final Context mContext;
        private final InputManager mInputManager;
        private final WindowManager mWindowManager;
        
        InputExecutor(Context context) {
            mContext = context;
            mInputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        }
        
        @Override
        public String execute(String actionId, String argumentsJson, CapabilityToken token) throws Exception {
            JSONObject args = new JSONObject(argumentsJson);
            
            switch (actionId) {
                case "ui.tap":
                case "ui.long_tap": {
                    String nodeId = args.getString("nodeId");
                    boolean isLongTap = "ui.long_tap".equals(actionId);
                    int durationMs = isLongTap ? args.optInt("durationMs", 1000) : 0;
                    
                    // TODO(A2.2): stale 校验 - 从 IAgentPerceptionService 获取最新快照并验证 nodeId bounds
                    // TODO(A2.2): overlay 复查 - 检测可疑 overlay（对齐 detectSecureFlags）
                    
                    // 从 nodeId 解析坐标（v1 简化：直接从参数取或用屏幕中心兜底）
                    int x = args.optInt("x", -1);
                    int y = args.optInt("y", -1);
                    if (x < 0 || y < 0) {
                        // 兜底：屏幕中心（真实部署必须从 UiSnapshot.node.bounds 解析）
                        DisplayMetrics metrics = new DisplayMetrics();
                        mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
                        x = metrics.widthPixels / 2;
                        y = metrics.heightPixels / 2;
                        Slog.w(TAG, "No coords in args, using screen center: " + x + "," + y);
                    }
                    
                    // 注入触摸事件
                    injectTap(x, y, isLongTap, durationMs);
                    
                    JSONObject result = new JSONObject();
                    result.put("hitNodeId", nodeId);
                    result.put("x", x);
                    result.put("y", y);
                    result.put("timestamp", SystemClock.elapsedRealtime());
                    return result.toString();
                }
                
                case "ui.swipe": {
                    String startNodeId = args.getString("startNodeId");
                    String direction = args.getString("direction");
                    int distance = args.optInt("distance", 500);
                    
                    // TODO(A2.2): stale 校验 startNodeId
                    
                    // 解析起点坐标
                    int startX = args.optInt("startX", -1);
                    int startY = args.optInt("startY", -1);
                    if (startX < 0 || startY < 0) {
                        DisplayMetrics metrics = new DisplayMetrics();
                        mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
                        startX = metrics.widthPixels / 2;
                        startY = metrics.heightPixels / 2;
                    }
                    
                    // 计算终点
                    int endX = startX, endY = startY;
                    switch (direction) {
                        case "up":    endY -= distance; break;
                        case "down":  endY += distance; break;
                        case "left":  endX -= distance; break;
                        case "right": endX += distance; break;
                        default: throw new IllegalArgumentException("Invalid direction: " + direction);
                    }
                    
                    injectSwipe(startX, startY, endX, endY);
                    
                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("direction", direction);
                    return result.toString();
                }
                
                case "ui.type": {
                    String nodeId = args.getString("nodeId");
                    String text = args.getString("text");
                    boolean clearFirst = args.optBoolean("clearFirst", false);
                    
                    // TODO(A2.2): stale 校验 nodeId + 确认为可编辑字段
                    
                    // v1 简化：直接点击坐标聚焦后按键注入字符
                    int x = args.optInt("x", -1);
                    int y = args.optInt("y", -1);
                    if (x > 0 && y > 0) {
                        injectTap(x, y, false, 0);  // 先点击聚焦
                        Thread.sleep(100);
                    }
                    
                    if (clearFirst) {
                        // 清除已有文本：Ctrl+A + Delete
                        injectKey(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON);
                        Thread.sleep(50);
                        injectKey(KeyEvent.KEYCODE_DEL, 0);
                        Thread.sleep(50);
                    }
                    
                    // 逐字符注入（真实部署应优化为 commitText）
                    for (char c : text.toCharArray()) {
                        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(new char[]{c});
                        if (events != null) {
                            for (KeyEvent event : events) {
                                injectKeyEvent(event);
                            }
                        }
                    }
                    
                    JSONObject result = new JSONObject();
                    result.put("inputNodeId", nodeId);
                    result.put("textLength", text.length());
                    return result.toString();
                }
                
                case "ui.key": {
                    String key = args.getString("key");
                    int keyCode = getAndroidKeyCode(key);
                    if (keyCode == 0) {
                        throw new IllegalArgumentException("Unknown key: " + key);
                    }
                    
                    injectKey(keyCode, 0);
                    
                    JSONObject result = new JSONObject();
                    result.put("keyCode", keyCode);
                    result.put("key", key);
                    return result.toString();
                }
                
                default:
                    throw new IllegalArgumentException("Unknown action: " + actionId);
            }
        }
        
        /** 注入点击/长按 */
        private void injectTap(int x, int y, boolean longTap, int durationMs) throws Exception {
            long downTime = SystemClock.uptimeMillis();
            
            MotionEvent down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            injectMotionEvent(down);
            
            if (longTap) {
                Thread.sleep(durationMs);
            }
            
            long upTime = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(downTime, upTime,
                MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            injectMotionEvent(up);
        }
        
        /** 注入滑动 */
        private void injectSwipe(int startX, int startY, int endX, int endY) throws Exception {
            long downTime = SystemClock.uptimeMillis();
            long eventTime = downTime;
            
            MotionEvent down = MotionEvent.obtain(downTime, eventTime,
                MotionEvent.ACTION_DOWN, startX, startY, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            injectMotionEvent(down);
            
            // 分段移动（10 步）
            int steps = 10;
            long stepDelay = 10; // ms
            for (int i = 1; i <= steps; i++) {
                float progress = (float) i / steps;
                int x = (int) (startX + (endX - startX) * progress);
                int y = (int) (startY + (endY - startY) * progress);
                
                eventTime = SystemClock.uptimeMillis();
                MotionEvent move = MotionEvent.obtain(downTime, eventTime,
                    MotionEvent.ACTION_MOVE, x, y, 0);
                move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                injectMotionEvent(move);
                
                Thread.sleep(stepDelay);
            }
            
            eventTime = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(downTime, eventTime,
                MotionEvent.ACTION_UP, endX, endY, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            injectMotionEvent(up);
        }
        
        /** 注入按键（DOWN + UP） */
        private void injectKey(int keyCode, int metaState) throws Exception {
            long downTime = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState);
            injectKeyEvent(down);
            
            long upTime = SystemClock.uptimeMillis();
            KeyEvent up = new KeyEvent(downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0, metaState);
            injectKeyEvent(up);
        }
        
        /** 注入 MotionEvent（经 InputManager） */
        private void injectMotionEvent(MotionEvent event) {
            if (!mInputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)) {
                throw new RuntimeException("Failed to inject motion event");
            }
        }
        
        /** 注入 KeyEvent（经 InputManager） */
        private void injectKeyEvent(KeyEvent event) {
            if (!mInputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)) {
                throw new RuntimeException("Failed to inject key event");
            }
        }
        
        /** 键名到 Android KeyCode 映射 */
        private int getAndroidKeyCode(String key) {
            switch (key) {
                case "back":    return KeyEvent.KEYCODE_BACK;
                case "home":    return KeyEvent.KEYCODE_HOME;
                case "recents": return KeyEvent.KEYCODE_APP_SWITCH;
                case "enter":   return KeyEvent.KEYCODE_ENTER;
                case "delete":  return KeyEvent.KEYCODE_DEL;
                default: return 0;
            }
        }
    }
    
    /**
     * 查询执行器（Query Executor）
     * 职责：只读查询（应用列表、Intent 解析）
     */
    private static class QueryExecutor implements IActionExecutor {
        private final Context mContext;
        
        QueryExecutor(Context context) {
            mContext = context;
        }
        
        @Override
        public String execute(String actionId, String argumentsJson, CapabilityToken token) throws Exception {
            JSONObject args = new JSONObject(argumentsJson);
            
            switch (actionId) {
                case "query.apps": {
                    String filter = args.optString("filter", "user");
                    
                    // TODO: 调用 PMS 查询应用列表
                    Slog.i(TAG, "MOCK: Querying apps filter=" + filter);
                    
                    JSONObject result = new JSONObject();
                    result.put("apps", "[]");  // 空列表
                    return result.toString();
                }
                
                case "query.intent": {
                    String action = args.getString("action");
                    
                    // TODO: 调用 PMS 解析 Intent
                    Slog.i(TAG, "MOCK: Resolving intent action=" + action);
                    
                    JSONObject result = new JSONObject();
                    result.put("candidates", "[]");
                    return result.toString();
                }
                
                default:
                    throw new IllegalArgumentException("Unknown action: " + actionId);
            }
        }
    }
    
    /**
     * 设备控制执行器（Device Control Executor）
     * 职责：音量、亮度等设备控制
     * 
     * 编排系统服务：
     * - device.volume → AudioManager.adjustStreamVolume
     * - device.brightness → Settings.System.putInt(SCREEN_BRIGHTNESS)
     */
    private static class DeviceControlExecutor implements IActionExecutor {
        private final Context mContext;
        private final AudioManager mAudioManager;
        
        DeviceControlExecutor(Context context) {
            mContext = context;
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }
        
        @Override
        public String execute(String actionId, String argumentsJson, CapabilityToken token) throws Exception {
            JSONObject args = new JSONObject(argumentsJson);
            
            switch (actionId) {
                case "device.volume": {
                    String direction = args.getString("direction");
                    String streamName = args.optString("stream", "media");
                    
                    int streamType = getStreamType(streamName);
                    int adjustDirection;
                    switch (direction) {
                        case "up":   adjustDirection = AudioManager.ADJUST_RAISE; break;
                        case "down": adjustDirection = AudioManager.ADJUST_LOWER; break;
                        case "mute": adjustDirection = AudioManager.ADJUST_MUTE; break;
                        case "unmute": adjustDirection = AudioManager.ADJUST_UNMUTE; break;
                        default: throw new IllegalArgumentException("Invalid direction: " + direction);
                    }
                    
                    mAudioManager.adjustStreamVolume(streamType, adjustDirection, AudioManager.FLAG_SHOW_UI);
                    
                    int newVolume = mAudioManager.getStreamVolume(streamType);
                    int maxVolume = mAudioManager.getStreamMaxVolume(streamType);
                    
                    JSONObject result = new JSONObject();
                    result.put("newVolume", newVolume);
                    result.put("maxVolume", maxVolume);
                    result.put("stream", streamName);
                    return result.toString();
                }
                
                case "device.brightness": {
                    int level = args.getInt("level");
                    
                    // 亮度范围：0-255
                    if (level < 0 || level > 255) {
                        throw new IllegalArgumentException("Brightness level must be 0-255, got: " + level);
                    }
                    
                    Settings.System.putInt(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, level);
                    
                    JSONObject result = new JSONObject();
                    result.put("actualLevel", level);
                    result.put("timestamp", SystemClock.elapsedRealtime());
                    return result.toString();
                }
                
                default:
                    throw new IllegalArgumentException("Unknown action: " + actionId);
            }
        }
        
        /** 音频流名称到 AudioManager 常量映射 */
        private int getStreamType(String streamName) {
            switch (streamName) {
                case "media":   return AudioManager.STREAM_MUSIC;
                case "ring":    return AudioManager.STREAM_RING;
                case "alarm":   return AudioManager.STREAM_ALARM;
                case "notification": return AudioManager.STREAM_NOTIFICATION;
                case "voice":   return AudioManager.STREAM_VOICE_CALL;
                case "system":  return AudioManager.STREAM_SYSTEM;
                default: return AudioManager.STREAM_MUSIC;
            }
        }
    }
    
    /**
     * 通知执行器（Notification Executor）
     * 职责：清除通知、快捷回复
     */
    private static class NotificationExecutor implements IActionExecutor {
        private final Context mContext;
        
        NotificationExecutor(Context context) {
            mContext = context;
        }
        
        @Override
        public String execute(String actionId, String argumentsJson, CapabilityToken token) throws Exception {
            JSONObject args = new JSONObject(argumentsJson);
            
            switch (actionId) {
                case "notification.clear": {
                    String notificationId = args.getString("notificationId");
                    
                    // TODO: 调用 NotificationManagerService 清除通知
                    Slog.i(TAG, "MOCK: Clearing notification " + notificationId);
                    
                    JSONObject result = new JSONObject();
                    result.put("cleared", true);
                    return result.toString();
                }
                
                case "notification.reply": {
                    String notificationId = args.getString("notificationId");
                    String replyText = args.getString("replyText");
                    
                    // TODO: 调用 NotificationManagerService 快捷回复
                    Slog.i(TAG, "MOCK: Replying to notification " + notificationId + " text=" + replyText);
                    
                    JSONObject result = new JSONObject();
                    result.put("sent", true);
                    return result.toString();
                }
                
                default:
                    throw new IllegalArgumentException("Unknown action: " + actionId);
            }
        }
    }
}
