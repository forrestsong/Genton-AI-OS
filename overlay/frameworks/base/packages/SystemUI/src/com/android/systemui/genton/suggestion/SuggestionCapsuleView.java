// AI Native OS - Suggestion Capsule View (D2.3)
package com.android.systemui.genton.suggestion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.genton.ui.SuggestionCapsuleData;

/**
 * 建议胶囊视图（D2.3）
 * 
 * 视觉设计：
 * - 圆角矩形背景（8dp）
 * - 标题 + 摘要布局（垂直排列）
 * - 优先级颜色区分（NORMAL=蓝色，HIGH=橙色）
 * - 入场动画：淡入 + 从上滑入（200ms）
 * - 出场动画：淡出 + 向上滑出（150ms）
 * - 过期倒计时：最后 10 秒显示进度条
 * 
 * 交互：
 * - 点击：触发回调，移除胶囊
 * - 长按：无操作（未来可扩展为"静音"）
 */
public class SuggestionCapsuleView extends LinearLayout {
    private static final String TAG = "SuggestionCapsuleView";
    
    // 视觉参数
    private static final int CORNER_RADIUS_DP = 8;
    private static final int PADDING_HORIZONTAL_DP = 16;
    private static final int PADDING_VERTICAL_DP = 12;
    
    // 颜色（优先级）
    private static final int COLOR_NORMAL = 0xFF2196F3; // 蓝色
    private static final int COLOR_HIGH = 0xFFFF9800;   // 橙色
    
    // 动画时长
    private static final int ANIM_ENTER_DURATION = 200;
    private static final int ANIM_EXIT_DURATION = 150;
    
    // 过期倒计时阈值（最后 10 秒显示进度条）
    private static final long COUNTDOWN_THRESHOLD_NANOS = 10_000_000_000L; // 10秒
    
    private final Paint mBackgroundPaint;
    private final Paint mProgressPaint;
    private final RectF mBackgroundRect = new RectF();
    private final RectF mProgressRect = new RectF();
    
    private TextView mTitleText;
    private TextView mSummaryText;
    
    private SuggestionCapsuleData mCapsuleData;
    private OnCapsuleClickListener mClickListener;
    
    // 倒计时
    private float mCountdownProgress = 0f; // 0.0 ~ 1.0
    private boolean mShowCountdown = false;
    
    /**
     * 点击回调
     */
    public interface OnCapsuleClickListener {
        void onCapsuleClick(String capsuleId);
    }
    
    public SuggestionCapsuleView(Context context) {
        this(context, null);
    }
    
    public SuggestionCapsuleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        setOrientation(VERTICAL);
        setWillNotDraw(false); // 允许 onDraw
        
        // 背景画笔
        mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBackgroundPaint.setColor(COLOR_NORMAL);
        
        // 进度条画笔
        mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mProgressPaint.setColor(0x80FFFFFF); // 半透明白色
        
        // 内边距
        int paddingH = dpToPx(PADDING_HORIZONTAL_DP);
        int paddingV = dpToPx(PADDING_VERTICAL_DP);
        setPadding(paddingH, paddingV, paddingH, paddingV);
        
        // 创建子视图
        createChildViews();
        
        // 点击监听
        setOnClickListener(v -> {
            if (mClickListener != null && mCapsuleData != null) {
                mClickListener.onCapsuleClick(mCapsuleData.capsuleId);
            }
        });
        
        // 设置可点击
        setClickable(true);
        setFocusable(true);
    }
    
    /**
     * 创建子视图
     */
    private void createChildViews() {
        // 标题
        mTitleText = new TextView(getContext());
        mTitleText.setTextColor(Color.WHITE);
        mTitleText.setTextSize(14);
        mTitleText.setMaxLines(1);
        addView(mTitleText, new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ));
        
        // 摘要
        mSummaryText = new TextView(getContext());
        mSummaryText.setTextColor(0xE0FFFFFF); // 稍透明
        mSummaryText.setTextSize(12);
        mSummaryText.setMaxLines(2);
        LayoutParams summaryParams = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dpToPx(4);
        addView(mSummaryText, summaryParams);
    }
    
    /**
     * 绑定胶囊数据
     */
    public void bind(SuggestionCapsuleData data) {
        mCapsuleData = data;
        
        // 更新文本
        mTitleText.setText(data.title);
        mSummaryText.setText(data.summaryMasked);
        
        // 更新颜色
        int color = (data.priority == SuggestionCapsuleManager.PRIORITY_HIGH) ? 
            COLOR_HIGH : COLOR_NORMAL;
        mBackgroundPaint.setColor(color);
        
        // 重绘
        invalidate();
    }
    
    /**
     * 设置倒计时进度
     * 
     * @param progress 0.0 ~ 1.0（1.0=即将过期）
     */
    public void setCountdownProgress(float progress) {
        mCountdownProgress = progress;
        mShowCountdown = (progress > 0f);
        invalidate();
    }
    
    /**
     * 设置点击监听
     */
    public void setOnCapsuleClickListener(OnCapsuleClickListener listener) {
        mClickListener = listener;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        // 绘制背景（圆角矩形）
        mBackgroundRect.set(0, 0, getWidth(), getHeight());
        float radius = dpToPx(CORNER_RADIUS_DP);
        canvas.drawRoundRect(mBackgroundRect, radius, radius, mBackgroundPaint);
        
        // 绘制倒计时进度条（底部）
        if (mShowCountdown && mCountdownProgress > 0f) {
            float progressWidth = getWidth() * mCountdownProgress;
            mProgressRect.set(0, getHeight() - dpToPx(2), progressWidth, getHeight());
            canvas.drawRoundRect(mProgressRect, radius, radius, mProgressPaint);
        }
        
        super.onDraw(canvas);
    }
    
    // ==================== 动画 ====================
    
    /**
     * 播放入场动画
     */
    public void playEnterAnimation() {
        setAlpha(0f);
        setTranslationY(-dpToPx(20));
        
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIM_ENTER_DURATION)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .start();
    }
    
    /**
     * 播放出场动画
     */
    public void playExitAnimation(Runnable onEnd) {
        animate()
            .alpha(0f)
            .translationY(-dpToPx(20))
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
     * dp 转 px
     */
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
