package com.android.server.genton;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.genton.learn.IntentLearningGenerator;
import com.genton.learn.LearnStateManager;
import com.genton.learn.LearnStateListener;
import com.genton.learn.LearnSuggestion;
import com.genton.learn.LearnSuggestionRecord;
import com.genton.learn.NotificationInteraction;
import com.genton.learn.CallInteraction;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intent Learning 系统服务（增强版）
 * 
 * 职责：
 * 1. 定期扫描用户交互历史（24小时间隔）
 * 2. 调用 IntentLearningGenerator 生成候选规则
 * 3. 通过 LearnStateManager 持久化建议
 * 4. 管理建议生命周期（7天抑制窗、30天清理）
 * 5. 监听状态变更并通知上层（PolicyEngine、UI）
 * 
 * 集成点：
 * - C2.1 IntentLearningGenerator: 生成 learn 候选
 * - C2.2 LearnStateManager: 状态管理和持久化
 * - A7.1 PolicyEngine: 激活后重新加载规则（通过监听器）
 * - D4.2 UI: 展示待处理建议（通过监听器）
 */
public class IntentLearningService {
    private static final String TAG = "IntentLearningService";
    
    // 扫描间隔：24 小时
    private static final long SCAN_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    
    // 历史窗口：30 天
    private static final long HISTORY_WINDOW_MS = 30 * 24 * 60 * 60 * 1000L;
    
    // 清理间隔：7 天
    private static final long CLEANUP_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L;
    
    private final Context mContext;
    private final IntentLearningGenerator mGenerator;
    private final LearnStateManager mStateManager;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;
    
    // 上层监听器（PolicyEngine、UI 等）
    private final List<OnSuggestionListener> mExternalListeners = new ArrayList<>();
    
    public IntentLearningService(Context context) {
        mContext = context;
        
        // 初始化生成器
        mGenerator = new IntentLearningGenerator(
            context,
            null,  // IEntityQueryService 由 A5.4 提供
            null   // IAuditService 由 A5.2 提供
        );
        
        // 初始化状态管理器
        mStateManager = new LearnStateManager(context);
        
        // 注册内部监听器（桥接到外部监听器）
        mStateManager.registerListener(new LearnStateListener() {
            @Override
            public void onSuggestionAdded(LearnSuggestionRecord suggestion) {
                notifyExternalListeners(suggestion);
            }
            
            @Override
            public void onSuggestionActivated(LearnSuggestionRecord suggestion) {
                Log.i(TAG, "Suggestion activated: " + suggestion.getSuggestionId());
                // PolicyEngine 需要重新加载规则
                notifyPolicyEngineReload();
            }
            
            @Override
            public void onSuggestionRevoked(LearnSuggestionRecord suggestion, String reason) {
                Log.i(TAG, "Suggestion revoked: " + suggestion.getSuggestionId());
                // PolicyEngine 需要重新加载规则
                notifyPolicyEngineReload();
            }
        });
        
        // 创建后台线程
        mHandlerThread = new HandlerThread("IntentLearningService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        
        Log.i(TAG, "IntentLearningService initialized");
    }
    
    /**
     * 启动服务
     */
    public void start() {
        Log.i(TAG, "Starting IntentLearningService");
        
        mHandler.post(() -> {
            // 立即执行一次扫描
            performScan();
            
            // 启动定期扫描
            scheduleScan();
            
            // 启动定期清理
            scheduleCleanup();
        });
    }
    
    /**
     * 停止服务
     */
    public void stop() {
        Log.i(TAG, "Stopping IntentLearningService");
        mHandler.removeCallbacksAndMessages(null);
        mHandlerThread.quitSafely();
    }
    
    // ========== 扫描调度 ==========
    
    /**
     * 调度下一次扫描（24 小时后）
     */
    private void scheduleScan() {
        mHandler.postDelayed(() -> {
            performScan();
            scheduleScan();  // 递归调度
        }, SCAN_INTERVAL_MS);
        
        Log.d(TAG, "Next scan scheduled in 24 hours");
    }
    
    /**
     * 执行扫描
     */
    private void performScan() {
        Log.i(TAG, "Starting periodic scan");
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 抽取交互历史（近 30 天）
            long windowStartMs = System.currentTimeMillis() - HISTORY_WINDOW_MS;
            
            List<NotificationInteraction> notificationHistory = extractNotificationHistory(windowStartMs);
            List<CallInteraction> callHistory = extractCallHistory(windowStartMs);
            
            Log.d(TAG, "Extracted history: notifications=" + notificationHistory.size() 
                    + ", calls=" + callHistory.size());
            
            // 2. 生成通知域候选规则
            List<LearnSuggestion> notificationSuggestions = 
                mGenerator.generateNotificationSuggestions(notificationHistory);
            
            // 3. 生成来电域候选规则
            List<LearnSuggestion> callSuggestions = 
                mGenerator.generateCallGateSuggestions(callHistory);
            
            // 4. 合并候选列表
            List<LearnSuggestion> allSuggestions = new ArrayList<>();
            allSuggestions.addAll(notificationSuggestions);
            allSuggestions.addAll(callSuggestions);
            
            Log.i(TAG, "Generated " + allSuggestions.size() + " suggestions");
            
            // 5. 添加到状态管理器（自动检查抑制窗）
            int addedCount = 0;
            for (LearnSuggestion suggestion : allSuggestions) {
                if (mStateManager.addSuggestion(suggestion)) {
                    addedCount++;
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            Log.i(TAG, "Scan completed: generated=" + allSuggestions.size() 
                    + ", added=" + addedCount + ", duration=" + duration + "ms");
            
        } catch (Exception e) {
            Log.e(TAG, "Error during scan", e);
        }
    }
    
    /**
     * 抽取通知交互历史
     * 
     * TODO: 集成 A2.3 NotificationService 审计日志
     */
    private List<NotificationInteraction> extractNotificationHistory(long windowStartMs) {
        // 暂时返回空列表，等待 A2.3 审计日志接口
        // 生产实现需要查询：
        // - IAgentAuditService.queryEvents(type=NOTIFICATION_POSTED, startMs, endMs)
        // - 提取字段: senderDomain, senderTrust, category, classifierConfidence, userOpened
        return new ArrayList<>();
    }
    
    /**
     * 抽取来电交互历史
     * 
     * TODO: 集成 A10.1 CallGate 审计日志
     */
    private List<CallInteraction> extractCallHistory(long windowStartMs) {
        // 暂时返回空列表，等待 A10.1 审计日志接口
        // 生产实现需要查询：
        // - IAgentAuditService.queryEvents(type=CALL_INCOMING, startMs, endMs)
        // - 提取字段: callerNumber, callerClass, numberPrefix, spamScore, userAnswered
        return new ArrayList<>();
    }
    
    // ========== 清理调度 ==========
    
    /**
     * 调度定期清理（7 天一次）
     */
    private void scheduleCleanup() {
        mHandler.postDelayed(() -> {
            performCleanup();
            scheduleCleanup();  // 递归调度
        }, CLEANUP_INTERVAL_MS);
        
        Log.d(TAG, "Next cleanup scheduled in 7 days");
    }
    
    /**
     * 执行清理（删除 DISCARDED 且超过 30 天的记录）
     */
    private void performCleanup() {
        Log.i(TAG, "Starting periodic cleanup");
        
        try {
            int deletedCount = mStateManager.cleanupExpiredSuggestions(30);
            Log.i(TAG, "Cleanup completed: deleted " + deletedCount + " expired suggestions");
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
    }
    
    // ========== 外部 API ==========
    
    /**
     * 注册上层监听器（PolicyEngine、UI 等）
     */
    public void registerListener(OnSuggestionListener listener) {
        synchronized (mExternalListeners) {
            mExternalListeners.add(listener);
        }
        Log.d(TAG, "Registered external listener");
    }
    
    /**
     * 注销上层监听器
     */
    public void unregisterListener(OnSuggestionListener listener) {
        synchronized (mExternalListeners) {
            mExternalListeners.remove(listener);
        }
        Log.d(TAG, "Unregistered external listener");
    }
    
    /**
     * 获取状态管理器（供外部调用）
     */
    public LearnStateManager getStateManager() {
        return mStateManager;
    }
    
    /**
     * 手动触发扫描（调试用）
     */
    public void triggerScanNow() {
        Log.i(TAG, "Manual scan triggered");
        mHandler.post(this::performScan);
    }
    
    // ========== 私有辅助方法 ==========
    
    /**
     * 通知外部监听器
     */
    private void notifyExternalListeners(LearnSuggestionRecord suggestion) {
        synchronized (mExternalListeners) {
            for (OnSuggestionListener listener : mExternalListeners) {
                try {
                    listener.onSuggestionAdded(suggestion);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying listener", e);
                }
            }
        }
    }
    
    /**
     * 通知 PolicyEngine 重新加载规则
     */
    private void notifyPolicyEngineReload() {
        // TODO: 调用 A7.1 IPolicyEngine.reloadPolicySet()
        Log.d(TAG, "TODO: Notify PolicyEngine to reload rules");
    }
    
    // ========== 监听器接口 ==========
    
    /**
     * 外部监听器接口（供 PolicyEngine、UI 实现）
     */
    public interface OnSuggestionListener {
        /**
         * 新建议生成
         * 
         * @param suggestion 新生成的建议
         */
        void onSuggestionAdded(LearnSuggestionRecord suggestion);
    }
}
