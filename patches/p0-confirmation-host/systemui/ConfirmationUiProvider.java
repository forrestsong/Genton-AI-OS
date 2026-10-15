// Genton (AI Native OS) - 确认 UI 提供方（SystemUI 侧）
//
// 实现 com.genton.ui.IAgentConfirmationUi，并通过
// IAgentConfirmationHost.registerUiProvider(binder) 反向交给 system_server。
//
// 为什么不直接 ServiceManager.addService（实测）：
//   com.android.systemui 运行在应用 UID（本机实测 10119），
//   servicemanager 的 addService 对 multiuser_get_app_id(uid) >= AID_APP(10000)
//   直接返回 "App UIDs cannot add services."（SecurityException）。
//
// 安全链路（保持设计原意）：
//   执行中介 -> ConfirmationHostService(system_server: HMAC 校验/队列/超时)
//            -> 本类展示全屏 FLAG_SECURE 面板
//            -> 用户决策经 IConfirmationDecisionCallback 回到 system_server
//            -> system_server 回灌 PolicyEngine.reportUserDecision 并路由回中介
//   （决策全程不经过大脑进程）
package com.android.systemui.genton.confirmation;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.genton.ui.ConfirmationUiRequest;
import com.genton.ui.IAgentConfirmationHost;
import com.genton.ui.IAgentConfirmationUi;
import com.genton.ui.IConfirmationDecisionCallback;

/** 确认 UI 提供方（SystemUI 独占绘制） */
public class ConfirmationUiProvider extends IAgentConfirmationUi.Stub {

    private static final String TAG = "GentonConfirmUi";

    /** 确认宿主服务名（system_server 注册），须与 service_contexts 一致 */
    private static final String HOST_SERVICE = "com.genton.ui.IAgentConfirmationHost";

    private final Context mContext;
    private final ConfirmationPanel mPanel;
    private volatile IConfirmationDecisionCallback mDecisionCallback;

    private static volatile ConfirmationUiProvider sInstance;

    private ConfirmationUiProvider(Context context) {
        mContext = context.getApplicationContext();
        mPanel = new ConfirmationPanel(mContext);
    }

    /** 幂等启动并反向注册（由 SystemUIService.onCreate 调用） */
    public static synchronized void start(Context context) {
        if (sInstance != null) {
            return;
        }
        sInstance = new ConfirmationUiProvider(context);
        sInstance.register();
    }

    private void register() {
        try {
            IBinder b = ServiceManager.getService(HOST_SERVICE);
            if (b == null) {
                Log.w(TAG, "confirmation host not found: " + HOST_SERVICE
                        + " (system_server 侧未注册？)");
                return;
            }
            IAgentConfirmationHost host = IAgentConfirmationHost.Stub.asInterface(b);
            host.registerUiProvider(this);
            Log.i(TAG, "UI provider registered with " + HOST_SERVICE);
        } catch (Throwable t) {
            Log.e(TAG, "register UI provider failed", t);
        }
    }

    /** 供 SystemUI 内部重试（服务端晚于 SystemUI 就绪时） */
    public static void retryRegister() {
        ConfirmationUiProvider p = sInstance;
        if (p != null) {
            p.register();
        }
    }

    // ---------------------------------------------------------------- AIDL
    @Override
    public void show(ConfirmationUiRequest request) {
        if (request == null) {
            return;
        }
        Log.i(TAG, "show: id=" + request.confirmId + " capability=" + request.capability
                + " risk=" + request.riskLabel);
        mPanel.show(request, (confirmId, decision) -> {
            IConfirmationDecisionCallback cb = mDecisionCallback;
            if (cb == null) {
                Log.e(TAG, "decision callback missing; decision dropped: " + confirmId);
                return;
            }
            try {
                // 决策交回 system_server 侧：由它回灌策略引擎并路由回执行中介
                cb.onUserDecision(confirmId, decision);
            } catch (RemoteException e) {
                Log.e(TAG, "onUserDecision failed", e);
            }
        });
    }

    @Override
    public void dismiss(String confirmId, int reason) {
        Log.i(TAG, "dismiss: id=" + confirmId + " reason=" + reason);
        mPanel.dismiss();
    }

    @Override
    public void setDecisionCallback(IConfirmationDecisionCallback callback) {
        mDecisionCallback = callback;
        Log.i(TAG, "decision callback " + (callback == null ? "cleared" : "installed"));
    }
}
