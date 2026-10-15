// AI Native OS - Task Card View
package com.android.systemui.ainative.session.card;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ainative.agent.TaskCardData;

/**
 * 任务卡片视图：展示任务状态（对齐ACB状态机）
 * 
 * 显示内容：
 * - 任务目标摘要
 * - 状态（空闲/规划/执行/挂起/终止）
 * - 进度条
 * - 预算警告
 */
public class TaskCardView extends LinearLayout {
    private static final String TAG = "TaskCardView";
    
    // 状态文本
    private static final String[] STATE_LABELS = {
        "空闲", "规划中", "执行中", "等待确认", "已完成"
    };
    
    // 状态颜色
    private static final int[] STATE_COLORS = {
        0xFF9E9E9E,  // IDLE - 灰色
        0xFF2196F3,  // PLANNING - 蓝色
        0xFF4CAF50,  // EXECUTING - 绿色
        0xFFFFC107,  // SUSPENDED - 黄色
        0xFF757575   // TERMINATED - 深灰色
    };
    
    private TextView mGoalText;
    private TextView mStateText;
    private ProgressBar mProgressBar;
    private TextView mProgressText;
    private TextView mBudgetWarning;
    
    private TaskCardData mCardData;
    
    public TaskCardView(Context context) {
        super(context);
        init();
    }
    
    public TaskCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        setOrientation(VERTICAL);
        setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        setBackgroundColor(Color.WHITE);
        
        // 目标摘要
        mGoalText = new TextView(getContext());
        mGoalText.setTextSize(16);
        mGoalText.setTextColor(Color.parseColor("#212121"));
        addView(mGoalText, new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ));
        
        // 状态文本
        mStateText = new TextView(getContext());
        mStateText.setTextSize(14);
        LayoutParams stateParams = new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        );
        stateParams.topMargin = dpToPx(8);
        addView(mStateText, stateParams);
        
        // 进度条
        mProgressBar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(100);
        LayoutParams progressBarParams = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            dpToPx(4)
        );
        progressBarParams.topMargin = dpToPx(8);
        addView(mProgressBar, progressBarParams);
        
        // 进度文本
        mProgressText = new TextView(getContext());
        mProgressText.setTextSize(12);
        mProgressText.setTextColor(Color.parseColor("#757575"));
        LayoutParams progressTextParams = new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        );
        progressTextParams.topMargin = dpToPx(4);
        addView(mProgressText, progressTextParams);
        
        // 预算警告
        mBudgetWarning = new TextView(getContext());
        mBudgetWarning.setText("⚠️ 预算即将耗尽");
        mBudgetWarning.setTextSize(12);
        mBudgetWarning.setTextColor(Color.parseColor("#FF9800"));
        mBudgetWarning.setVisibility(GONE);
        LayoutParams warningParams = new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        );
        warningParams.topMargin = dpToPx(8);
        addView(mBudgetWarning, warningParams);
    }
    
    /**
     * 绑定任务卡片数据
     */
    public void bind(TaskCardData data) {
        mCardData = data;
        
        // 设置目标摘要
        mGoalText.setText(data.goalSummaryMasked);
        
        // 设置状态
        int state = Math.max(0, Math.min(4, data.state));
        mStateText.setText(STATE_LABELS[state]);
        mStateText.setTextColor(STATE_COLORS[state]);
        
        // 设置进度
        if (data.progress >= 0) {
            mProgressBar.setVisibility(VISIBLE);
            mProgressText.setVisibility(VISIBLE);
            mProgressBar.setProgress(data.progress);
            mProgressText.setText(data.progress + "%");
        } else {
            mProgressBar.setVisibility(GONE);
            mProgressText.setVisibility(GONE);
        }
        
        // 显示预算警告
        mBudgetWarning.setVisibility(data.budgetWarning ? VISIBLE : GONE);
        
        Log.d(TAG, "Task card bound: " + data.taskId + ", state=" + state);
    }
    
    /**
     * 获取任务ID
     */
    public String getTaskId() {
        return mCardData != null ? mCardData.taskId : null;
    }
    
    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
