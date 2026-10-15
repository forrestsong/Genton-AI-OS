// AI Native OS - Action Card View
package com.android.systemui.genton.session.card;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.genton.agent.ActionCardData;

/**
 * 动作卡片视图：展示动作执行状态和结果
 * 
 * 支持 5 种类型（type 0-4）：
 * 0 = progress（执行中）- 显示进度条
 * 1 = done（已完成）- 显示完成状态
 * 2 = needs_attention（需要用户关注）- 高亮显示
 * 3 = undo_available（可撤销）- 显示撤销按钮
 * 4 = form（内联表单）- 嵌入表单渲染器
 */
public class ActionCardView extends LinearLayout {
    private static final String TAG = "ActionCardView";
    
    // 卡片类型
    public static final int TYPE_PROGRESS = 0;
    public static final int TYPE_DONE = 1;
    public static final int TYPE_NEEDS_ATTENTION = 2;
    public static final int TYPE_UNDO_AVAILABLE = 3;
    public static final int TYPE_FORM = 4;
    
    private TextView mTitleText;
    private TextView mSummaryText;
    private ProgressBar mProgressBar;
    private Button mUndoButton;
    private Button mDetailsButton;
    private LinearLayout mFormContainer;
    
    private ActionCardData mCardData;
    private OnActionListener mActionListener;
    
    public interface OnActionListener {
        void onUndoClick(String cardId);
        void onDetailsClick(String cardId, String auditRef);
    }
    
    public ActionCardView(Context context) {
        super(context);
        init();
    }
    
    public ActionCardView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        setOrientation(VERTICAL);
        setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        setBackgroundColor(Color.WHITE);
        
        // 标题
        mTitleText = new TextView(getContext());
        mTitleText.setTextSize(16);
        mTitleText.setTextColor(Color.parseColor("#212121"));
        addView(mTitleText, new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ));
        
        // 摘要
        mSummaryText = new TextView(getContext());
        mSummaryText.setTextSize(14);
        mSummaryText.setTextColor(Color.parseColor("#757575"));
        LayoutParams summaryParams = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = dpToPx(4);
        addView(mSummaryText, summaryParams);
        
        // 进度条（仅 TYPE_PROGRESS 显示）
        mProgressBar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setIndeterminate(true);
        LayoutParams progressParams = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            dpToPx(4)
        );
        progressParams.topMargin = dpToPx(8);
        mProgressBar.setVisibility(GONE);
        addView(mProgressBar, progressParams);
        
        // 按钮容器
        LinearLayout buttonContainer = new LinearLayout(getContext());
        buttonContainer.setOrientation(HORIZONTAL);
        LayoutParams buttonContainerParams = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        );
        buttonContainerParams.topMargin = dpToPx(12);
        addView(buttonContainer, buttonContainerParams);
        
        // 撤销按钮
        mUndoButton = new Button(getContext());
        mUndoButton.setText("撤销");
        mUndoButton.setTextSize(14);
        mUndoButton.setBackgroundColor(Color.parseColor("#FF5252"));
        mUndoButton.setTextColor(Color.WHITE);
        mUndoButton.setVisibility(GONE);
        mUndoButton.setOnClickListener(v -> {
            if (mActionListener != null && mCardData != null) {
                mActionListener.onUndoClick(mCardData.cardId);
            }
        });
        LayoutParams undoParams = new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            dpToPx(36)
        );
        undoParams.rightMargin = dpToPx(8);
        buttonContainer.addView(mUndoButton, undoParams);
        
        // 查看详情按钮
        mDetailsButton = new Button(getContext());
        mDetailsButton.setText("查看详情");
        mDetailsButton.setTextSize(14);
        mDetailsButton.setBackgroundColor(Color.parseColor("#2196F3"));
        mDetailsButton.setTextColor(Color.WHITE);
        mDetailsButton.setVisibility(GONE);
        mDetailsButton.setOnClickListener(v -> {
            if (mActionListener != null && mCardData != null) {
                mActionListener.onDetailsClick(mCardData.cardId, mCardData.auditRef);
            }
        });
        buttonContainer.addView(mDetailsButton, new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            dpToPx(36)
        ));
        
        // 表单容器（TYPE_FORM 时使用）
        mFormContainer = new LinearLayout(getContext());
        mFormContainer.setOrientation(VERTICAL);
        mFormContainer.setVisibility(GONE);
        LayoutParams formParams = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        );
        formParams.topMargin = dpToPx(12);
        addView(mFormContainer, formParams);
    }
    
    /**
     * 绑定动作卡片数据
     */
    public void bind(ActionCardData data) {
        mCardData = data;
        
        // 设置标题和摘要
        mTitleText.setText(data.title);
        mSummaryText.setText(data.summaryMasked);
        
        // 根据类型配置卡片
        switch (data.type) {
            case TYPE_PROGRESS:
                // 执行中：显示进度条
                setBackgroundColor(Color.parseColor("#E3F2FD"));
                mProgressBar.setVisibility(VISIBLE);
                mUndoButton.setVisibility(GONE);
                mDetailsButton.setVisibility(GONE);
                mFormContainer.setVisibility(GONE);
                break;
                
            case TYPE_DONE:
                // 已完成：绿色背景，显示详情按钮
                setBackgroundColor(Color.parseColor("#E8F5E9"));
                mProgressBar.setVisibility(GONE);
                mUndoButton.setVisibility(GONE);
                mDetailsButton.setVisibility(data.auditRef != null ? VISIBLE : GONE);
                mFormContainer.setVisibility(GONE);
                break;
                
            case TYPE_NEEDS_ATTENTION:
                // 需要关注：橙色背景，高亮显示
                setBackgroundColor(Color.parseColor("#FFF3E0"));
                mTitleText.setTextColor(Color.parseColor("#E65100"));
                mProgressBar.setVisibility(GONE);
                mUndoButton.setVisibility(GONE);
                mDetailsButton.setVisibility(VISIBLE);
                mFormContainer.setVisibility(GONE);
                break;
                
            case TYPE_UNDO_AVAILABLE:
                // 可撤销：显示撤销按钮
                setBackgroundColor(Color.parseColor("#FFF9C4"));
                mProgressBar.setVisibility(GONE);
                mUndoButton.setVisibility(data.canUndo ? VISIBLE : GONE);
                mDetailsButton.setVisibility(data.auditRef != null ? VISIBLE : GONE);
                mFormContainer.setVisibility(GONE);
                break;
                
            case TYPE_FORM:
                // 内联表单：显示表单容器
                setBackgroundColor(Color.parseColor("#F5F5F5"));
                mProgressBar.setVisibility(GONE);
                mUndoButton.setVisibility(GONE);
                mDetailsButton.setVisibility(GONE);
                mFormContainer.setVisibility(VISIBLE);
                
                // 渲染表单（D5.1）
                if (data.form != null) {
                    renderForm(data.form);
                }
                break;
        }
        
        Log.d(TAG, "Action card bound: " + data.cardId + ", type=" + data.type);
    }
    
    /**
     * 渲染内联表单（D5.1集成）
     */
    private void renderForm(com.genton.agent.FormPayload form) {
        mFormContainer.removeAllViews();
        
        // 使用FormRenderer渲染表单
        com.android.systemui.genton.form.FormRenderer renderer = 
            new com.android.systemui.genton.form.FormRenderer(getContext(), mFormContainer);
        
        renderer.setOnFormChangeListener(new com.android.systemui.genton.form.FormRenderer.OnFormChangeListener() {
            @Override
            public void onFormChanged(boolean canSubmit) {
                // 表单状态变化
                Log.d(TAG, "Form can submit: " + canSubmit);
            }
            
            @Override
            public void onFormSubmit(com.genton.agent.FormValueEntry[] values) {
                // 表单提交
                Log.i(TAG, "Form submitted with " + values.length + " fields");
                // TODO: 回调到SessionController处理提交
            }
            
            @Override
            public void onFormCancel() {
                // 表单取消
                Log.i(TAG, "Form cancelled");
                // TODO: 回调到SessionController处理取消
            }
        });
        
        renderer.render(form);
        
        Log.d(TAG, "Form rendered: " + form.formId);
    }
    
    /**
     * 获取表单容器（供FormRenderer使用）
     */
    public LinearLayout getFormContainer() {
        return mFormContainer;
    }
    
    /**
     * 设置动作监听器
     */
    public void setOnActionListener(OnActionListener listener) {
        mActionListener = listener;
    }
    
    /**
     * 获取卡片ID
     */
    public String getCardId() {
        return mCardData != null ? mCardData.cardId : null;
    }
    
    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
