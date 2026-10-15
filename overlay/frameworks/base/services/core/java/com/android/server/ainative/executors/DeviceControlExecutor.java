package com.android.server.ainative.executors;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Slog;

import com.ainative.action.ActionRequest;

import org.json.JSONObject;

/**
 * 设备控制执行器
 * 
 * 核心职责：
 * 1. 音量控制（媒体/铃声/通知/闹钟）
 * 2. 亮度调节（手动/自动）
 * 3. 网络开关（Wi-Fi/数据/飞行模式）
 * 4. 勿扰模式（DND）
 * 5. 蓝牙/定位开关
 * 6. 屏幕控制（锁屏/唤醒）
 * 
 * 安全约束：
 * - 修改系统设置需要 WRITE_SETTINGS / WRITE_SECURE_SETTINGS 权限
 * - 部分操作需要策略引擎授权（如飞行模式）
 * - 敏感操作（如定位）需要用户确认
 * - 值域边界检查（音量/亮度范围）
 * 
 * 性能要求：
 * - 设置延迟 P50 < 20ms
 * - 状态查询 < 5ms
 * 
 * @see AudioManager
 * @see Settings
 */
public class DeviceControlExecutor implements ActionExecutor {
    private static final String TAG = "DeviceControlExecutor";
    
    private final Context mContext;
    private final AudioManager mAudioManager;
    private final NotificationManager mNotificationManager;
    private final WifiManager mWifiManager;
    private final ConnectivityManager mConnectivityManager;
    private final PowerManager mPowerManager;
    
    public DeviceControlExecutor(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mNotificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        Slog.i(TAG, "DeviceControlExecutor initialized");
    }
    
    @Override
    public String getExecutorType() {
        return "device_control";
    }
    
    /**
     * 执行设备控制
     * 
     * 支持动作：
     * - device.set_volume
     * - device.set_brightness
     * - device.set_wifi
     * - device.set_dnd
     * - device.set_bluetooth
     * - device.set_location
     * - device.lock_screen
     */
    @Override
    public ExecutionResult execute(ActionRequest request, ExecutionContext context) {
        long startNanos = System.nanoTime();
        
        try {
            JSONObject args = new JSONObject(request.argumentsJson);
            String actionId = request.actionId;
            
            ExecutionResult result;
            
            switch (actionId) {
                case "device.set_volume":
                    result = executeSetVolume(args);
                    break;
                case "device.set_brightness":
                    result = executeSetBrightness(args);
                    break;
                case "device.set_wifi":
                    result = executeSetWifi(args);
                    break;
                case "device.set_dnd":
                    result = executeSetDnd(args);
                    break;
                case "device.set_bluetooth":
                    result = executeSetBluetooth(args);
                    break;
                case "device.set_location":
                    result = executeSetLocation(args);
                    break;
                case "device.lock_screen":
                    result = executeLockScreen(args);
                    break;
                case "device.set_airplane_mode":
                    result = executeSetAirplaneMode(args);
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
            Slog.e(TAG, "Device control execution failed", e);
            return ExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }
    
    /**
     * 设置音量
     * 
     * 参数：
     * {
     *   "stream": "media" | "ring" | "notification" | "alarm" | "call",
     *   "level": 10,  // 0-max（可选，与 delta 二选一）
     *   "delta": 1    // 增量（可选）
     * }
     */
    private ExecutionResult executeSetVolume(JSONObject args) throws Exception {
        if (!args.has("stream")) {
            return ExecutionResult.failure("Missing parameter: stream");
        }
        
        String streamName = args.getString("stream");
        int streamType = parseStreamType(streamName);
        
        if (streamType == -1) {
            return ExecutionResult.failure("Invalid stream type: " + streamName);
        }
        
        int maxVolume = mAudioManager.getStreamMaxVolume(streamType);
        int currentVolume = mAudioManager.getStreamVolume(streamType);
        int newVolume;
        
        if (args.has("level")) {
            // 绝对值设置
            newVolume = args.getInt("level");
        } else if (args.has("delta")) {
            // 增量调节
            int delta = args.getInt("delta");
            newVolume = currentVolume + delta;
        } else {
            return ExecutionResult.failure("Missing parameter: level or delta");
        }
        
        // 边界检查
        if (newVolume < 0) {
            newVolume = 0;
        } else if (newVolume > maxVolume) {
            newVolume = maxVolume;
        }
        
        // 设置音量
        mAudioManager.setStreamVolume(streamType, newVolume, 0);  // 0 = 无 UI 提示
        
        JSONObject result = new JSONObject();
        result.put("stream", streamName);
        result.put("previous_level", currentVolume);
        result.put("new_level", newVolume);
        result.put("max_level", maxVolume);
        
        Slog.i(TAG, String.format("Volume set: %s %d→%d", streamName, currentVolume, newVolume));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 设置亮度
     * 
     * 参数：
     * {
     *   "level": 128,  // 0-255（可选，与 delta/auto 三选一）
     *   "delta": 20,   // 增量（可选）
     *   "auto": true   // 自动亮度（可选）
     * }
     */
    private ExecutionResult executeSetBrightness(JSONObject args) throws Exception {
        if (args.has("auto")) {
            // 设置自动亮度
            boolean auto = args.getBoolean("auto");
            int mode = auto ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                            : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
            
            Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, mode);
            
            JSONObject result = new JSONObject();
            result.put("auto_brightness", auto);
            
            Slog.i(TAG, "Brightness mode set: auto=" + auto);
            return ExecutionResult.success(result.toString());
        }
        
        // 手动亮度设置
        int currentBrightness = Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS, 128);
        
        int newBrightness;
        
        if (args.has("level")) {
            newBrightness = args.getInt("level");
        } else if (args.has("delta")) {
            int delta = args.getInt("delta");
            newBrightness = currentBrightness + delta;
        } else {
            return ExecutionResult.failure("Missing parameter: level, delta, or auto");
        }
        
        // 边界检查（0-255）
        if (newBrightness < 0) {
            newBrightness = 0;
        } else if (newBrightness > 255) {
            newBrightness = 255;
        }
        
        // 设置亮度
        Settings.System.putInt(mContext.getContentResolver(),
            Settings.System.SCREEN_BRIGHTNESS, newBrightness);
        
        JSONObject result = new JSONObject();
        result.put("previous_level", currentBrightness);
        result.put("new_level", newBrightness);
        
        Slog.i(TAG, String.format("Brightness set: %d→%d", currentBrightness, newBrightness));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 设置 Wi-Fi
     * 
     * 参数：
     * {
     *   "enabled": true | false
     * }
     */
    private ExecutionResult executeSetWifi(JSONObject args) throws Exception {
        if (!args.has("enabled")) {
            return ExecutionResult.failure("Missing parameter: enabled");
        }
        
        boolean enabled = args.getBoolean("enabled");
        boolean currentState = mWifiManager.isWifiEnabled();
        
        if (enabled == currentState) {
            JSONObject result = new JSONObject();
            result.put("wifi_enabled", enabled);
            result.put("changed", false);
            return ExecutionResult.success(result.toString());
        }
        
        // 切换 Wi-Fi 状态
        boolean success = mWifiManager.setWifiEnabled(enabled);
        
        if (!success) {
            return ExecutionResult.failure("Failed to set Wi-Fi state");
        }
        
        JSONObject result = new JSONObject();
        result.put("previous_state", currentState);
        result.put("new_state", enabled);
        result.put("changed", true);
        
        Slog.i(TAG, "Wi-Fi set: " + enabled);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 设置勿扰模式
     * 
     * 参数：
     * {
     *   "mode": "off" | "priority" | "alarms" | "total_silence"
     * }
     */
    private ExecutionResult executeSetDnd(JSONObject args) throws Exception {
        if (!args.has("mode")) {
            return ExecutionResult.failure("Missing parameter: mode");
        }
        
        String modeName = args.getString("mode");
        int interruptionFilter;
        
        switch (modeName) {
            case "off":
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL;
                break;
            case "priority":
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY;
                break;
            case "alarms":
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALARMS;
                break;
            case "total_silence":
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE;
                break;
            default:
                return ExecutionResult.failure("Invalid DND mode: " + modeName);
        }
        
        int currentFilter = mNotificationManager.getCurrentInterruptionFilter();
        
        // 设置勿扰模式
        mNotificationManager.setInterruptionFilter(interruptionFilter);
        
        JSONObject result = new JSONObject();
        result.put("previous_mode", filterToString(currentFilter));
        result.put("new_mode", modeName);
        
        Slog.i(TAG, "DND mode set: " + modeName);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 设置蓝牙
     * 
     * 参数：
     * {
     *   "enabled": true | false
     * }
     */
    private ExecutionResult executeSetBluetooth(JSONObject args) throws Exception {
        if (!args.has("enabled")) {
            return ExecutionResult.failure("Missing parameter: enabled");
        }
        
        boolean enabled = args.getBoolean("enabled");
        
        // 通过 BluetoothAdapter 控制
        android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        
        if (adapter == null) {
            return ExecutionResult.failure("Bluetooth not supported");
        }
        
        boolean currentState = adapter.isEnabled();
        
        if (enabled == currentState) {
            JSONObject result = new JSONObject();
            result.put("bluetooth_enabled", enabled);
            result.put("changed", false);
            return ExecutionResult.success(result.toString());
        }
        
        boolean success;
        if (enabled) {
            success = adapter.enable();
        } else {
            success = adapter.disable();
        }
        
        if (!success) {
            return ExecutionResult.failure("Failed to set Bluetooth state");
        }
        
        JSONObject result = new JSONObject();
        result.put("previous_state", currentState);
        result.put("new_state", enabled);
        result.put("changed", true);
        
        Slog.i(TAG, "Bluetooth set: " + enabled);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 设置定位
     * 
     * 参数：
     * {
     *   "enabled": true | false
     * }
     */
    private ExecutionResult executeSetLocation(JSONObject args) throws Exception {
        if (!args.has("enabled")) {
            return ExecutionResult.failure("Missing parameter: enabled");
        }
        
        boolean enabled = args.getBoolean("enabled");
        
        // 定位开关（需要 WRITE_SECURE_SETTINGS）
        int mode = enabled ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                           : Settings.Secure.LOCATION_MODE_OFF;
        
        Settings.Secure.putInt(mContext.getContentResolver(),
            Settings.Secure.LOCATION_MODE, mode);
        
        JSONObject result = new JSONObject();
        result.put("location_enabled", enabled);
        
        Slog.i(TAG, "Location set: " + enabled);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 锁定屏幕
     */
    private ExecutionResult executeLockScreen(JSONObject args) throws Exception {
        // 通过 PowerManager 锁屏
        mPowerManager.goToSleep(android.os.SystemClock.uptimeMillis());
        
        JSONObject result = new JSONObject();
        result.put("screen_locked", true);
        
        Slog.i(TAG, "Screen locked");
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 设置飞行模式
     * 
     * 参数：
     * {
     *   "enabled": true | false
     * }
     */
    private ExecutionResult executeSetAirplaneMode(JSONObject args) throws Exception {
        if (!args.has("enabled")) {
            return ExecutionResult.failure("Missing parameter: enabled");
        }
        
        boolean enabled = args.getBoolean("enabled");
        
        // 飞行模式（全局设置）
        Settings.Global.putInt(mContext.getContentResolver(),
            Settings.Global.AIRPLANE_MODE_ON, enabled ? 1 : 0);
        
        // 广播飞行模式变更
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcastAsUser(intent, android.os.UserHandle.ALL);
        
        JSONObject result = new JSONObject();
        result.put("airplane_mode", enabled);
        
        Slog.i(TAG, "Airplane mode set: " + enabled);
        return ExecutionResult.success(result.toString());
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 解析音频流类型
     */
    private int parseStreamType(String streamName) {
        switch (streamName) {
            case "media":
                return AudioManager.STREAM_MUSIC;
            case "ring":
                return AudioManager.STREAM_RING;
            case "notification":
                return AudioManager.STREAM_NOTIFICATION;
            case "alarm":
                return AudioManager.STREAM_ALARM;
            case "call":
                return AudioManager.STREAM_VOICE_CALL;
            default:
                return -1;
        }
    }
    
    /**
     * 勿扰模式转字符串
     */
    private String filterToString(int filter) {
        switch (filter) {
            case NotificationManager.INTERRUPTION_FILTER_ALL:
                return "off";
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY:
                return "priority";
            case NotificationManager.INTERRUPTION_FILTER_ALARMS:
                return "alarms";
            case NotificationManager.INTERRUPTION_FILTER_NONE:
                return "total_silence";
            default:
                return "unknown";
        }
    }
    
    @Override
    public boolean supportsAction(String actionId) {
        return actionId.startsWith("device.");
    }
}
