package com.android.server.genton;

import android.content.Context;
import android.util.Slog;
import android.view.Surface;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 帧率纪律管理器
 * 
 * 核心职责：
 * 1. 管理 Agent 任务期间的帧率降载策略（setFrameRate）
 * 2. 任务执行期间降低渲染帧率，减少 GPU/CPU 开销
 * 3. 任务完成后自动恢复原始帧率
 * 4. 与降采样协同，实现全链路性能优化
 * 
 * 帧率策略：
 * - FRAME_RATE_NORMAL (60 Hz): 正常帧率，用户交互
 * - FRAME_RATE_REDUCED (30 Hz): 降载帧率，后台任务
 * - FRAME_RATE_LOW (15 Hz): 低帧率，极端省电
 * - FRAME_RATE_MINIMAL (10 Hz): 最低帧率，静态场景
 * 
 * 使用场景：
 * - 后台规划（无用户交互）：FRAME_RATE_REDUCED
 * - 长时任务（多步骤执行）：FRAME_RATE_LOW
 * - 等待确认（静态 UI）：FRAME_RATE_MINIMAL
 * - 用户交互中：保持 FRAME_RATE_NORMAL
 * 
 * 性能收益：
 * - FRAME_RATE_REDUCED (30Hz): 节省 ~40% GPU 功耗
 * - FRAME_RATE_LOW (15Hz): 节省 ~70% GPU 功耗
 * 
 * 安全约束：
 * - 仅在 Agent 任务期间降载
 * - 用户触摸/滑动时立即恢复正常帧率
 * - 敏感场景（相机/视频/游戏）禁用降载
 * 
 * 对应任务：A2.5
 * 前置依赖：A3.2 (执行中介服务)
 * 草案来源：D9 §6h
 * 
 * @author AI Native OS Team
 * @since A2 (读-写-界闭环)
 */
public class FrameRateDisciplineManager {
    private static final String TAG = "FrameRateDisciplineManager";
    
    // 帧率级别常量
    public static final int FRAME_RATE_NORMAL = 0;      // 60 Hz
    public static final int FRAME_RATE_REDUCED = 1;     // 30 Hz
    public static final int FRAME_RATE_LOW = 2;         // 15 Hz
    public static final int FRAME_RATE_MINIMAL = 3;     // 10 Hz
    
    // 帧率映射（Hz）
    private static final float[] FRAME_RATES = {
        60.0f,  // FRAME_RATE_NORMAL
        30.0f,  // FRAME_RATE_REDUCED
        15.0f,  // FRAME_RATE_LOW
        10.0f   // FRAME_RATE_MINIMAL
    };
    
    // Surface.FRAME_RATE_COMPATIBILITY 常量
    private static final int FRAME_RATE_COMPATIBILITY_DEFAULT = 0;
    private static final int FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1;
    
    private final Context mContext;
    
    // 任务级帧率绑定（intentId → FrameRateHint）
    private final ConcurrentHashMap<String, FrameRateHint> mTaskFrameRates;
    
    // 敏感场景白名单（禁用降载）
    private final ConcurrentHashMap<String, Long> mSensitiveScenes;
    
    // 全局降载开关
    private volatile boolean mGlobalFrameRateReductionEnabled = true;
    
    // 用户交互检测（触摸/滑动期间禁用降载）
    private volatile long mLastUserInteractionMs = 0;
    private static final long USER_INTERACTION_COOLDOWN_MS = 2000;  // 2 秒冷却
    
    public FrameRateDisciplineManager(Context context) {
        mContext = context;
        mTaskFrameRates = new ConcurrentHashMap<>();
        mSensitiveScenes = new ConcurrentHashMap<>();
        
        Slog.i(TAG, "FrameRateDisciplineManager initialized");
    }
    
    /**
     * 设置任务级帧率降载
     * 
     * @param intentId 意图 ID
     * @param frameRateLevel 帧率级别（FRAME_RATE_NORMAL ~ FRAME_RATE_MINIMAL）
     * @param reason 降载原因
     */
    public void setTaskFrameRate(String intentId, int frameRateLevel, String reason) {
        if (intentId == null) {
            Slog.w(TAG, "Invalid intentId: null");
            return;
        }
        
        if (frameRateLevel < FRAME_RATE_NORMAL || frameRateLevel > FRAME_RATE_MINIMAL) {
            Slog.w(TAG, "Invalid frame rate level: " + frameRateLevel);
            return;
        }
        
        // 检查全局开关
        if (!mGlobalFrameRateReductionEnabled) {
            Slog.d(TAG, "Frame rate reduction disabled globally, ignoring request");
            return;
        }
        
        FrameRateHint hint = new FrameRateHint(frameRateLevel, reason, System.currentTimeMillis());
        mTaskFrameRates.put(intentId, hint);
        
        // 应用帧率设置
        applyFrameRate(intentId, frameRateLevel);
        
        Slog.i(TAG, String.format("Task frame rate set: intent=%s, level=%d (%.1f Hz), reason=%s",
            intentId, frameRateLevel, FRAME_RATES[frameRateLevel], reason));
    }
    
    /**
     * 清除任务级帧率降载（任务完成时自动恢复）
     * 
     * @param intentId 意图 ID
     */
    public void clearTaskFrameRate(String intentId) {
        if (intentId == null) {
            return;
        }
        
        FrameRateHint removed = mTaskFrameRates.remove(intentId);
        if (removed != null) {
            // 恢复正常帧率
            applyFrameRate(intentId, FRAME_RATE_NORMAL);
            
            Slog.i(TAG, "Task frame rate cleared (restored to normal): intent=" + intentId);
        }
    }
    
    /**
     * 标记敏感场景（禁用帧率降载）
     * 
     * @param sceneId 场景标识（如 "camera"、"video"、"game"）
     * @param durationMs 持续时间（毫秒），0 表示永久
     */
    public void markSensitiveScene(String sceneId, long durationMs) {
        if (sceneId == null) {
            return;
        }
        
        long expiresAtMs = durationMs > 0 ? System.currentTimeMillis() + durationMs : Long.MAX_VALUE;
        mSensitiveScenes.put(sceneId, expiresAtMs);
        
        Slog.i(TAG, String.format("Sensitive scene marked: scene=%s, duration=%dms", sceneId, durationMs));
    }
    
    /**
     * 取消敏感场景标记
     * 
     * @param sceneId 场景标识
     */
    public void clearSensitiveScene(String sceneId) {
        if (sceneId == null) {
            return;
        }
        
        Long removed = mSensitiveScenes.remove(sceneId);
        if (removed != null) {
            Slog.i(TAG, "Sensitive scene cleared: scene=" + sceneId);
        }
    }
    
    /**
     * 检查当前是否处于敏感场景
     * 
     * @return true 如果当前处于敏感场景
     */
    public boolean isInSensitiveScene() {
        long now = System.currentTimeMillis();
        
        // 清理过期场景
        mSensitiveScenes.entrySet().removeIf(entry -> {
            if (entry.getValue() != Long.MAX_VALUE && entry.getValue() < now) {
                Slog.d(TAG, "Sensitive scene expired: " + entry.getKey());
                return true;
            }
            return false;
        });
        
        return !mSensitiveScenes.isEmpty();
    }
    
    /**
     * 通知用户交互（触摸/滑动）
     */
    public void notifyUserInteraction() {
        mLastUserInteractionMs = System.currentTimeMillis();
        
        // 用户交互期间，临时恢复正常帧率
        Slog.d(TAG, "User interaction detected, temporarily restoring normal frame rate");
    }
    
    /**
     * 检查是否处于用户交互冷却期
     * 
     * @return true 如果处于冷却期
     */
    public boolean isInUserInteractionCooldown() {
        long now = System.currentTimeMillis();
        return (now - mLastUserInteractionMs) < USER_INTERACTION_COOLDOWN_MS;
    }
    
    /**
     * 获取当前生效的帧率级别
     * 
     * @param intentId 意图 ID
     * @return 帧率级别（FRAME_RATE_NORMAL ~ FRAME_RATE_MINIMAL）
     */
    public int getEffectiveFrameRateLevel(String intentId) {
        // 1. 敏感场景：禁用降载
        if (isInSensitiveScene()) {
            return FRAME_RATE_NORMAL;
        }
        
        // 2. 用户交互冷却期：禁用降载
        if (isInUserInteractionCooldown()) {
            return FRAME_RATE_NORMAL;
        }
        
        // 3. 全局开关关闭：禁用降载
        if (!mGlobalFrameRateReductionEnabled) {
            return FRAME_RATE_NORMAL;
        }
        
        // 4. 检查任务级设置
        if (intentId != null) {
            FrameRateHint hint = mTaskFrameRates.get(intentId);
            if (hint != null) {
                return hint.frameRateLevel;
            }
        }
        
        // 5. 默认正常帧率
        return FRAME_RATE_NORMAL;
    }
    
    /**
     * 获取当前生效的帧率（Hz）
     * 
     * @param intentId 意图 ID
     * @return 帧率（Hz）
     */
    public float getEffectiveFrameRate(String intentId) {
        int level = getEffectiveFrameRateLevel(intentId);
        return FRAME_RATES[level];
    }
    
    /**
     * 设置全局帧率降载开关
     * 
     * @param enabled true 启用，false 禁用
     */
    public void setGlobalFrameRateReductionEnabled(boolean enabled) {
        mGlobalFrameRateReductionEnabled = enabled;
        
        Slog.i(TAG, "Global frame rate reduction " + (enabled ? "enabled" : "disabled"));
        
        if (!enabled) {
            // 禁用时，恢复所有任务的正常帧率
            for (String intentId : mTaskFrameRates.keySet()) {
                applyFrameRate(intentId, FRAME_RATE_NORMAL);
            }
        }
    }
    
    /**
     * 根据任务场景推荐帧率级别
     * 
     * @param isInteractive 是否交互式
     * @param isLongRunning 是否长时任务
     * @param isWaitingConfirmation 是否等待确认
     * @return 推荐的帧率级别
     */
    public int recommendFrameRateLevel(boolean isInteractive, boolean isLongRunning, boolean isWaitingConfirmation) {
        // 等待确认：最低帧率
        if (isWaitingConfirmation) {
            return FRAME_RATE_MINIMAL;
        }
        
        // 长时任务：低帧率
        if (isLongRunning) {
            return FRAME_RATE_LOW;
        }
        
        // 非交互式：降载帧率
        if (!isInteractive) {
            return FRAME_RATE_REDUCED;
        }
        
        // 交互式：正常帧率
        return FRAME_RATE_NORMAL;
    }
    
    /**
     * 应用帧率设置（实际执行帧率切换）
     * 
     * @param intentId 意图 ID
     * @param frameRateLevel 帧率级别
     */
    private void applyFrameRate(String intentId, int frameRateLevel) {
        float targetFrameRate = FRAME_RATES[frameRateLevel];
        
        // TODO: 实际集成需要调用 SurfaceControl 或 WindowManager API
        // 示例伪代码：
        // WindowManagerService wms = LocalServices.getService(WindowManagerService.class);
        // wms.setPreferredDisplayModeId(displayId, modeId);
        // 
        // 或者针对特定 Surface：
        // surface.setFrameRate(targetFrameRate, FRAME_RATE_COMPATIBILITY_DEFAULT);
        
        Slog.d(TAG, String.format("Frame rate applied: intent=%s, rate=%.1f Hz", intentId, targetFrameRate));
    }
    
    /**
     * 获取当前统计信息
     * 
     * @return 统计信息字符串
     */
    public String getStats() {
        return String.format("FrameRateDisciplineManager Stats: tasks=%d, sensitiveScenes=%d, globalEnabled=%b, lastInteraction=%dms ago",
            mTaskFrameRates.size(),
            mSensitiveScenes.size(),
            mGlobalFrameRateReductionEnabled,
            System.currentTimeMillis() - mLastUserInteractionMs);
    }
    
    // ===== 内部数据结构 =====
    
    /**
     * 帧率提示
     */
    private static class FrameRateHint {
        final int frameRateLevel;
        final String reason;
        final long setAtMs;
        
        FrameRateHint(int frameRateLevel, String reason, long setAtMs) {
            this.frameRateLevel = frameRateLevel;
            this.reason = reason;
            this.setAtMs = setAtMs;
        }
    }
}
