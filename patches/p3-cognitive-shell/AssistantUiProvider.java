// Genton (AI Native OS) - 助手 UI 提供方（SystemUI 侧，P1）
//
// 实现 IAgentAssistantUiProvider 并反向注册给 system_server 侧
// AgentAssistantUiService（平台不允许应用 UID 用 ServiceManager.addService）。
//
// 路由：
//   setActivityIndicator -> ActivityIndicatorManager -> 感知/操作指示器芯片
//   requestAssistantSurface / showSuggestion / navigateTo -> P2/P3 的助理表面
//     （当前仅记录并保留接口，P2 接入悬浮球与双模式，P3 接入认知壳）
package com.android.systemui.ainative.assistantui;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.ainative.audit.ActivityIndicator;
import com.ainative.ui.IAgentAssistantUi;
import com.ainative.ui.IAgentAssistantUiProvider;
import com.ainative.ui.SuggestionCapsuleData;
import com.android.systemui.ainative.indicator.ActivityIndicatorBootstrap;
import com.android.systemui.ainative.indicator.ActivityIndicatorManager;

/** 助手 UI 提供方（SystemUI 侧） */
public class AssistantUiProvider extends IAgentAssistantUiProvider.Stub {

    private static final String TAG = "GentonAssistantUiProv";

    /** 服务名（system_server 注册），与 service_contexts 一致 */
    private static final String SERVICE_NAME = "com.ainative.ui.IAgentAssistantUi";

    private final Context mContext;
    private static volatile AssistantUiProvider sInstance;

    private AssistantUiProvider(Context context) {
        mContext = context.getApplicationContext();
    }

    /** 幂等启动并反向注册 */
    public static synchronized void start(Context context) {
        if (sInstance != null) {
            return;
        }
        sInstance = new AssistantUiProvider(context);
        sInstance.register();
    }

    private void register() {
        try {
            IBinder b = ServiceManager.getService(SERVICE_NAME);
            if (b == null) {
                Log.w(TAG, "assistant ui service not found: " + SERVICE_NAME);
                return;
            }
            IAgentAssistantUi svc = IAgentAssistantUi.Stub.asInterface(b);
            svc.registerUiProvider(this);
            Log.i(TAG, "UI provider registered with " + SERVICE_NAME);
        } catch (Throwable t) {
            Log.e(TAG, "register failed", t);
        }
    }

    // ---------------------------------------------------------------- AIDL
    @Override
    public void setActivityIndicator(int kind, boolean active) {
        Log.i(TAG, "setActivityIndicator kind=" + kind + " active=" + active);
        ActivityIndicatorManager mgr = ActivityIndicatorBootstrap.getManager();
        if (mgr == null) {
            Log.w(TAG, "indicator manager unavailable");
            return;
        }
        // IAgentAssistantUi.INDICATOR_* -> 审计分类（Manager 的 categoryToKind 映射）
        int category;
        switch (kind) {
            case IAgentAssistantUi.INDICATOR_SCREEN_READ:
                category = 1;   // CAT_PERCEPTION_READ -> KIND_SCREEN_READ
                break;
            case IAgentAssistantUi.INDICATOR_INJECT:
                category = 2;   // CAT_ACTION_EXECUTION -> KIND_INJECT
                break;
            case IAgentAssistantUi.INDICATOR_RECORDING:
                category = 7;   // CAT_SYSTEM -> KIND_RECORDING
                break;
            default:
                Log.w(TAG, "unknown indicator kind: " + kind);
                return;
        }
        ActivityIndicator ind = new ActivityIndicator();
        ind.category = category;
        ind.agentId = "brain";
        ind.active = active;
        ind.timestampRealtimeNanos = System.nanoTime();
        mgr.updateIndicator(ind);
    }

    @Override
    public void requestAssistantSurface(String reason) {
        Log.i(TAG, "requestAssistantSurface: " + reason);
        AssistantSurfaceBootstrap b = AssistantSurfaceBootstrap.get();
        if (b != null) {
            b.showAssistant("brain:" + reason);
        } else {
            // 引导未就绪时用广播兜底（由 AssistantSurfaceBootstrap 接收）
            mContext.sendBroadcast(new android.content.Intent(
                    AssistantSurfaceBootstrap.ACTION_SHOW_ASSISTANT));
        }
    }

    @Override
    public void showSuggestion(SuggestionCapsuleData capsule) {
        if (capsule == null) {
            return;
        }
        Log.i(TAG, "showSuggestion: id=" + capsule.capsuleId);
        AssistantSurfaceBootstrap b = AssistantSurfaceBootstrap.get();
        if (b == null) {
            Log.w(TAG, "surface bootstrap unavailable");
            return;
        }
        // 胶囊是顶层轻量呈现，无需强制进入助理模式（D5 §1.5 克制式打扰）
        boolean ok = b.showSuggestion(capsule);
        Log.i(TAG, "suggestion dispatched: " + ok);
    }

    @Override
    public void navigateTo(int section) {
        Log.i(TAG, "navigateTo: " + section);
        // P2：先确保进入助理模式；分区路由（任务/隐私/审计）在 P3 认知壳内实现
        AssistantSurfaceBootstrap b = AssistantSurfaceBootstrap.get();
        if (b != null) {
            b.showAssistant("navigate:" + section);
        }
    }
}
