// Genton (AI Native OS) - 活性指示器控制器（D3.2）
//
// 数据源：com.ainative.audit.IAuditService（AIDL）的活性流订阅
//
// [P1 修复] 原实现直接依赖 com.android.server.ainative.AgentAuditService 类，
//   而 SystemUI 是独立 APK（应用 UID），无法访问服务端类 —— 这是当初整批
//   SystemUI 覆盖层被移出编译的根因之一。现改为纯 AIDL 跨进程订阅：
//     IAuditService.registerActivityListener(IAuditListener)
//       -> IAuditListener.onActivity(ActivityIndicator)
//       -> ActivityIndicatorManager.updateIndicator()
//       -> ActivityIndicatorOverlay 显示芯片
//
// 线程模型：Binder 回调 -> 主线程 Handler -> Manager
package com.android.systemui.ainative.indicator;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.ainative.audit.ActivityIndicator;
import com.ainative.audit.IAuditListener;
import com.ainative.audit.IAuditService;

/** 活性指示器控制器：订阅审计活性流并驱动指示器 */
public class ActivityIndicatorController {

    private static final String TAG = "GentonIndicatorCtrl";

    /** 审计服务名（system_server 注册），与 service_contexts 一致 */
    private static final String AUDIT_SERVICE = "com.ainative.audit.IAuditService";

    private final Context mContext;
    private final ActivityIndicatorManager mManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private IAuditService mAuditService;
    private boolean mRegistered;

    private final IAuditListener mListener = new IAuditListener.Stub() {
        @Override
        public void onActivity(ActivityIndicator indicator) {
            if (indicator == null) {
                return;
            }
            mHandler.post(() -> {
                if (mManager != null) {
                    mManager.updateIndicator(indicator);
                }
            });
        }
    };

    public ActivityIndicatorController(Context context, ActivityIndicatorManager manager) {
        mContext = context;
        mManager = manager;
    }

    /** 订阅活性流；服务未就绪时静默返回（可由调用方重试） */
    public boolean start() {
        if (mRegistered) {
            return true;
        }
        try {
            IBinder b = ServiceManager.getService(AUDIT_SERVICE);
            if (b == null) {
                Log.w(TAG, "audit service not found: " + AUDIT_SERVICE);
                return false;
            }
            mAuditService = IAuditService.Stub.asInterface(b);
            int rc = mAuditService.registerActivityListener(mListener);
            if (rc == IAuditService.STATUS_OK) {
                mRegistered = true;
                mManager.startTimeoutCheck();
                Log.i(TAG, "activity listener registered");
                return true;
            }
            Log.e(TAG, "registerActivityListener rc=" + rc);
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
        }
        return false;
    }

    public void stop() {
        if (!mRegistered || mAuditService == null) {
            return;
        }
        try {
            mAuditService.unregisterActivityListener(mListener);
        } catch (RemoteException e) {
            Log.w(TAG, "unregister failed", e);
        }
        mRegistered = false;
        if (mManager != null) {
            mManager.stopTimeoutCheck();
        }
        Log.i(TAG, "activity listener unregistered");
    }
}
