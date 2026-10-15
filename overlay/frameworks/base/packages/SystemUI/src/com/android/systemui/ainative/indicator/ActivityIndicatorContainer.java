// AI Native OS - Activity Indicator Container (D3.2)
package com.android.systemui.ainative.indicator;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 活性指示器容器（D3.2）
 * 
 * 职责：
 * 1. 管理多个芯片视图的布局（水平排列）
 * 2. 自动播放入场/出场动画
 * 3. 点击事件路由
 * 
 * 布局规则：
 * - 芯片从左到右水平排列
 * - 间距 8dp
 * - 最多同时显示 3 种类型（SCREEN_READ/INJECT/RECORDING）
 */
public class ActivityIndicatorContainer extends LinearLayout {
    private static final String TAG = "ActivityIndicatorContainer";
    
    // 布局参数
    private static final int CHIP_SPACING_DP = 8;
    
    // 芯片视图映射（kind -> View）
    private final Map<Integer, ActivityIndicatorChip> mChipViews = new HashMap<>();
    
    // 点击回调
    private ActivityIndicatorChip.OnChipClickListener mClickListener;
    
    public ActivityIndicatorContainer(Context context) {
        this(context, null);
    }
    
    public ActivityIndicatorContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        
        // 设置透明背景
        setBackgroundColor(0x00000000);
    }
    
    /**
     * 设置点击监听
     */
    public void setOnChipClickListener(ActivityIndicatorChip.OnChipClickListener listener) {
        mClickListener = listener;
    }
    
    /**
     * 更新芯片显示
     * 
     * @param kind 指示器类型
     * @param active 是否活跃
     * @param agentCount Agent 数量
     */
    public void updateChip(int kind, boolean active, int agentCount) {
        ActivityIndicatorChip chip = mChipViews.get(kind);
        
        if (active) {
            if (chip == null) {
                // 添加新芯片
                addChip(kind, agentCount);
            } else {
                // 更新现有芯片
                chip.setData(kind, agentCount);
            }
        } else {
            if (chip != null) {
                // 移除芯片
                removeChip(kind);
            }
        }
    }
    
    /**
     * 添加芯片
     */
    private void addChip(int kind, int agentCount) {
        ActivityIndicatorChip chip = new ActivityIndicatorChip(getContext());
        chip.setData(kind, agentCount);
        chip.setOnChipClickListener(mClickListener);
        
        // 设置布局参数
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        if (getChildCount() > 0) {
            params.leftMargin = dpToPx(CHIP_SPACING_DP);
        }
        
        // 添加到容器
        addView(chip, params);
        mChipViews.put(kind, chip);
        
        // 播放入场动画
        chip.playEnterAnimation();
    }
    
    /**
     * 移除芯片
     */
    private void removeChip(int kind) {
        ActivityIndicatorChip chip = mChipViews.remove(kind);
        if (chip == null) {
            return;
        }
        
        // 播放出场动画，完成后移除
        chip.playExitAnimation(() -> {
            removeView(chip);
        });
    }
    
    /**
     * 清空所有芯片
     */
    public void clearAll() {
        for (Integer kind : mChipViews.keySet()) {
            removeChip(kind);
        }
    }
    
    /**
     * dp 转 px
     */
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
