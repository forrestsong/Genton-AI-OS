// AI Native OS - Activity Indicator Chip View (D3.2)
package com.android.systemui.genton.indicator;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 活性指示器芯片视图（D3.2）
 * 
 * 视觉设计：
 * - 胶囊形状（圆角矩形，高度 28dp）
 * - 图标 + 文本布局（水平排列）
 * - 类型颜色区分：
 *   - SCREEN_READ（读屏）：蓝色 #2196F3
 *   - INJECT（操作）：橙色 #FF9800
 *   - RECORDING（录音）：红色 #F44336
 * - 脉冲动画：每 2 秒缩放一次（提示用户关注）
 * 
 * 入场/出场动画：
 * - 入场：淡入 + 从右滑入（200ms）
 * - 出场：淡出 + 向右滑出（150ms）
 * 
 * 交互：
 * - 点击：展开详情列表（显示具体 Agent）
 * - 长按：快速停止（暂未实现）
 */
public class ActivityIndicatorChip extends LinearLayout {
    private static final String TAG = "ActivityIndicatorChip";
    
    // 视觉参数
    private static final int HEIGHT_DP = 28;
    private static final int CORNER_RADIUS_DP = 14; // 半圆角
    private static final int PADDING_HORIZONTAL_DP = 12;
    private static final int ICON_SIZE_DP = 16;
    private static final int SPACING_DP = 6;
    
    // 动画时长
    private static final int ANIM_ENTER_DURATION = 200;
    private static final int ANIM_EXIT_DURATION = 150;
    private static final int PULSE_DURATION = 600;
    private static final long PULSE_INTERVAL = 2000; // 每 2 秒脉冲一次
    
    // 颜色（按类型）
    private static final int COLOR_SCREEN_READ = 0xFF2196F3;  // 蓝色
    private static final int COLOR_INJECT = 0xFFFF9800;       // 橙色
    private static final int COLOR_RECORDING = 0xFFF44336;    // 红色
    
    private final Paint mBackgroundPaint;
    private final RectF mBackgroundRect = new RectF();
    
    private TextView mTextView;
    
    private int mKind;
    private int mAgentCount;
    private OnChipClickListener mClickListener;
    
    // 脉冲动画
    private boolean mPulseEnabled = true;
    private final Runnable mPulseRunnable = new Runnable() {
        @Override
        public void run() {
            if (mPulseEnabled && getVisibility() == VISIBLE) {
                playPulseAnimation();
                postDelayed(this, PULSE_INTERVAL);
            }
        }
    };
    
    /**
     * 点击回调
     */
    public interface OnChipClickListener {
        void onChipClick(int kind);
    }
    
    public ActivityIndicatorChip(Context context) {
        this(context, null);
    }
    
    public ActivityIndicatorChip(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setWillNotDraw(false);
        
        // 背景画笔
        mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBackgroundPaint.setColor(COLOR_SCREEN_READ);
        
        // 内边距
        int paddingH = dpToPx(PADDING_HORIZONTAL_DP);
        int height = dpToPx(HEIGHT_DP);
        setPadding(paddingH, 0, paddingH, 0);
        setMinimumHeight(height);
        
        // 创建文本视图
        createTextView();
        
        // 点击监听
        setOnClickListener(v -> {
            if (mClickListener != null) {
                mClickListener.onChipClick(mKind);
            }
        });
        
        setClickable(true);
        setFocusable(true);
    }
    
    /**
     * 创建文本视图
     */
    private void createTextView() {
        mTextView = new TextView(getContext());
        mTextView.setTextColor(Color.WHITE);
        mTextView.setTextSize(12);
        mTextView.setMaxLines(1);
        addView(mTextView, new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ));
    }
    
    /**
     * 设置芯片数据
     * 
     * @param kind 指示器类型（SCREEN_READ/INJECT/RECORDING）
     * @param agentCount 活跃 Agent 数量
     */
    public void setData(int kind, int agentCount) {
        mKind = kind;
        mAgentCount = agentCount;
        
        // 更新颜色
        int color = getColorForKind(kind);
        mBackgroundPaint.setColor(color);
        
        // 更新文本
        String text = getTextForKind(kind, agentCount);
        mTextView.setText(text);
        
        invalidate();
    }
    
    /**
     * 获取类型颜色
     */
    private int getColorForKind(int kind) {
        switch (kind) {
            case ActivityIndicatorManager.KIND_SCREEN_READ:
                return COLOR_SCREEN_READ;
            case ActivityIndicatorManager.KIND_INJECT:
                return COLOR_INJECT;
            case ActivityIndicatorManager.KIND_RECORDING:
                return COLOR_RECORDING;
            default:
                return COLOR_SCREEN_READ;
        }
    }
    
    /**
     * 获取显示文本
     */
    private String getTextForKind(int kind, int agentCount) {
        String base;
        switch (kind) {
            case ActivityIndicatorManager.KIND_SCREEN_READ:
                base = "正在读屏";
                break;
            case ActivityIndicatorManager.KIND_INJECT:
                base = "正在操作";
                break;
            case ActivityIndicatorManager.KIND_RECORDING:
                base = "正在录音";
                break;
            default:
                base = "活动中";
                break;
        }
        
        if (agentCount > 1) {
            return base + " (" + agentCount + ")";
        } else {
            return base;
        }
    }
    
    /**
     * 设置点击监听
     */
    public void setOnChipClickListener(OnChipClickListener listener) {
        mClickListener = listener;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        // 绘制背景（胶囊形状）
        mBackgroundRect.set(0, 0, getWidth(), getHeight());
        float radius = getHeight() / 2f; // 半圆角
        canvas.drawRoundRect(mBackgroundRect, radius, radius, mBackgroundPaint);
        
        super.onDraw(canvas);
    }
    
    // ==================== 动画 ====================
    
    /**
     * 播放入场动画
     */
    public void playEnterAnimation() {
        setAlpha(0f);
        setTranslationX(dpToPx(50)); // 从右边滑入
        
        animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(ANIM_ENTER_DURATION)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // 启动脉冲动画
                    startPulseAnimation();
                }
            })
            .start();
    }
    
    /**
     * 播放出场动画
     */
    public void playExitAnimation(Runnable onEnd) {
        stopPulseAnimation();
        
        animate()
            .alpha(0f)
            .translationX(dpToPx(50)) // 向右边滑出
            .setDuration(ANIM_EXIT_DURATION)
            .setInterpolator(new android.view.animation.AccelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (onEnd != null) {
                        onEnd.run();
                    }
                }
            })
            .start();
    }
    
    /**
     * 启动脉冲动画
     */
    private void startPulseAnimation() {
        mPulseEnabled = true;
        removeCallbacks(mPulseRunnable);
        postDelayed(mPulseRunnable, PULSE_INTERVAL);
    }
    
    /**
     * 停止脉冲动画
     */
    private void stopPulseAnimation() {
        mPulseEnabled = false;
        removeCallbacks(mPulseRunnable);
    }
    
    /**
     * 播放单次脉冲动画
     */
    private void playPulseAnimation() {
        animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(PULSE_DURATION / 2)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(PULSE_DURATION / 2)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .setListener(null)
                        .start();
                }
            })
            .start();
    }
    
    /**
     * dp 转 px
     */
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulseAnimation();
    }
}
