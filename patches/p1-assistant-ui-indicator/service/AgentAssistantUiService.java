// Genton (AI Native OS) - 助手 UI 服务端代理（P1）
//
// 系统服务名 com.ainative.ui.IAgentAssistantUi 由本类（system_server 侧）注册。
// 由于 SystemUI 为应用 UID 无法自行注册系统服务，本类同时承担：
//   1) 代理角色：接收大脑的 UI 命令并转发给 SystemUI 提供方
//   2) 引导角色：接受 SystemUI 的 registerUiProvider 反向注册
//
// 依据：轨道A-SystemUI会话桥AIDL接口草案 / D5 §6 集成架构
package com.android.server.ainative;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.ainative.ui.IAgentAssistantUi;
import com.ainative.ui.IAgentAssistantUiProvider;
import com.ainative.ui.SuggestionCapsuleData;

/** 助手 UI 命令服务（system_server 侧代理） */
public class AgentAssistantUiService extends IAgentAssistantUi.Stub {

    private static final String TAG = "GentonAssistantUi";

    private final Context mContext;
    private volatile IAgentAssistantUiProvider mProvider;

    public AgentAssistantUiService(Context context) {
        mContext = context;
    }

    // ==================== 反向注册（SystemUI -> 本服务）====================

    @Override
    public void registerUiProvider(IBinder provider) {
        if (provider == null) {
            Slog.w(TAG, "registerUiProvider: null");
            return;
        }
        mProvider = IAgentAssistantUiProvider.Stub.asInterface(provider);
        Slog.i(TAG, "[P1] SystemUI assistant UI provider registered");
    }

    @Override
    public boolean isUiReady() {
        return mProvider != null;
    }

    // ==================== 代理转发（大脑 -> SystemUI）====================

    @Override
    public void requestAssistantSurface(String reason) {
        IAgentAssistantUiProvider p = mProvider;
        if (p == null) {
            Slog.w(TAG, "requestAssistantSurface: UI not ready");
            return;
        }
        try {
            p.requestAssistantSurface(reason);
            Slog.i(TAG, "[P1] requestAssistantSurface: " + reason);
        } catch (RemoteException e) {
            Slog.e(TAG, "requestAssistantSurface failed", e);
        }
    }

    @Override
    public void showSuggestion(SuggestionCapsuleData capsule) {
        IAgentAssistantUiProvider p = mProvider;
        if (p == null || capsule == null) {
            Slog.w(TAG, "showSuggestion: UI not ready or null capsule");
            return;
        }
        try {
            p.showSuggestion(capsule);
            Slog.i(TAG, "[P1] showSuggestion dispatched");
        } catch (RemoteException e) {
            Slog.e(TAG, "showSuggestion failed", e);
        }
    }

    @Override
    public void navigateTo(int section) {
        IAgentAssistantUiProvider p = mProvider;
        if (p == null) {
            Slog.w(TAG, "navigateTo: UI not ready");
            return;
        }
        try {
            p.navigateTo(section);
            Slog.i(TAG, "[P1] navigateTo: " + section);
        } catch (RemoteException e) {
            Slog.e(TAG, "navigateTo failed", e);
        }
    }

    @Override
    public void setActivityIndicator(int kind, boolean active) {
        IAgentAssistantUiProvider p = mProvider;
        if (p == null) {
            Slog.w(TAG, "setActivityIndicator: UI not ready");
            return;
        }
        try {
            p.setActivityIndicator(kind, active);
            Slog.i(TAG, "[P1] setActivityIndicator kind=" + kind + " active=" + active);
        } catch (RemoteException e) {
            Slog.e(TAG, "setActivityIndicator failed", e);
        }
    }
}
