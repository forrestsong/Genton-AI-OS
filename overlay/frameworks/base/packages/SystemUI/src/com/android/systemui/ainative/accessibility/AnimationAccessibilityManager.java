// AI Native OS - Animation and Accessibility Manager
package com.android.systemui.ainative.accessibility;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * 动效与可访问性管理器（D8.1）
 * 
 * 职责：
 * - 切换动画（<150ms要求）
 * - 文本放大支持
 * - 无障碍服务兼容
 * - 动画禁用模式（系统无障碍设置）
 */
public class AnimationAccessibilityManager {
    private static final String TAG = "AnimationAccessibilityMgr";
    
    // 动画时长（符合D8.1要求：<150ms）
    public static final long TRANSITION_DURATION = 150;
    public static final long FADE_DURATION = 120;
    public static final long SLIDE_DURATION = 140;
    
    private final Context mContext;
    private final Interpolator mDefaultInterpolator;
    
    private boolean mIsReduceMotionEnabled = false;
    private float mTextScaleFactor = 1.0f;
    
    public AnimationAccessibilityManager(Context context) {
        mContext = context;
        mDefaultInterpolator = new AccelerateDecelerateInterpolator();
        
        // 读取系统无障碍设置
        loadAccessibilitySettings();
    }
    
    /**
     * 加载无障碍设置
     */
    private void loadAccessibilitySettings() {
        try {
            // 检查是否启用"减少动画"
            float animatorDurationScale = Settings.Global.getFloat(
                mContext.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            );
            mIsReduceMotionEnabled = (animatorDurationScale == 0f);
            
            // 读取文本缩放因子
            mTextScaleFactor = Settings.System.getFloat(
                mContext.getContentResolver(),
                Settings.System.FONT_SCALE,
                1.0f
            );
            
            Log.i(TAG, "Accessibility settings loaded: reduceMotion=" + mIsReduceMotionEnabled + 
                ", textScale=" + mTextScaleFactor);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load accessibility settings", e);
        }
    }
    
    /**
     * 获取实际动画时长（考虑无障碍设置）
     */
    public long getActualDuration(long baseDuration) {
        if (mIsReduceMotionEnabled) {
            return 0; // 无动画
        }
        return baseDuration;
    }
    
    /**
     * 淡入淡出动画
     */
    public void fadeIn(View view, AnimationCallback callback) {
        long duration = getActualDuration(FADE_DURATION);
        
        if (duration == 0) {
            // 无动画模式
            view.setAlpha(1f);
            view.setVisibility(View.VISIBLE);
            if (callback != null) {
                callback.onAnimationEnd();
            }
            return;
        }
        
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(mDefaultInterpolator)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (callback != null) {
                        callback.onAnimationEnd();
                    }
                }
            })
            .start();
    }
    
    /**
     * 淡出动画
     */
    public void fadeOut(View view, AnimationCallback callback) {
        long duration = getActualDuration(FADE_DURATION);
        
        if (duration == 0) {
            // 无动画模式
            view.setAlpha(0f);
            view.setVisibility(View.GONE);
            if (callback != null) {
                callback.onAnimationEnd();
            }
            return;
        }
        
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(mDefaultInterpolator)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    view.setVisibility(View.GONE);
                    if (callback != null) {
                        callback.onAnimationEnd();
                    }
                }
            })
            .start();
    }
    
    /**
     * 滑动进入动画
     */
    public void slideIn(View view, SlideDirection direction, AnimationCallback callback) {
        long duration = getActualDuration(SLIDE_DURATION);
        
        if (duration == 0) {
            // 无动画模式
            view.setTranslationX(0);
            view.setTranslationY(0);
            view.setVisibility(View.VISIBLE);
            if (callback != null) {
                callback.onAnimationEnd();
            }
            return;
        }
        
        // 设置初始位置
        float startX = 0, startY = 0;
        switch (direction) {
            case LEFT:
                startX = -view.getWidth();
                break;
            case RIGHT:
                startX = view.getWidth();
                break;
            case TOP:
                startY = -view.getHeight();
                break;
            case BOTTOM:
                startY = view.getHeight();
                break;
        }
        
        view.setVisibility(View.VISIBLE);
        view.setTranslationX(startX);
        view.setTranslationY(startY);
        
        view.animate()
            .translationX(0)
            .translationY(0)
            .setDuration(duration)
            .setInterpolator(mDefaultInterpolator)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (callback != null) {
                        callback.onAnimationEnd();
                    }
                }
            })
            .start();
    }
    
    /**
     * 缩放动画
     */
    public void scale(View view, float fromScale, float toScale, AnimationCallback callback) {
        long duration = getActualDuration(TRANSITION_DURATION);
        
        if (duration == 0) {
            // 无动画模式
            view.setScaleX(toScale);
            view.setScaleY(toScale);
            if (callback != null) {
                callback.onAnimationEnd();
            }
            return;
        }
        
        view.setScaleX(fromScale);
        view.setScaleY(fromScale);
        
        view.animate()
            .scaleX(toScale)
            .scaleY(toScale)
            .setDuration(duration)
            .setInterpolator(mDefaultInterpolator)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (callback != null) {
                        callback.onAnimationEnd();
                    }
                }
            })
            .start();
    }
    
    /**
     * 获取文本缩放因子
     */
    public float getTextScaleFactor() {
        return mTextScaleFactor;
    }
    
    /**
     * 应用文本缩放到视图
     */
    public void applyTextScale(android.widget.TextView textView, float baseTextSize) {
        textView.setTextSize(baseTextSize * mTextScaleFactor);
    }
    
    /**
     * 是否启用减少动画
     */
    public boolean isReduceMotionEnabled() {
        return mIsReduceMotionEnabled;
    }
    
    /**
     * 刷新设置（当系统设置变更时调用）
     */
    public void refresh() {
        loadAccessibilitySettings();
        Log.d(TAG, "Accessibility settings refreshed");
    }
    
    /**
     * 滑动方向枚举
     */
    public enum SlideDirection {
        LEFT, RIGHT, TOP, BOTTOM
    }
    
    /**
     * 动画回调接口
     */
    public interface AnimationCallback {
        void onAnimationEnd();
    }
}
