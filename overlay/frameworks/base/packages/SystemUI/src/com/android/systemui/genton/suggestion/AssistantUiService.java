// AI Native OS - Assistant UI Service (D2.3)
package com.android.systemui.genton.suggestion;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import com.genton.ui.IAgentAssistantUi;
import com.genton.ui.SuggestionCapsuleData;

/**
 * 助手 UI 服务端（D2.3）
 * 
 * 实现 IAgentAssistantUi.aidl 接口，由 SystemUI 注册到 ServiceManager
 * 
 * 核心职责：
 * 1. 接收大脑的 UI 命令（showSuggestion/requestAssistantSurface/navigateTo/setActivityIndicator）
 * 2. 路由到 SuggestionCapsuleManager（胶囊）
 * 3. 路由到 AssistantSurfaceController（拉起助理）
 * 4. 路由到 ActivityIndicatorManager（活性指示器）
 * 
 * 安全约束：
 * - 调用方必须持有 com.genton.permission.CONTROL_AGENT_UI (signature|privileged)
 * - 仅大脑特权应用可调用
 * 
 * WBS 对齐：
 * - D2.3 主动提醒胶囊：showSuggestion() ✅
 * - D3.2 活性指示器：setActivityIndicator() ✅
 * - D1.3 后台化：requestAssistantSurface() ✅
 */
public class AssistantUiService extends IAgentAssistantUi.Stub {
    private static final String TAG = "AssistantUiService";
    
    private final Context mContext;
    private final SuggestionCapsuleManager mCapsuleManager;
    
    // 回调接口（注入外部依赖）
    private SurfaceRequestCallback mSurfaceCallback;
    private NavigationCallback mNavigationCallback;
    private ActivityIndicatorCallback mActivityIndicatorCallback;
    
    /**
     * 拉起助理回调
     */
    public interface SurfaceRequestCallback {
        void onRequestAssistantSurface(String reason);
    }
    
    /**
     * 导航回调
     */
    public interface NavigationCallback {
        void onNavigateTo(int section);
    }
    
    /**
     * 活性指示器回调
     */
    public interface ActivityIndicatorCallback {
        void onSetActivityIndicator(int kind, boolean active);
    }
    
    public AssistantUiService(Context context, SuggestionCapsuleManager capsuleManager) {
        mContext = context;
        mCapsuleManager = capsuleManager;
    }
    
    /**
     * 设置拉起助理回调
     */
    public void setSurfaceRequestCallback(SurfaceRequestCallback callback) {
        mSurfaceCallback = callback;
    }
    
    /**
     * 设置导航回调
     */
    public void setNavigationCallback(NavigationCallback callback) {
        mNavigationCallback = callback;
    }
    
    /**
     * 设置活性指示器回调
     */
    public void setActivityIndicatorCallback(ActivityIndicatorCallback callback) {
        mActivityIndicatorCallback = callback;
    }
    
    // ==================== IAgentAssistantUi 实现 ====================
    
    /**
     * 拉起助理界面
     * 
     * AIDL: oneway void requestAssistantSurface(in String reason)
     */
    @Override
    public void requestAssistantSurface(String reason) throws RemoteException {
        Log.i(TAG, "requestAssistantSurface: reason=" + reason);
        
        // 权限检查（由 system_server 在 Binder 层完成）
        // 这里假设已通过 SELinux + AndroidManifest 权限检查
        
        if (mSurfaceCallback != null) {
            mSurfaceCallback.onRequestAssistantSurface(reason);
        } else {
            Log.w(TAG, "SurfaceRequestCallback not set");
        }
    }
    
    /**
     * 显示建议胶囊（D2.3 核心）
     * 
     * AIDL: oneway void showSuggestion(in SuggestionCapsuleData capsule)
     */
    @Override
    public void showSuggestion(SuggestionCapsuleData capsule) throws RemoteException {
        if (capsule == null) {
            Log.w(TAG, "showSuggestion: capsule is null");
            return;
        }
        
        Log.i(TAG, "showSuggestion: id=" + capsule.capsuleId + 
              ", priority=" + capsule.priority + 
              ", title=" + capsule.title);
        
        // 路由到 SuggestionCapsuleManager
        boolean shown = mCapsuleManager.showSuggestion(capsule);
        
        if (shown) {
            Log.d(TAG, "Capsule shown: " + capsule.capsuleId);
        } else {
            Log.w(TAG, "Capsule blocked (DND or expired): " + capsule.capsuleId);
        }
    }
    
    /**
     * 导航到特定分区
     * 
     * AIDL: oneway void navigateTo(in int section)
     */
    @Override
    public void navigateTo(int section) throws RemoteException {
        String sectionName;
        switch (section) {
            case IAgentAssistantUi.NAV_HOME:
                sectionName = "HOME";
                break;
            case IAgentAssistantUi.NAV_TASKS:
                sectionName = "TASKS";
                break;
            case IAgentAssistantUi.NAV_PRIVACY:
                sectionName = "PRIVACY";
                break;
            case IAgentAssistantUi.NAV_AUDIT:
                sectionName = "AUDIT";
                break;
            default:
                Log.w(TAG, "Invalid navigation section: " + section);
                return;
        }
        
        Log.i(TAG, "navigateTo: " + sectionName);
        
        if (mNavigationCallback != null) {
            mNavigationCallback.onNavigateTo(section);
        } else {
            Log.w(TAG, "NavigationCallback not set");
        }
    }
    
    /**
     * 设置活性指示器状态（D3.2）
     * 
     * AIDL: oneway void setActivityIndicator(in int kind, in boolean active)
     */
    @Override
    public void setActivityIndicator(int kind, boolean active) throws RemoteException {
        String kindName;
        switch (kind) {
            case IAgentAssistantUi.INDICATOR_SCREEN_READ:
                kindName = "SCREEN_READ";
                break;
            case IAgentAssistantUi.INDICATOR_INJECT:
                kindName = "INJECT";
                break;
            case IAgentAssistantUi.INDICATOR_RECORDING:
                kindName = "RECORDING";
                break;
            default:
                Log.w(TAG, "Invalid indicator kind: " + kind);
                return;
        }
        
        Log.i(TAG, "setActivityIndicator: " + kindName + "=" + active);
        
        if (mActivityIndicatorCallback != null) {
            mActivityIndicatorCallback.onSetActivityIndicator(kind, active);
        } else {
            Log.w(TAG, "ActivityIndicatorCallback not set");
        }
    }
    
    // ==================== 生命周期 ====================
    
    /**
     * 初始化
     */
    public void initialize() {
        Log.i(TAG, "AssistantUiService initialized");
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        Log.i(TAG, "AssistantUiService destroyed");
    }
}
