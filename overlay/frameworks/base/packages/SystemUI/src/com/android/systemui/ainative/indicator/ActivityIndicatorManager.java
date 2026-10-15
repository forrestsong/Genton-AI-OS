// AI Native OS - Activity Indicator Manager (D3.2)
package com.android.systemui.ainative.indicator;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.ainative.audit.ActivityIndicator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 活性指示器管理器（D3.2）
 * 
 * 核心职责：
 * 1. 聚合多 Agent 活性状态（按 category 分类）
 * 2. 防抖处理（短时间频繁开关不触发 UI 更新）
 * 3. 超时自动清理（防止僵尸指示器）
 * 4. 实时通知 UI 层（DisplayCallback）
 * 
 * 状态模型：
 * - SCREEN_READ：正在读取屏幕（CAT_PERCEPTION_READ）
 * - ACTION_EXEC：正在执行操作（CAT_ACTION_EXECUTION）
 * - RECORDING：正在录音/拍照（CAT_SYSTEM）
 * 
 * 聚合规则：
 * - 同类型多 Agent → 显示数量（如"2 个应用正在读屏"）
 * - 点击芯片 → 展开列表显示具体 Agent
 * 
 * 防抖策略：
 * - 开启 → 立即显示
 * - 关闭 → 延迟 300ms（防止闪烁）
 * 
 * 超时保护：
 * - 每个活性最长持续 5 分钟
 * - 超时自动移除（防止大脑异常导致僵尸指示器）
 * 
 * WBS 要求：
 * - 在读屏/在操作/在录音芯片 ✅
 * - 最近活动聚合 ✅
 * - 前置依赖 A5.2（活性流）✅
 */
public class ActivityIndicatorManager {
    private static final String TAG = "ActivityIndicatorMgr";
    
    // 指示器类型（对应 IAgentAssistantUi.INDICATOR_*）
    public static final int KIND_SCREEN_READ = 0;  // 正在读屏
    public static final int KIND_INJECT = 1;       // 正在操作
    public static final int KIND_RECORDING = 2;    // 正在录音
    
    // 审计事件分类（对应 ActivityIndicator.category）
    private static final int CAT_PERCEPTION_READ = 1;
    private static final int CAT_ACTION_EXECUTION = 2;
    private static final int CAT_SYSTEM = 7;
    
    // 防抖延迟（关闭时延迟 300ms）
    private static final long DEBOUNCE_CLOSE_DELAY_MS = 300;
    
    // 超时保护（5 分钟）
    private static final long ACTIVITY_TIMEOUT_MS = 5 * 60 * 1000;
    
    // 超时检查间隔（每 30 秒）
    private static final long TIMEOUT_CHECK_INTERVAL_MS = 30 * 1000;
    
    private final Handler mHandler;
    
    // 活性状态（kind -> {agentId -> timestampMs}）
    private final Map<Integer, Map<String, Long>> mActiveStates;
    
    // 防抖任务（kind -> Runnable）
    private final Map<Integer, Runnable> mDebounceRunnables;
    
    // UI 回调
    private DisplayCallback mDisplayCallback;
    
    // 超时检查任务
    private final Runnable mTimeoutCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndRemoveTimeoutActivities();
            mHandler.postDelayed(this, TIMEOUT_CHECK_INTERVAL_MS);
        }
    };
    
    /**
     * UI 显示回调
     */
    public interface DisplayCallback {
        /**
         * 指示器状态变化
         * 
         * @param kind 指示器类型（SCREEN_READ/INJECT/RECORDING）
         * @param active 是否活跃
         * @param agentCount 活跃 Agent 数量
         * @param agentIds 活跃 Agent ID 列表
         */
        void onIndicatorChanged(int kind, boolean active, int agentCount, List<String> agentIds);
    }
    
    public ActivityIndicatorManager() {
        mHandler = new Handler(Looper.getMainLooper());
        mActiveStates = new HashMap<>();
        mDebounceRunnables = new HashMap<>();
        
        // 初始化三种指示器状态
        mActiveStates.put(KIND_SCREEN_READ, new HashMap<>());
        mActiveStates.put(KIND_INJECT, new HashMap<>());
        mActiveStates.put(KIND_RECORDING, new HashMap<>());
    }
    
    /**
     * 设置显示回调
     */
    public void setDisplayCallback(DisplayCallback callback) {
        mDisplayCallback = callback;
    }
    
    // ==================== 核心方法：更新活性状态 ====================
    
    /**
     * 更新活性指示器
     * 
     * D3.2 核心逻辑：
     * 1. 分类映射（category → kind）
     * 2. 状态聚合（多 Agent）
     * 3. 防抖处理（延迟关闭）
     * 4. 触发 UI 更新
     * 
     * @param indicator 活性指示器数据
     */
    public void updateIndicator(ActivityIndicator indicator) {
        if (indicator == null) {
            Log.w(TAG, "updateIndicator: indicator is null");
            return;
        }
        
        // 1. 分类映射
        int kind = categoryToKind(indicator.category);
        if (kind == -1) {
            // 不关心的分类（如 POLICY/MEMORY）
            return;
        }
        
        String agentId = indicator.agentId != null ? indicator.agentId : "system";
        boolean active = indicator.active;
        
        Log.d(TAG, "updateIndicator: kind=" + kind + ", agentId=" + agentId + 
              ", active=" + active);
        
        // 2. 更新状态
        synchronized (mActiveStates) {
            Map<String, Long> agents = mActiveStates.get(kind);
            if (agents == null) {
                return;
            }
            
            if (active) {
                // 开启活性：立即添加
                long now = SystemClock.elapsedRealtime();
                agents.put(agentId, now);
                
                // 取消防抖任务（如果有）
                cancelDebounce(kind);
                
                // 立即通知 UI
                notifyDisplayUpdate(kind, true);
                
            } else {
                // 关闭活性：延迟移除（防抖）
                agents.remove(agentId);
                
                // 如果该类型没有其他活跃 Agent，延迟关闭
                if (agents.isEmpty()) {
                    scheduleDebounceClose(kind);
                } else {
                    // 还有其他 Agent 活跃，立即更新数量
                    notifyDisplayUpdate(kind, true);
                }
            }
        }
    }
    
    /**
     * 分类映射：审计分类 → 指示器类型
     * 
     * @return 指示器类型；-1=不关心
     */
    private int categoryToKind(int category) {
        switch (category) {
            case CAT_PERCEPTION_READ:
                return KIND_SCREEN_READ;
            
            case CAT_ACTION_EXECUTION:
                return KIND_INJECT;
            
            case CAT_SYSTEM:
                return KIND_RECORDING;
            
            default:
                return -1; // 不关心的分类
        }
    }
    
    // ==================== 防抖处理 ====================
    
    /**
     * 调度延迟关闭（防抖）
     */
    private void scheduleDebounceClose(int kind) {
        cancelDebounce(kind);
        
        Runnable runnable = () -> {
            synchronized (mActiveStates) {
                Map<String, Long> agents = mActiveStates.get(kind);
                if (agents != null && agents.isEmpty()) {
                    // 确认仍然为空，通知 UI 关闭
                    notifyDisplayUpdate(kind, false);
                }
            }
        };
        
        mDebounceRunnables.put(kind, runnable);
        mHandler.postDelayed(runnable, DEBOUNCE_CLOSE_DELAY_MS);
        
        Log.d(TAG, "Scheduled debounce close for kind=" + kind);
    }
    
    /**
     * 取消防抖任务
     */
    private void cancelDebounce(int kind) {
        Runnable runnable = mDebounceRunnables.remove(kind);
        if (runnable != null) {
            mHandler.removeCallbacks(runnable);
            Log.d(TAG, "Cancelled debounce for kind=" + kind);
        }
    }
    
    // ==================== 超时保护 ====================
    
    /**
     * 启动超时检查
     */
    public void startTimeoutCheck() {
        mHandler.removeCallbacks(mTimeoutCheckRunnable);
        mHandler.postDelayed(mTimeoutCheckRunnable, TIMEOUT_CHECK_INTERVAL_MS);
        Log.i(TAG, "Timeout check started");
    }
    
    /**
     * 停止超时检查
     */
    public void stopTimeoutCheck() {
        mHandler.removeCallbacks(mTimeoutCheckRunnable);
        Log.i(TAG, "Timeout check stopped");
    }
    
    /**
     * 检查并移除超时活性
     */
    private void checkAndRemoveTimeoutActivities() {
        long now = SystemClock.elapsedRealtime();
        boolean hasTimeout = false;
        
        synchronized (mActiveStates) {
            for (Map.Entry<Integer, Map<String, Long>> entry : mActiveStates.entrySet()) {
                int kind = entry.getKey();
                Map<String, Long> agents = entry.getValue();
                
                Iterator<Map.Entry<String, Long>> iter = agents.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<String, Long> agentEntry = iter.next();
                    long startTime = agentEntry.getValue();
                    
                    if (now - startTime > ACTIVITY_TIMEOUT_MS) {
                        String agentId = agentEntry.getKey();
                        iter.remove();
                        hasTimeout = true;
                        Log.w(TAG, "Activity timeout: kind=" + kind + ", agentId=" + agentId);
                    }
                }
                
                // 如果该类型清空了，通知 UI
                if (hasTimeout && agents.isEmpty()) {
                    notifyDisplayUpdate(kind, false);
                }
            }
        }
    }
    
    // ==================== UI 通知 ====================
    
    /**
     * 通知 UI 更新显示
     */
    private void notifyDisplayUpdate(int kind, boolean active) {
        if (mDisplayCallback == null) {
            return;
        }
        
        List<String> agentIds = new ArrayList<>();
        int agentCount = 0;
        
        synchronized (mActiveStates) {
            Map<String, Long> agents = mActiveStates.get(kind);
            if (agents != null) {
                agentIds.addAll(agents.keySet());
                agentCount = agents.size();
            }
        }
        
        mHandler.post(() -> {
            mDisplayCallback.onIndicatorChanged(kind, active, agentCount, agentIds);
        });
        
        Log.i(TAG, "Indicator changed: kind=" + kind + ", active=" + active + 
              ", count=" + agentCount);
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 获取指定类型的活跃 Agent 列表
     */
    public List<String> getActiveAgents(int kind) {
        synchronized (mActiveStates) {
            Map<String, Long> agents = mActiveStates.get(kind);
            if (agents != null) {
                return new ArrayList<>(agents.keySet());
            }
        }
        return new ArrayList<>();
    }
    
    /**
     * 获取指定类型的活跃数量
     */
    public int getActiveCount(int kind) {
        synchronized (mActiveStates) {
            Map<String, Long> agents = mActiveStates.get(kind);
            if (agents != null) {
                return agents.size();
            }
        }
        return 0;
    }
    
    /**
     * 是否有任何活性
     */
    public boolean hasAnyActivity() {
        synchronized (mActiveStates) {
            for (Map<String, Long> agents : mActiveStates.values()) {
                if (!agents.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 清空所有活性
     */
    public void clearAll() {
        synchronized (mActiveStates) {
            for (int kind : mActiveStates.keySet()) {
                Map<String, Long> agents = mActiveStates.get(kind);
                if (agents != null && !agents.isEmpty()) {
                    agents.clear();
                    notifyDisplayUpdate(kind, false);
                }
            }
        }
        
        // 取消所有防抖任务
        for (Runnable runnable : mDebounceRunnables.values()) {
            mHandler.removeCallbacks(runnable);
        }
        mDebounceRunnables.clear();
        
        Log.i(TAG, "All activities cleared");
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        stopTimeoutCheck();
        clearAll();
        Log.i(TAG, "ActivityIndicatorManager destroyed");
    }
}
