package com.android.server.genton;

import android.content.Context;
import android.util.Slog;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 捕获降采样管理器
 * 
 * 核心职责：
 * 1. 管理 UI 树/截图捕获的降采样策略（scaleHint）
 * 2. 根据任务负载动态调整采样率
 * 3. 与帧率纪律协同，降低感知层开销
 * 4. 任务完成后自动恢复原始采样率
 * 
 * 降采样策略：
 * - SCALE_FULL (1.0): 全分辨率，无降采样
 * - SCALE_HIGH (0.75): 高质量，75% 采样
 * - SCALE_MEDIUM (0.5): 中等质量，50% 采样
 * - SCALE_LOW (0.25): 低质量，25% 采样
 * - SCALE_MINIMAL (0.1): 最低质量，10% 采样（极端场景）
 * 
 * 使用场景：
 * - 高频捕获（连续截图/UI 树刷新）：SCALE_MEDIUM
 * - 后台任务（非交互式规划）：SCALE_LOW
 * - 快速扫描（寻找特定元素）：SCALE_LOW
 * - 精细操作（OCR/表单识别）：SCALE_HIGH/SCALE_FULL
 * 
 * 性能收益：
 * - SCALE_MEDIUM: 节省 ~50% 捕获时间和内存
 * - SCALE_LOW: 节省 ~75% 捕获时间和内存
 * 
 * 对应任务：A2.5
 * 前置依赖：A2.2 (UI 树服务)
 * 草案来源：D9 §6h
 * 
 * @author AI Native OS Team
 * @since A2 (读-写-界闭环)
 */
public class CaptureDownsamplingManager {
    private static final String TAG = "CaptureDownsamplingManager";
    
    // 降采样级别常量
    public static final int SCALE_FULL = 0;      // 1.0
    public static final int SCALE_HIGH = 1;      // 0.75
    public static final int SCALE_MEDIUM = 2;    // 0.5
    public static final int SCALE_LOW = 3;       // 0.25
    public static final int SCALE_MINIMAL = 4;   // 0.1
    
    // 降采样比例映射
    private static final float[] SCALE_FACTORS = {
        1.0f,   // SCALE_FULL
        0.75f,  // SCALE_HIGH
        0.5f,   // SCALE_MEDIUM
        0.25f,  // SCALE_LOW
        0.1f    // SCALE_MINIMAL
    };
    
    private final Context mContext;
    
    // 会话级降采样配置（sessionId → ScaleHint）
    private final ConcurrentHashMap<String, ScaleHint> mSessionScaleHints;
    
    // 全局默认降采样级别
    private volatile int mGlobalDefaultScale = SCALE_FULL;
    
    // 任务级降采样绑定（intentId → ScaleHint）
    private final ConcurrentHashMap<String, ScaleHint> mTaskScaleHints;
    
    public CaptureDownsamplingManager(Context context) {
        mContext = context;
        mSessionScaleHints = new ConcurrentHashMap<>();
        mTaskScaleHints = new ConcurrentHashMap<>();
        
        Slog.i(TAG, "CaptureDownsamplingManager initialized");
    }
    
    /**
     * 设置会话级降采样提示
     * 
     * @param sessionId 会话 ID（通常为 agentId 或捕获会话标识）
     * @param scaleLevel 降采样级别（SCALE_FULL ~ SCALE_MINIMAL）
     * @param reason 降采样原因（用于审计）
     */
    public void setSessionScaleHint(String sessionId, int scaleLevel, String reason) {
        if (sessionId == null) {
            Slog.w(TAG, "Invalid sessionId: null");
            return;
        }
        
        if (scaleLevel < SCALE_FULL || scaleLevel > SCALE_MINIMAL) {
            Slog.w(TAG, "Invalid scale level: " + scaleLevel);
            return;
        }
        
        ScaleHint hint = new ScaleHint(scaleLevel, reason, System.currentTimeMillis());
        mSessionScaleHints.put(sessionId, hint);
        
        Slog.i(TAG, String.format("Session scale hint set: session=%s, level=%d (%.2f), reason=%s",
            sessionId, scaleLevel, SCALE_FACTORS[scaleLevel], reason));
    }
    
    /**
     * 设置任务级降采样提示（任务完成后自动清除）
     * 
     * @param intentId 意图 ID
     * @param scaleLevel 降采样级别
     * @param reason 降采样原因
     */
    public void setTaskScaleHint(String intentId, int scaleLevel, String reason) {
        if (intentId == null) {
            Slog.w(TAG, "Invalid intentId: null");
            return;
        }
        
        if (scaleLevel < SCALE_FULL || scaleLevel > SCALE_MINIMAL) {
            Slog.w(TAG, "Invalid scale level: " + scaleLevel);
            return;
        }
        
        ScaleHint hint = new ScaleHint(scaleLevel, reason, System.currentTimeMillis());
        mTaskScaleHints.put(intentId, hint);
        
        Slog.i(TAG, String.format("Task scale hint set: intent=%s, level=%d (%.2f), reason=%s",
            intentId, scaleLevel, SCALE_FACTORS[scaleLevel], reason));
    }
    
    /**
     * 清除任务级降采样提示（任务完成时调用）
     * 
     * @param intentId 意图 ID
     */
    public void clearTaskScaleHint(String intentId) {
        if (intentId == null) {
            return;
        }
        
        ScaleHint removed = mTaskScaleHints.remove(intentId);
        if (removed != null) {
            Slog.i(TAG, "Task scale hint cleared: intent=" + intentId);
        }
    }
    
    /**
     * 清除会话级降采样提示
     * 
     * @param sessionId 会话 ID
     */
    public void clearSessionScaleHint(String sessionId) {
        if (sessionId == null) {
            return;
        }
        
        ScaleHint removed = mSessionScaleHints.remove(sessionId);
        if (removed != null) {
            Slog.i(TAG, "Session scale hint cleared: session=" + sessionId);
        }
    }
    
    /**
     * 获取当前生效的降采样因子
     * 
     * 优先级：任务级 > 会话级 > 全局默认
     * 
     * @param sessionId 会话 ID
     * @param intentId 意图 ID（可选）
     * @return 降采样因子（0.1 ~ 1.0）
     */
    public float getEffectiveScaleFactor(String sessionId, String intentId) {
        // 1. 优先检查任务级提示
        if (intentId != null) {
            ScaleHint taskHint = mTaskScaleHints.get(intentId);
            if (taskHint != null) {
                return SCALE_FACTORS[taskHint.scaleLevel];
            }
        }
        
        // 2. 检查会话级提示
        if (sessionId != null) {
            ScaleHint sessionHint = mSessionScaleHints.get(sessionId);
            if (sessionHint != null) {
                return SCALE_FACTORS[sessionHint.scaleLevel];
            }
        }
        
        // 3. 使用全局默认
        return SCALE_FACTORS[mGlobalDefaultScale];
    }
    
    /**
     * 获取当前生效的降采样级别
     * 
     * @param sessionId 会话 ID
     * @param intentId 意图 ID（可选）
     * @return 降采样级别（SCALE_FULL ~ SCALE_MINIMAL）
     */
    public int getEffectiveScaleLevel(String sessionId, String intentId) {
        // 1. 优先检查任务级提示
        if (intentId != null) {
            ScaleHint taskHint = mTaskScaleHints.get(intentId);
            if (taskHint != null) {
                return taskHint.scaleLevel;
            }
        }
        
        // 2. 检查会话级提示
        if (sessionId != null) {
            ScaleHint sessionHint = mSessionScaleHints.get(sessionId);
            if (sessionHint != null) {
                return sessionHint.scaleLevel;
            }
        }
        
        // 3. 使用全局默认
        return mGlobalDefaultScale;
    }
    
    /**
     * 设置全局默认降采样级别
     * 
     * @param scaleLevel 降采样级别
     */
    public void setGlobalDefaultScale(int scaleLevel) {
        if (scaleLevel < SCALE_FULL || scaleLevel > SCALE_MINIMAL) {
            Slog.w(TAG, "Invalid global scale level: " + scaleLevel);
            return;
        }
        
        mGlobalDefaultScale = scaleLevel;
        Slog.i(TAG, String.format("Global default scale set: level=%d (%.2f)",
            scaleLevel, SCALE_FACTORS[scaleLevel]));
    }
    
    /**
     * 根据捕获场景推荐降采样级别
     * 
     * @param captureType 捕获类型（"ui_tree"、"screenshot"、"ocr"等）
     * @param frequency 捕获频率（Hz）
     * @param isInteractive 是否交互式
     * @return 推荐的降采样级别
     */
    public int recommendScaleLevel(String captureType, float frequency, boolean isInteractive) {
        // OCR/表单识别：需要高质量
        if ("ocr".equals(captureType) || "form".equals(captureType)) {
            return SCALE_HIGH;
        }
        
        // 高频捕获（> 2 Hz）：中等质量
        if (frequency > 2.0f) {
            return SCALE_MEDIUM;
        }
        
        // 非交互式：低质量
        if (!isInteractive) {
            return SCALE_LOW;
        }
        
        // 默认：全分辨率
        return SCALE_FULL;
    }
    
    /**
     * 计算降采样后的尺寸
     * 
     * @param originalWidth 原始宽度
     * @param originalHeight 原始高度
     * @param scaleFactor 降采样因子
     * @return [缩放后宽度, 缩放后高度]
     */
    public int[] calculateDownsampledSize(int originalWidth, int originalHeight, float scaleFactor) {
        int scaledWidth = (int) (originalWidth * scaleFactor);
        int scaledHeight = (int) (originalHeight * scaleFactor);
        
        // 确保至少 1x1
        scaledWidth = Math.max(1, scaledWidth);
        scaledHeight = Math.max(1, scaledHeight);
        
        return new int[]{scaledWidth, scaledHeight};
    }
    
    /**
     * 获取当前统计信息
     * 
     * @return 统计信息字符串
     */
    public String getStats() {
        return String.format("CaptureDownsamplingManager Stats: sessions=%d, tasks=%d, globalDefault=%.2f",
            mSessionScaleHints.size(),
            mTaskScaleHints.size(),
            SCALE_FACTORS[mGlobalDefaultScale]);
    }
    
    // ===== 内部数据结构 =====
    
    /**
     * 降采样提示
     */
    private static class ScaleHint {
        final int scaleLevel;
        final String reason;
        final long setAtMs;
        
        ScaleHint(int scaleLevel, String reason, long setAtMs) {
            this.scaleLevel = scaleLevel;
            this.reason = reason;
            this.setAtMs = setAtMs;
        }
    }
}
