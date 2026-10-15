// AI Native OS - Activity Indicator Controller (D3.2)
package com.android.systemui.genton.indicator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.genton.audit.ActivityIndicator;
import com.android.server.genton.AgentAuditService;

/**
 * 活性指示器控制器（D3.2）
 * 
 * 核心职责：
 * 1. 连接大脑审计服务（AgentAuditService）
 * 2. 监听活性事件流（IAgentAuditListener）
 * 3. 路由到 ActivityIndicatorManager
 * 4. 生命周期管理
 * 
 * 数据流：
 * Agent Brain → AgentAuditService.appendEvent()
 *   → 检测活性分类（CAT_PERCEPTION_READ/CAT_ACTION_EXECUTION/CAT_SYSTEM）
 *   → 通知所有监听器（IAgentAuditListener.onActivity()）
 *   → ActivityIndicatorController 接收
 *   → ActivityIndicatorManager 聚合
 *   → UI 层显示芯片
 * 
 * 线程模型：
 * - 监听器回调：Binder 线程
 * - 路由到 Manager：主线程（Handler.post）
 * 
 * WBS 对齐：
 * - 前置依赖 A5.2（活性流）✅
 * - 集成到 SystemUI ✅
 */
public class ActivityIndicatorController {
    private static final String TAG = "ActivityIndicatorCtrl";
    
    private final Context mContext;
    private final ActivityIndicatorManager mManager;
    private final Handler mHandler;
    
    // 审计服务（连接到 system_server）
    private AgentAuditService mAuditService;
    
    // 活性监听器
    private final AgentAuditService.IAgentAuditListener mActivityListener = 
        new AgentAuditService.IAgentAuditListener() {
            @Override
            public void onActivity(ActivityIndicator indicator) throws RemoteException {
                // Binder 线程 → 主线程
                mHandler.post(() -> {
                    handleActivityEvent(indicator);
                });
            }
        };
    
    private boolean mIsInitialized = false;
    
    public ActivityIndicatorController(Context context, ActivityIndicatorManager manager) {
        mContext = context;
        mManager = manager;
        mHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 初始化（连接审计服务）
     */
    public void initialize(AgentAuditService auditService) {
        if (mIsInitialized) {
            Log.w(TAG, "Already initialized");
            return;
        }
        
        mAuditService = auditService;
        
        if (mAuditService == null) {
            Log.e(TAG, "AuditService is null, cannot initialize");
            return;
        }
        
        // 注册活性监听器
        int result = mAuditService.registerActivityListener(mActivityListener);
        if (result == AgentAuditService.STATUS_OK) {
            Log.i(TAG, "Activity listener registered successfully");
            mIsInitialized = true;
            
            // 启动超时检查
            mManager.startTimeoutCheck();
        } else {
            Log.e(TAG, "Failed to register activity listener: " + result);
        }
    }
    
    /**
     * 处理活性事件
     * 
     * 在主线程中执行
     */
    private void handleActivityEvent(ActivityIndicator indicator) {
        if (indicator == null) {
            Log.w(TAG, "Received null indicator");
            return;
        }
        
        Log.d(TAG, "Activity event: category=" + indicator.category + 
              ", agentId=" + indicator.agentId + 
              ", active=" + indicator.active);
        
        // 路由到 Manager
        mManager.updateIndicator(indicator);
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        if (!mIsInitialized) {
            return;
        }
        
        // 注销监听器
        if (mAuditService != null) {
            mAuditService.unregisterActivityListener(mActivityListener);
            Log.i(TAG, "Activity listener unregistered");
        }
        
        // 停止超时检查
        mManager.stopTimeoutCheck();
        
        mIsInitialized = false;
        Log.i(TAG, "ActivityIndicatorController destroyed");
    }
    
    /**
     * 是否已初始化
     */
    public boolean isInitialized() {
        return mIsInitialized;
    }
}
