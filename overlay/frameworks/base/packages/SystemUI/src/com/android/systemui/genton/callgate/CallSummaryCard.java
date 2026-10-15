package com.android.systemui.genton.callgate;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.genton.call.CallSessionHandle;
import com.genton.call.CallSummary;

/**
 * 通话摘要+建议回拨卡
 * 
 * 显示时机：收到 onSummaryReady() 后显示
 * 
 * 视觉设计：
 * ┌─────────────────────────────────────────┐
 * │  通话摘要                                │
 * │  ────────────────────────────────────   │
 * │                                         │
 * │  来电方询问了产品价格和发货时间，       │
 * │  已告知具体信息并记录需求。             │
 * │                                         │
 * │  通话时长：1 分 23 秒                   │
 * │  转写字数：156 字                       │
 * │                                         │
 * │  💡 建议回拨：+86 138****5678           │
 * │                                         │
 * │  [立即回拨]  [查看详情]  [关闭]         │
 * └─────────────────────────────────────────┘
 * 
 * 关键要素：
 * - 摘要文本：<200 字，简洁明了
 * - 通话时长：分钟+秒数
 * - 转写字数：用于统计
 * - 建议回拨：根据 callbackSuggested 决定是否显示
 * - 操作按钮：回拨/查看详情/关闭
 * 
 * 安全策略：
 * - 回拨需要二次确认（防误触）
 * - 摘要不落长期记忆（除非用户要求）
 * - 自动消失：10s 后或用户主动关闭
 */
public class CallSummaryCard {
    private static final String TAG = "CallSummaryCard";
    
    private static final int AUTO_DISMISS_DELAY_MS = 10000;
    
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mMainHandler;
    
    private View mRootView;
    private TextView mTitleText;
    private TextView mSummaryText;
    private TextView mDurationText;
    private TextView mWordCountText;
    private TextView mCallbackSuggestionText;
    private LinearLayout mButtonLayout;
    private Button mCallbackButton;
    private Button mDetailsButton;
    private Button mDismissButton;
    
    private CallSessionHandle mSession;
    private CallSummary mSummary;
    private Callback mCallback;
    
    private boolean mIsShown = false;
    private Runnable mAutoDismissRunnable;

    /**
     * 回调接口
     */
    public interface Callback {
        /**
         * 用户点击"立即回拨"
         */
        void onCallback();
        
        /**
         * 用户点击"查看详情"
         */
        void onViewDetails();
        
        /**
         * 用户点击"关闭"或自动消失
         */
        void onDismiss();
    }

    public CallSummaryCard(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 显示摘要卡
     * 
     * @param session 会话句柄
     * @param summary 通话摘要
     * @param callback 回调
     */
    public void show(CallSessionHandle session, CallSummary summary, Callback callback) {
        if (mIsShown) {
            Log.w(TAG, "Summary card already shown");
            return;
        }
        
        mSession = session;
        mSummary = summary;
        mCallback = callback;
        
        createView();
        updateContent();
        attachToWindow();
        
        // 自动消失定时器
        mAutoDismissRunnable = () -> {
            if (mIsShown && mCallback != null) {
                mCallback.onDismiss();
            }
        };
        mMainHandler.postDelayed(mAutoDismissRunnable, AUTO_DISMISS_DELAY_MS);
        
        mIsShown = true;
        Log.d(TAG, "Summary card shown");
    }

    /**
     * 隐藏摘要卡
     */
    public void dismiss() {
        if (!mIsShown) {
            return;
        }
        
        // 取消自动消失定时器
        if (mAutoDismissRunnable != null) {
            mMainHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }
        
        mMainHandler.post(() -> {
            detachFromWindow();
            mIsShown = false;
            Log.d(TAG, "Summary card dismissed");
        });
    }

    private void createView() {
        // 创建根布局
        mRootView = new LinearLayout(mContext);
        LinearLayout rootLayout = (LinearLayout) mRootView;
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xF0FFFFFF); // 半透明白色背景
        rootLayout.setPadding(48, 32, 48, 32);
        
        // 标题
        mTitleText = new TextView(mContext);
        mTitleText.setText("通话摘要");
        mTitleText.setTextSize(18);
        mTitleText.setTextColor(0xFF212121);
        mTitleText.setPadding(0, 0, 0, 16);
        rootLayout.addView(mTitleText);
        
        // 分隔线
        View divider = new View(mContext);
        divider.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            2
        );
        dividerParams.bottomMargin = 16;
        rootLayout.addView(divider, dividerParams);
        
        // 摘要文本
        mSummaryText = new TextView(mContext);
        mSummaryText.setTextSize(15);
        mSummaryText.setTextColor(0xFF424242);
        mSummaryText.setPadding(0, 8, 0, 16);
        mSummaryText.setLineSpacing(4, 1.2f);
        rootLayout.addView(mSummaryText);
        
        // 通话时长
        mDurationText = new TextView(mContext);
        mDurationText.setTextSize(13);
        mDurationText.setTextColor(0xFF757575);
        mDurationText.setPadding(0, 4, 0, 4);
        rootLayout.addView(mDurationText);
        
        // 转写字数
        mWordCountText = new TextView(mContext);
        mWordCountText.setTextSize(13);
        mWordCountText.setTextColor(0xFF757575);
        mWordCountText.setPadding(0, 4, 0, 16);
        rootLayout.addView(mWordCountText);
        
        // 建议回拨提示
        mCallbackSuggestionText = new TextView(mContext);
        mCallbackSuggestionText.setTextSize(14);
        mCallbackSuggestionText.setTextColor(0xFF1976D2);
        mCallbackSuggestionText.setPadding(0, 8, 0, 16);
        rootLayout.addView(mCallbackSuggestionText);
        
        // 按钮容器
        mButtonLayout = new LinearLayout(mContext);
        mButtonLayout.setOrientation(LinearLayout.HORIZONTAL);
        mButtonLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        
        // 立即回拨按钮
        mCallbackButton = new Button(mContext);
        mCallbackButton.setText("立即回拨");
        mCallbackButton.setTextSize(14);
        mCallbackButton.setPadding(32, 16, 32, 16);
        mCallbackButton.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onCallback();
            }
        });
        
        LinearLayout.LayoutParams callbackParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        callbackParams.rightMargin = 16;
        mButtonLayout.addView(mCallbackButton, callbackParams);
        
        // 查看详情按钮
        mDetailsButton = new Button(mContext);
        mDetailsButton.setText("查看详情");
        mDetailsButton.setTextSize(14);
        mDetailsButton.setPadding(32, 16, 32, 16);
        mDetailsButton.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onViewDetails();
            }
        });
        
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        detailsParams.rightMargin = 16;
        mButtonLayout.addView(mDetailsButton, detailsParams);
        
        // 关闭按钮
        mDismissButton = new Button(mContext);
        mDismissButton.setText("关闭");
        mDismissButton.setTextSize(14);
        mDismissButton.setPadding(32, 16, 32, 16);
        mDismissButton.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onDismiss();
            }
        });
        
        mButtonLayout.addView(mDismissButton);
        
        rootLayout.addView(mButtonLayout);
    }

    private void updateContent() {
        // 更新摘要文本
        String summaryDisplay = mSummary.summaryText;
        if (summaryDisplay == null || summaryDisplay.isEmpty()) {
            summaryDisplay = "通话已结束，暂无摘要。";
        }
        mSummaryText.setText(summaryDisplay);
        
        // 更新通话时长
        String durationDisplay = "通话时长：" + formatDuration(mSummary.durationSec);
        mDurationText.setText(durationDisplay);
        
        // 更新转写字数
        String wordCountDisplay = "转写字数：" + mSummary.transcriptWordCount + " 字";
        mWordCountText.setText(wordCountDisplay);
        
        // 更新建议回拨提示
        if (mSummary.callbackSuggested && mSummary.callbackNumberMasked != null) {
            String callbackDisplay = "💡 建议回拨：" + mSummary.callbackNumberMasked;
            mCallbackSuggestionText.setText(callbackDisplay);
            mCallbackSuggestionText.setVisibility(View.VISIBLE);
            mCallbackButton.setVisibility(View.VISIBLE);
        } else {
            mCallbackSuggestionText.setVisibility(View.GONE);
            mCallbackButton.setVisibility(View.GONE);
        }
    }

    private void attachToWindow() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.CENTER;
        params.width = 700; // 固定宽度 700dp
        
        mWindowManager.addView(mRootView, params);
    }

    private void detachFromWindow() {
        if (mRootView != null && mRootView.getParent() != null) {
            mWindowManager.removeView(mRootView);
        }
        mRootView = null;
    }

    /**
     * 格式化通话时长
     */
    private String formatDuration(int durationSec) {
        if (durationSec < 60) {
            return durationSec + " 秒";
        }
        
        int minutes = durationSec / 60;
        int seconds = durationSec % 60;
        
        if (minutes < 60) {
            return minutes + " 分 " + seconds + " 秒";
        }
        
        int hours = minutes / 60;
        minutes = minutes % 60;
        return hours + " 小时 " + minutes + " 分 " + seconds + " 秒";
    }
}
