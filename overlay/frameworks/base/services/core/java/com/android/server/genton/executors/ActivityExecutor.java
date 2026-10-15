package com.android.server.genton.executors;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;

import com.genton.action.ActionRequest;
import com.genton.action.ComponentTarget;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Activity 启动执行器
 * 
 * 核心职责：
 * 1. AMS 定向启动（绕过 Chooser/ResolverActivity）
 * 2. 非导出 Activity 白名单表（防止越权启动）
 * 3. Intent 构造校验（action/data/extras）
 * 4. FLAG 安全约束（禁止危险 FLAG）
 * 5. 包可见性校验（防止探测隐藏包）
 * 
 * 安全约束：
 * - 仅启动导出 Activity 或白名单非导出
 * - 禁止 FLAG_GRANT_* / FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 等危险标志
 * - Intent extras 类型白名单（防止 Parcelable 注入）
 * - 目标包必须已安装且可见
 * 
 * 性能要求：
 * - 启动延迟 P50 < 50ms
 * - AMS 直接启动（绕过 Intent 解析）
 * 
 * @see IActivityManager
 */
public class ActivityExecutor implements ActionExecutor {
    private static final String TAG = "ActivityExecutor";
    
    // 危险 Intent FLAG（禁止列表）
    private static final int FORBIDDEN_FLAGS = 
        Intent.FLAG_GRANT_READ_URI_PERMISSION |
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION |
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
        Intent.FLAG_ACTIVITY_FORWARD_RESULT;
    
    // 白名单：允许启动的非导出 Activity
    // 格式：com.package.name/.ActivityName
    private static final Set<String> NON_EXPORTED_WHITELIST = new HashSet<>();
    
    static {
        // TODO: 从配置文件加载
        // 示例：系统设置页面
        NON_EXPORTED_WHITELIST.add("com.android.settings/.wifi.WifiSettings");
        NON_EXPORTED_WHITELIST.add("com.android.settings/.bluetooth.BluetoothSettings");
        NON_EXPORTED_WHITELIST.add("com.android.settings/.DisplaySettings");
    }
    
    private final Context mContext;
    private final IActivityManager mAms;
    private final PackageManager mPm;
    
    public ActivityExecutor(Context context) {
        mContext = context;
        mAms = ActivityManager.getService();
        mPm = context.getPackageManager();
        Slog.i(TAG, "ActivityExecutor initialized");
    }
    
    @Override
    public String getExecutorType() {
        return "activity";
    }
    
    /**
     * 执行 Activity 启动
     * 
     * 参数 schema：
     * {
     *   "action": "ui.launch_activity",
     *   "arguments": {
     *     "component": {"package": "com.example", "activity": ".MainActivity"},
     *     "intentAction": "android.intent.action.VIEW",  // 可选
     *     "data": "https://example.com",  // 可选
     *     "extras": {"key": "value"},  // 可选
     *     "flags": 268435456  // 可选，FLAG_ACTIVITY_NEW_TASK
     *   }
     * }
     */
    @Override
    public ExecutionResult execute(ActionRequest request, ExecutionContext context) {
        long startNanos = System.nanoTime();
        
        try {
            // 1. Taint 参数校验
            if (request.argTaints != null && request.argTaints.length > 0) {
                // 检查 packageName 来源
                for (com.genton.policy.TaintMapEntry taint : request.argTaints) {
                    if ("packageName".equals(taint.argumentKey) || "package".equals(taint.argumentKey)) {
                        // 包名参数必须来自 AGENT 或更高等级
                        if (taint.sourceLevel > 2) {  // > AGENT
                            Slog.w(TAG, "Package name from untrusted source: level=" + taint.sourceLevel);
                            return ExecutionResult.failure("Package name must come from trusted source");
                        }
                    }
                }
            }
            
            // 2. 解析参数
            JSONObject args = new JSONObject(request.argumentsJson);
            
            if (!args.has("component")) {
                return ExecutionResult.failure("Missing required parameter: component");
            }
            
            JSONObject componentJson = args.getJSONObject("component");
            String packageName = componentJson.getString("package");
            String activityName = componentJson.getString("activity");
            
            // 2. 构造 ComponentName
            ComponentName component = new ComponentName(packageName, activityName);
            
            // 3. 校验包可见性
            if (!isPackageVisible(packageName)) {
                Slog.w(TAG, "Package not visible: " + packageName);
                return ExecutionResult.failure("Package not found: " + packageName);
            }
            
            // 4. 查询 ActivityInfo
            ActivityInfo activityInfo = queryActivityInfo(component);
            if (activityInfo == null) {
                Slog.w(TAG, "Activity not found: " + component);
                return ExecutionResult.failure("Activity not found: " + component.flattenToShortString());
            }
            
            // 5. 校验导出状态
            if (!activityInfo.exported) {
                String flattenName = component.flattenToShortString();
                if (!NON_EXPORTED_WHITELIST.contains(flattenName)) {
                    Slog.w(TAG, "Non-exported activity not in whitelist: " + flattenName);
                    return ExecutionResult.failure("Cannot launch non-exported activity: " + flattenName);
                }
            }
            
            // 6. 构造 Intent
            Intent intent = buildIntent(component, args);
            
            // 7. 校验 Intent FLAG
            if ((intent.getFlags() & FORBIDDEN_FLAGS) != 0) {
                Slog.w(TAG, "Forbidden intent flags detected");
                return ExecutionResult.failure("Intent contains forbidden flags");
            }
            
            // 8. AMS 定向启动
            int result = startActivityAsAgent(intent, component);
            
            if (result == ActivityManager.START_SUCCESS) {
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                
                JSONObject resultJson = new JSONObject();
                resultJson.put("component", component.flattenToShortString());
                resultJson.put("elapsed_us", elapsedMicros);
                
                Slog.i(TAG, String.format("Activity launched: %s (%dµs)", 
                    component.flattenToShortString(), elapsedMicros));
                
                return ExecutionResult.success(resultJson.toString());
            } else {
                Slog.w(TAG, "AMS startActivity failed: result=" + result);
                return ExecutionResult.failure("Failed to start activity: result=" + result);
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Activity execution failed", e);
            return ExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }
    
    /**
     * 构造 Intent
     */
    private Intent buildIntent(ComponentName component, JSONObject args) throws Exception {
        Intent intent = new Intent();
        intent.setComponent(component);
        
        // Intent action（可选）
        if (args.has("intentAction")) {
            String action = args.getString("intentAction");
            intent.setAction(action);
        }
        
        // Data URI（可选）
        if (args.has("data")) {
            String data = args.getString("data");
            intent.setData(android.net.Uri.parse(data));
        }
        
        // Extras（可选）
        if (args.has("extras")) {
            JSONObject extras = args.getJSONObject("extras");
            Bundle bundle = new Bundle();
            
            // 遍历 extras（类型白名单）
            java.util.Iterator<String> keys = extras.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = extras.get(key);
                
                // 只允许基本类型（防止 Parcelable 注入）
                if (value instanceof String) {
                    bundle.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    bundle.putInt(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    bundle.putBoolean(key, (Boolean) value);
                } else if (value instanceof Long) {
                    bundle.putLong(key, (Long) value);
                } else if (value instanceof Double) {
                    bundle.putDouble(key, (Double) value);
                } else {
                    Slog.w(TAG, "Unsupported extra type for key: " + key + " (type: " + value.getClass().getName() + ")");
                }
            }
            
            intent.putExtras(bundle);
        }
        
        // Flags（可选）
        if (args.has("flags")) {
            int flags = args.getInt("flags");
            intent.setFlags(flags);
        } else {
            // 默认：NEW_TASK（从非 Activity 上下文启动）
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        
        return intent;
    }
    
    /**
     * 通过 AMS 定向启动 Activity
     */
    private int startActivityAsAgent(Intent intent, ComponentName component) {
        try {
            // 调用 AMS.startActivityAsUser
            // 使用当前用户 ID
            int userId = android.os.UserHandle.myUserId();
            
            // 调用者身份：system_server (privileged)
            String callingPackage = "android";
            
            return mAms.startActivityAsUser(
                null,  // caller IBinder (null = system)
                callingPackage,
                intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                null,  // resultTo
                null,  // resultWho
                0,     // requestCode
                Intent.FLAG_ACTIVITY_NEW_TASK,
                null,  // profilerInfo
                null,  // options
                userId
            );
            
        } catch (RemoteException e) {
            Slog.e(TAG, "AMS startActivity failed", e);
            return ActivityManager.START_INTENT_NOT_RESOLVED;
        }
    }
    
    /**
     * 查询 ActivityInfo
     */
    private ActivityInfo queryActivityInfo(ComponentName component) {
        try {
            return mPm.getActivityInfo(component, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
    
    /**
     * 校验包可见性
     */
    private boolean isPackageVisible(String packageName) {
        try {
            mPm.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
    
    @Override
    public boolean supportsAction(String actionId) {
        return "ui.launch_activity".equals(actionId) ||
               "ui.open_app".equals(actionId);
    }
}
