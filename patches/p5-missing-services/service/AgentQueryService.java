// Genton (AI Native OS) - 只读规划查询服务（P5 补齐）
//
// 依据 轨道A-执行层AIDL与动作契约草案.md（D2）：
//   IAgentQueryService 提供"只读规划查询"——列出可启动应用、解析意图到具体组件，
//   供 Agent 规划阶段使用（不产生副作用，与 IAgentActionService 的写路径分离）。
package com.android.server.genton;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Slog;

import com.genton.action.ActionInfo;
import com.genton.action.AppInfo;
import com.genton.action.ComponentTarget;
import com.genton.action.IAgentQueryService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 只读规划查询服务 */
public class AgentQueryService extends IAgentQueryService.Stub {

    private static final String TAG = "AgentQueryService";

    /** 返回结果上限（防止规划期大列表拖垮 Agent 上下文） */
    private static final int MAX_APPS = 200;

    private final Context mContext;
    private final PackageManager mPackageManager;

    public AgentQueryService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    @Override
    public List<AppInfo> queryInstalledApps() {
        List<AppInfo> out = new ArrayList<>();
        try {
            // 仅返回具备启动入口的应用
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved =
                    mPackageManager.queryIntentActivities(launcher, 0);

            List<String> seen = new ArrayList<>();
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (seen.contains(pkg)) {
                    continue;
                }
                seen.add(pkg);

                AppInfo info = new AppInfo();
                info.packageName = pkg;
                try {
                    ApplicationInfo ai = mPackageManager.getApplicationInfo(pkg, 0);
                    info.label = String.valueOf(mPackageManager.getApplicationLabel(ai));
                    info.enabled = ai.enabled;
                    info.isSystemApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    info.firstInstallTime = 0L;
                } catch (PackageManager.NameNotFoundException e) {
                    info.label = pkg;
                    info.enabled = true;
                    info.isSystemApp = false;
                }
                out.add(info);
                if (out.size() >= MAX_APPS) {
                    break;
                }
            }
        } catch (Throwable t) {
            Slog.e(TAG, "queryInstalledApps failed", t);
            return Collections.emptyList();
        }
        Slog.d(TAG, "queryInstalledApps: " + out.size() + " apps");
        return out;
    }

    @Override
    public ActionInfo resolveIntent(ComponentTarget target) {
        if (target == null || target.packageName == null) {
            return null;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setClassName(target.packageName,
                    target.activityName != null ? target.activityName : "");
            List<ResolveInfo> resolved = mPackageManager.queryIntentActivities(intent, 0);

            ActionInfo info = new ActionInfo();
            info.actionId = "resolve:" + target.packageName;
            info.name = target.packageName;
            info.actionType = "intent.resolve";
            info.executorType = "activity";
            if (resolved != null && !resolved.isEmpty() && resolved.get(0).activityInfo != null) {
                ResolveInfo ri = resolved.get(0);
                info.description = ri.activityInfo.name;
                info.executorConfig = "{\"package\":\"" + ri.activityInfo.packageName
                        + "\",\"activity\":\"" + ri.activityInfo.name + "\"}";
            } else {
                info.description = "unresolved";
                info.executorConfig = "{}";
            }
            info.requiredPermissions = new String[0];
            info.requiredCapabilities = new String[0];
            info.parametersSchema = "{}";
            return info;
        } catch (Throwable t) {
            Slog.e(TAG, "resolveIntent failed: " + target.packageName, t);
            return null;
        }
    }
}
