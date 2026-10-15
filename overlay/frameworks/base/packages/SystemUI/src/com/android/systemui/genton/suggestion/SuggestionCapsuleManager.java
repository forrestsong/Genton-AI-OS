// AI Native OS - Suggestion Capsule Manager (D2.3)
package com.android.systemui.genton.suggestion;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.genton.ui.SuggestionCapsuleData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 主动提醒胶囊管理器（D2.3）
 * 
 * 核心职责：
 * 1. 管理胶囊生命周期（显示/过期/清理）
 * 2. DND（免打扰）约束检查
 * 3. 过期时间自动清理
 * 4. 优先级分级（NORMAL/HIGH）
 * 5. 点击路由到会话服务（转 UserTurn）
 * 
 * 约束：
 * - NORMAL 级胶囊受 DND 约束（DND 模式下不显示）
 * - HIGH 级胶囊绕过 DND（重要提醒）
 * - 过期胶囊自动移除（expiresAtRealtimeNanos）
 * - 同时最多显示 3 个胶囊（优先级排序）
 * 
 * WBS 要求：
 * - NORMAL 级打扰受免打扰约束 ✅
 * - 点击转 UserTurn(command) ✅
 * - 前置依赖 A6.1 事件流 ✅（胶囊来源于事件驱动）
 */
public class SuggestionCapsuleManager {
    private static final String TAG = "SuggestionCapsuleMgr";
    
    // 优先级常量（对齐 SuggestionCapsuleData）
    public static final int PRIORITY_NORMAL = 0;
    public static final int PRIORITY_HIGH = 1;
    
    // 胶囊显示限制
    private static final int MAX_CAPSULES = 3; // 同时最多显示 3 个
    
    // 过期检查间隔
    private static final long EXPIRY_CHECK_INTERVAL_MS = 5000; // 每 5 秒检查一次过期
    
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final Handler mHandler;
    
    // 胶囊存储（按优先级排序）
    private final List<SuggestionCapsuleData> mActiveCapsules = new ArrayList<>();
    
    // UI 回调
    private CapsuleDisplayCallback mDisplayCallback;
    
    // 点击回调
    private CapsuleClickCallback mClickCallback;
    
    // 过期检查任务
    private final Runnable mExpiryCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkAndRemoveExpiredCapsules();
            if (!mActiveCapsules.isEmpty()) {
                mHandler.postDelayed(this, EXPIRY_CHECK_INTERVAL_MS);
            }
        }
    };
    
    /**
     * UI 显示回调
     */
    public interface CapsuleDisplayCallback {
        /**
         * 请求显示胶囊列表
         * 
         * @param capsules 当前有效胶囊（最多 MAX_CAPSULES 个）
         */
        void onDisplayCapsules(List<SuggestionCapsuleData> capsules);
    }
    
    /**
     * 点击回调
     */
    public interface CapsuleClickCallback {
        /**
         * 胶囊被点击
         * 
         * @param capsule 被点击的胶囊
         */
        void onCapsuleClicked(SuggestionCapsuleData capsule);
    }
    
    public SuggestionCapsuleManager(Context context) {
        mContext = context;
        mNotificationManager = (NotificationManager) context.getSystemService(
            Context.NOTIFICATION_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 设置 UI 显示回调
     */
    public void setDisplayCallback(CapsuleDisplayCallback callback) {
        mDisplayCallback = callback;
    }
    
    /**
     * 设置点击回调
     */
    public void setClickCallback(CapsuleClickCallback callback) {
        mClickCallback = callback;
    }
    
    // ==================== 核心方法：显示胶囊 ====================
    
    /**
     * 显示建议胶囊
     * 
     * D2.3 核心逻辑：
     * 1. DND 约束检查
     * 2. 过期时间校验
     * 3. 添加到队列（优先级排序）
     * 4. 触发 UI 更新
     * 
     * @param capsule 胶囊数据
     * @return true=已显示；false=被 DND 阻止或无效
     */
    public boolean showSuggestion(SuggestionCapsuleData capsule) {
        if (capsule == null) {
            Log.w(TAG, "showSuggestion: capsule is null");
            return false;
        }
        
        // 1. DND 约束检查
        if (!checkDndPolicy(capsule)) {
            Log.i(TAG, "Capsule blocked by DND: " + capsule.capsuleId);
            return false;
        }
        
        // 2. 过期校验
        long now = SystemClock.elapsedRealtimeNanos();
        if (capsule.expiresAtRealtimeNanos > 0 && 
            capsule.expiresAtRealtimeNanos <= now) {
            Log.w(TAG, "Capsule already expired: " + capsule.capsuleId);
            return false;
        }
        
        // 3. 添加到队列（按优先级排序）
        synchronized (mActiveCapsules) {
            // 去重：相同 capsuleId 覆盖
            Iterator<SuggestionCapsuleData> iter = mActiveCapsules.iterator();
            while (iter.hasNext()) {
                SuggestionCapsuleData existing = iter.next();
                if (existing.capsuleId.equals(capsule.capsuleId)) {
                    iter.remove();
                    Log.d(TAG, "Replacing existing capsule: " + capsule.capsuleId);
                    break;
                }
            }
            
            // 插入排序（HIGH 优先级在前）
            int insertIndex = 0;
            for (int i = 0; i < mActiveCapsules.size(); i++) {
                if (mActiveCapsules.get(i).priority < capsule.priority) {
                    insertIndex = i;
                    break;
                }
                insertIndex = i + 1;
            }
            mActiveCapsules.add(insertIndex, capsule);
            
            Log.i(TAG, "Capsule added: id=" + capsule.capsuleId + 
                  ", priority=" + capsule.priority + 
                  ", expires=" + (capsule.expiresAtRealtimeNanos - now) / 1_000_000_000 + "s");
        }
        
        // 4. 触发 UI 更新
        notifyDisplayUpdate();
        
        // 5. 启动过期检查（如果尚未启动）
        startExpiryCheck();
        
        return true;
    }
    
    /**
     * 移除胶囊
     */
    public void removeCapsule(String capsuleId) {
        synchronized (mActiveCapsules) {
            Iterator<SuggestionCapsuleData> iter = mActiveCapsules.iterator();
            while (iter.hasNext()) {
                if (iter.next().capsuleId.equals(capsuleId)) {
                    iter.remove();
                    Log.i(TAG, "Capsule removed: " + capsuleId);
                    notifyDisplayUpdate();
                    return;
                }
            }
        }
    }
    
    /**
     * 清空所有胶囊
     */
    public void clearAll() {
        synchronized (mActiveCapsules) {
            int count = mActiveCapsules.size();
            mActiveCapsules.clear();
            Log.i(TAG, "All capsules cleared: " + count);
        }
        notifyDisplayUpdate();
        stopExpiryCheck();
    }
    
    // ==================== DND 约束 ====================
    
    /**
     * 检查 DND 策略
     * 
     * 规则：
     * - PRIORITY_NORMAL：受 DND 约束（DND 模式下阻止）
     * - PRIORITY_HIGH：绕过 DND（重要提醒）
     * 
     * @return true=允许显示；false=被 DND 阻止
     */
    private boolean checkDndPolicy(SuggestionCapsuleData capsule) {
        // HIGH 优先级绕过 DND
        if (capsule.priority == PRIORITY_HIGH) {
            return true;
        }
        
        // NORMAL 优先级受 DND 约束
        int filterMode = mNotificationManager.getCurrentInterruptionFilter();
        boolean isDndActive = (filterMode != NotificationManager.INTERRUPTION_FILTER_ALL);
        
        if (isDndActive) {
            Log.d(TAG, "DND active, blocking NORMAL capsule: " + capsule.capsuleId);
            return false;
        }
        
        return true;
    }
    
    // ==================== 过期管理 ====================
    
    /**
     * 启动过期检查
     */
    private void startExpiryCheck() {
        mHandler.removeCallbacks(mExpiryCheckRunnable);
        mHandler.postDelayed(mExpiryCheckRunnable, EXPIRY_CHECK_INTERVAL_MS);
    }
    
    /**
     * 停止过期检查
     */
    private void stopExpiryCheck() {
        mHandler.removeCallbacks(mExpiryCheckRunnable);
    }
    
    /**
     * 检查并移除过期胶囊
     */
    private void checkAndRemoveExpiredCapsules() {
        long now = SystemClock.elapsedRealtimeNanos();
        boolean hasExpired = false;
        
        synchronized (mActiveCapsules) {
            Iterator<SuggestionCapsuleData> iter = mActiveCapsules.iterator();
            while (iter.hasNext()) {
                SuggestionCapsuleData capsule = iter.next();
                if (capsule.expiresAtRealtimeNanos > 0 && 
                    capsule.expiresAtRealtimeNanos <= now) {
                    iter.remove();
                    hasExpired = true;
                    Log.i(TAG, "Capsule expired: " + capsule.capsuleId);
                }
            }
        }
        
        if (hasExpired) {
            notifyDisplayUpdate();
        }
        
        // 如果队列为空，停止检查
        if (mActiveCapsules.isEmpty()) {
            stopExpiryCheck();
        }
    }
    
    // ==================== UI 通知 ====================
    
    /**
     * 通知 UI 更新显示
     */
    private void notifyDisplayUpdate() {
        if (mDisplayCallback == null) {
            return;
        }
        
        // 取前 MAX_CAPSULES 个胶囊
        List<SuggestionCapsuleData> displayList;
        synchronized (mActiveCapsules) {
            if (mActiveCapsules.size() <= MAX_CAPSULES) {
                displayList = new ArrayList<>(mActiveCapsules);
            } else {
                displayList = new ArrayList<>(
                    mActiveCapsules.subList(0, MAX_CAPSULES));
            }
        }
        
        mHandler.post(() -> {
            mDisplayCallback.onDisplayCapsules(displayList);
        });
    }
    
    // ==================== 点击处理 ====================
    
    /**
     * 处理胶囊点击
     * 
     * 点击后：
     * 1. 移除该胶囊
     * 2. 回调到 SessionServiceConnector（转 UserTurn）
     */
    public void handleCapsuleClick(String capsuleId) {
        SuggestionCapsuleData capsule = null;
        
        synchronized (mActiveCapsules) {
            Iterator<SuggestionCapsuleData> iter = mActiveCapsules.iterator();
            while (iter.hasNext()) {
                SuggestionCapsuleData c = iter.next();
                if (c.capsuleId.equals(capsuleId)) {
                    capsule = c;
                    iter.remove();
                    break;
                }
            }
        }
        
        if (capsule == null) {
            Log.w(TAG, "Capsule not found for click: " + capsuleId);
            return;
        }
        
        Log.i(TAG, "Capsule clicked: " + capsuleId + ", command=" + capsule.actionCommand);
        
        // 通知 UI 更新
        notifyDisplayUpdate();
        
        // 触发点击回调（路由到会话服务）
        if (mClickCallback != null) {
            mClickCallback.onCapsuleClicked(capsule);
        }
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 获取当前活跃胶囊数量
     */
    public int getActiveCapsuleCount() {
        synchronized (mActiveCapsules) {
            return mActiveCapsules.size();
        }
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        stopExpiryCheck();
        synchronized (mActiveCapsules) {
            mActiveCapsules.clear();
        }
        Log.i(TAG, "SuggestionCapsuleManager destroyed");
    }
}
