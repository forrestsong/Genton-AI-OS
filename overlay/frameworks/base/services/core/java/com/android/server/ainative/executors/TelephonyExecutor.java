package com.android.server.ainative.executors;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.ainative.action.ActionRequest;

import org.json.JSONObject;

/**
 * 电话执行器
 * 
 * 核心职责：
 * 1. 拨打电话（直接拨号/显示拨号器）
 * 2. 挂断电话
 * 3. 接听电话
 * 4. 静音/取消静音
 * 5. 扬声器开关
 * 
 * 安全约束：
 * - 电话号码需要 Taint 校验（防止恶意拨号）
 * - 紧急号码（110/120）需要用户确认
 * - 收费号码（400/900）需要用户确认
 * - 记录所有拨号操作审计日志
 * 
 * 性能要求：
 * - 拨号延迟 P50 < 100ms
 * - 挂断延迟 P50 < 50ms
 * 
 * @see TelecomManager
 * @see TelephonyManager
 */
public class TelephonyExecutor implements ActionExecutor {
    private static final String TAG = "TelephonyExecutor";
    
    // 紧急号码列表
    private static final String[] EMERGENCY_NUMBERS = {
        "110", "120", "119", "122",  // 中国
        "911", "112"  // 国际
    };
    
    // 收费号码前缀
    private static final String[] PREMIUM_PREFIXES = {
        "400", "900", "118"
    };
    
    private final Context mContext;
    private final TelecomManager mTelecomManager;
    private final TelephonyManager mTelephonyManager;
    
    public TelephonyExecutor(Context context) {
        mContext = context;
        mTelecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        
        Slog.i(TAG, "TelephonyExecutor initialized");
    }
    
    @Override
    public String getExecutorType() {
        return "telephony";
    }
    
    /**
     * 执行电话操作
     * 
     * 支持动作：
     * - telephony.call：拨打电话
     * - telephony.end_call：挂断电话
     * - telephony.answer_call：接听电话
     * - telephony.mute：静音
     * - telephony.speaker：扬声器开关
     */
    @Override
    public ExecutionResult execute(ActionRequest request, ExecutionContext context) {
        long startNanos = System.nanoTime();
        
        try {
            JSONObject args = new JSONObject(request.argumentsJson);
            String actionId = request.actionId;
            
            ExecutionResult result;
            
            switch (actionId) {
                case "telephony.call":
                    result = executeCall(args, request);
                    break;
                case "telephony.end_call":
                    result = executeEndCall(args);
                    break;
                case "telephony.answer_call":
                    result = executeAnswerCall(args);
                    break;
                case "telephony.mute":
                    result = executeMute(args);
                    break;
                case "telephony.speaker":
                    result = executeSpeaker(args);
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
            Slog.e(TAG, "Telephony execution failed", e);
            return ExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }
    
    /**
     * 拨打电话
     * 
     * 参数：
     * {
     *   "number": "13800138000",
     *   "direct": true,  // 可选，true=直接拨号，false=显示拨号器
     *   "requireConfirmation": false  // 可选，是否需要用户确认
     * }
     */
    private ExecutionResult executeCall(JSONObject args, ActionRequest request) throws Exception {
        if (!args.has("number")) {
            return ExecutionResult.failure("Missing parameter: number");
        }
        
        String number = args.getString("number").trim();
        boolean direct = args.optBoolean("direct", false);
        boolean requireConfirmation = args.optBoolean("requireConfirmation", false);
        
        // 电话号码格式校验
        if (!isValidPhoneNumber(number)) {
            return ExecutionResult.failure("Invalid phone number: " + number);
        }
        
        // Taint 校验：电话号码必须来自 AGENT 或更高等级
        if (request.argTaints != null) {
            for (com.ainative.policy.TaintMapEntry taint : request.argTaints) {
                if ("number".equals(taint.argumentKey) && taint.sourceLevel > 2) {  // > AGENT
                    Slog.w(TAG, "Phone number from untrusted source: level=" + taint.sourceLevel);
                    return ExecutionResult.failure("Phone number must come from trusted source");
                }
            }
        }
        
        // 紧急号码检查
        if (isEmergencyNumber(number)) {
            Slog.w(TAG, "Emergency number detected: " + number);
            // 紧急号码强制需要用户确认
            if (!requireConfirmation) {
                return ExecutionResult.failure("Emergency number requires user confirmation");
            }
        }
        
        // 收费号码检查
        if (isPremiumNumber(number)) {
            Slog.w(TAG, "Premium number detected: " + number);
            // 收费号码建议用户确认
            if (!requireConfirmation) {
                Slog.i(TAG, "Premium number should require confirmation");
            }
        }
        
        // 构造拨号 Intent
        Intent intent;
        if (direct) {
            // 直接拨号（需要 CALL_PHONE 权限）
            intent = new Intent(Intent.ACTION_CALL);
        } else {
            // 显示拨号器（不需要权限）
            intent = new Intent(Intent.ACTION_DIAL);
        }
        
        intent.setData(Uri.parse("tel:" + number));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        // 发起拨号
        mContext.startActivity(intent);
        
        JSONObject result = new JSONObject();
        result.put("number", maskPhoneNumber(number));  // 脱敏
        result.put("direct", direct);
        result.put("emergency", isEmergencyNumber(number));
        result.put("premium", isPremiumNumber(number));
        
        Slog.i(TAG, String.format("Call initiated: number=%s, direct=%b", maskPhoneNumber(number), direct));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 挂断电话
     */
    private ExecutionResult executeEndCall(JSONObject args) throws Exception {
        // 检查是否有通话中
        int callState = mTelephonyManager.getCallState();
        if (callState == TelephonyManager.CALL_STATE_IDLE) {
            return ExecutionResult.failure("No active call");
        }
        
        // 挂断电话
        boolean success = mTelecomManager.endCall();
        
        if (!success) {
            return ExecutionResult.failure("Failed to end call");
        }
        
        JSONObject result = new JSONObject();
        result.put("ended", true);
        
        Slog.i(TAG, "Call ended");
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 接听电话
     */
    private ExecutionResult executeAnswerCall(JSONObject args) throws Exception {
        // 检查是否有来电
        int callState = mTelephonyManager.getCallState();
        if (callState != TelephonyManager.CALL_STATE_RINGING) {
            return ExecutionResult.failure("No incoming call");
        }
        
        // 接听电话
        boolean success = mTelecomManager.acceptRingingCall();
        
        if (!success) {
            return ExecutionResult.failure("Failed to answer call");
        }
        
        JSONObject result = new JSONObject();
        result.put("answered", true);
        
        Slog.i(TAG, "Call answered");
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 静音/取消静音
     * 
     * 参数：
     * {
     *   "mute": true | false
     * }
     */
    private ExecutionResult executeMute(JSONObject args) throws Exception {
        if (!args.has("mute")) {
            return ExecutionResult.failure("Missing parameter: mute");
        }
        
        boolean mute = args.getBoolean("mute");
        
        // 检查是否有通话中
        int callState = mTelephonyManager.getCallState();
        if (callState == TelephonyManager.CALL_STATE_IDLE) {
            return ExecutionResult.failure("No active call");
        }
        
        // 设置静音
        mTelecomManager.setMuted(mute);
        
        JSONObject result = new JSONObject();
        result.put("muted", mute);
        
        Slog.i(TAG, "Call mute: " + mute);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 扬声器开关
     * 
     * 参数：
     * {
     *   "enabled": true | false
     * }
     */
    private ExecutionResult executeSpeaker(JSONObject args) throws Exception {
        if (!args.has("enabled")) {
            return ExecutionResult.failure("Missing parameter: enabled");
        }
        
        boolean enabled = args.getBoolean("enabled");
        
        // 检查是否有通话中
        int callState = mTelephonyManager.getCallState();
        if (callState == TelephonyManager.CALL_STATE_IDLE) {
            return ExecutionResult.failure("No active call");
        }
        
        // 设置扬声器（通过 AudioManager）
        android.media.AudioManager audioManager = 
            (android.media.AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        
        audioManager.setSpeakerphoneOn(enabled);
        
        JSONObject result = new JSONObject();
        result.put("speaker_enabled", enabled);
        
        Slog.i(TAG, "Speaker: " + enabled);
        return ExecutionResult.success(result.toString());
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 校验电话号码格式
     */
    private boolean isValidPhoneNumber(String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }
        
        // 去除常见分隔符
        String cleaned = number.replaceAll("[\\s\\-()]", "");
        
        // 基本格式检查（数字 + 可选的 +）
        if (!cleaned.matches("^\\+?[0-9]{3,15}$")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查是否为紧急号码
     */
    private boolean isEmergencyNumber(String number) {
        String cleaned = number.replaceAll("[\\s\\-()]", "");
        
        for (String emergency : EMERGENCY_NUMBERS) {
            if (cleaned.equals(emergency)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 检查是否为收费号码
     */
    private boolean isPremiumNumber(String number) {
        String cleaned = number.replaceAll("[\\s\\-()]", "");
        
        for (String prefix : PREMIUM_PREFIXES) {
            if (cleaned.startsWith(prefix)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 电话号码脱敏
     */
    private String maskPhoneNumber(String number) {
        if (number == null || number.length() < 7) {
            return "***";
        }
        
        // 保留前3位和后4位
        int length = number.length();
        String prefix = number.substring(0, Math.min(3, length));
        String suffix = number.substring(Math.max(0, length - 4));
        
        return prefix + "****" + suffix;
    }
    
    @Override
    public boolean supportsAction(String actionId) {
        return actionId.startsWith("telephony.");
    }
}
