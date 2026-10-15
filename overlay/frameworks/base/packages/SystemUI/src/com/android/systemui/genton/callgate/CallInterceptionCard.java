package com.android.systemui.genton.callgate;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.genton.call.CallDescriptor;
import com.genton.call.CallVerdict;

/**
 * CallGate 接管卡片
 * 
 * 显示时机：收到 onVerdict(route=AGENT_HANDLE) 后立即显示
 * 
 * 视觉设计：
 * ┌─────────────────────────────────────────┐
 * │  🤖 AI 助理正在接管来电                  │
 * │                                         │
 * │  来电：+86 138****5678                   │
 * │  时间：14:32                            │
 * │                                         │
 * │  状态：正在连接... / 正在问候 / 正在听取 │
 * │                                         │
 * │  [中止接管，手动接听]  [查看详情]        │
 * └─────────────────────────────────────────┘
 * 
 * 关键要素：
 * - 来电号码：脱敏显示（中间 4 位隐藏）
 * - 接管模式：L1（留言）/ L2（对话）
 * - 会话状态：bridging / greeting / transcribing / done
 * - 用户控制：中止接管按钮（转原生通话）
 * 
 * 安全策略：
 * - 中止按钮：500ms 防误触延迟
 * - 自动消失：会话结束后 3s 或用户主动关闭
 * - WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY（需要权限）
 */
public class CallInterceptionCard {
    private static final String TAG = "CallInterceptionCard";
    
    private static final int ABORT_BUTTON_DELAY_MS = 500;
    private static final int AUTO_DISMISS_DELAY_MS = 3000;
    
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mMainHandler;
    
    private View mRootView;
    private TextView mTitleText;
    private TextView mNumberText;
    private TextView mTimeText;
    private TextView mModeText;
    private TextView mStateText;
    private Button mAbortButton;
    private Button mDetailsButton;
    
    private CallDescriptor mDescriptor;
    private CallVerdict mVerdict;
    private Callback mCallback;
    
    private boolean mIsShown = false;
    private boolean mAbortButtonEnabled = false;

    /**
     * 回调接口
     */
    public interface Callback {
        /**
         * 用户点击"中止接管"
         */
        void onAbortInterception();
        
        /**
         * 用户点击"查看详情"
         */
        void onViewDetails();
    }

    public CallInterceptionCard(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 显示接管卡
     * 
     * @param descriptor 来电描述符
     * @param verdict 判定结果
     * @param callback 回调
     */
    public void show(CallDescriptor descriptor, CallVerdict verdict, Callback callback) {
        if (mIsShown) {
            Log.w(TAG, "Card already shown");
            return;
        }
        
        mDescriptor = descriptor;
        mVerdict = verdict;
        mCallback = callback;
        
        createView();
        updateContent();
        attachToWindow();
        
        // 延迟启用"中止接管"按钮（防误触）
        mMainHandler.postDelayed(() -> {
            mAbortButtonEnabled = true;
            if (mAbortButton != null) {
                mAbortButton.setEnabled(true);
                mAbortButton.setAlpha(1.0f);
            }
        }, ABORT_BUTTON_DELAY_MS);
        
        mIsShown = true;
        Log.d(TAG, "Interception card shown");
    }

    /**
     * 更新会话状态
     * 
     * @param stateText 状态文本（"正在连接..."/"正在问候"/"正在听取"）
     */
    public void updateSessionState(String stateText) {
        if (!mIsShown || mStateText == null) {
            return;
        }
        
        mMainHandler.post(() -> {
            mStateText.setText(stateText);
        });
    }

    /**
     * 隐藏接管卡
     */
    public void dismiss() {
        if (!mIsShown) {
            return;
        }
        
        mMainHandler.post(() -> {
            detachFromWindow();
            mIsShown = false;
            Log.d(TAG, "Interception card dismissed");
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
        mTitleText.setText("🤖 AI 助理正在接管来电");
        mTitleText.setTextSize(18);
        mTitleText.setTextColor(0xFF212121);
        mTitleText.setPadding(0, 0, 0, 24);
        rootLayout.addView(mTitleText);
        
        // 来电号码
        mNumberText = new TextView(mContext);
        mNumberText.setTextSize(16);
        mNumberText.setTextColor(0xFF424242);
        mNumberText.setPadding(0, 8, 0, 8);
        rootLayout.addView(mNumberText);
        
        // 来电时间
        mTimeText = new TextView(mContext);
        mTimeText.setTextSize(14);
        mTimeText.setTextColor(0xFF757575);
        mTimeText.setPadding(0, 4, 0, 16);
        rootLayout.addView(mTimeText);
        
        // 接管模式
        mModeText = new TextView(mContext);
        mModeText.setTextSize(14);
        mModeText.setTextColor(0xFF1976D2);
        mModeText.setPadding(0, 8, 0, 8);
        rootLayout.addView(mModeText);
        
        // 会话状态
        mStateText = new TextView(mContext);
        mStateText.setText("正在连接...");
        mStateText.setTextSize(14);
        mStateText.setTextColor(0xFF757575);
        mStateText.setPadding(0, 4, 0, 24);
        rootLayout.addView(mStateText);
        
        // 按钮容器
        LinearLayout buttonLayout = new LinearLayout(mContext);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        
        // 中止接管按钮
        mAbortButton = new Button(mContext);
        mAbortButton.setText("中止接管，手动接听");
        mAbortButton.setTextSize(14);
        mAbortButton.setPadding(32, 16, 32, 16);
        mAbortButton.setEnabled(false);
        mAbortButton.setAlpha(0.5f); // 初始半透明（未启用）
        mAbortButton.setOnClickListener(v -> {
            if (mAbortButtonEnabled && mCallback != null) {
                mCallback.onAbortInterception();
            }
        });
        
        LinearLayout.LayoutParams abortParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        abortParams.rightMargin = 16;
        buttonLayout.addView(mAbortButton, abortParams);
        
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
        
        buttonLayout.addView(mDetailsButton);
        
        rootLayout.addView(buttonLayout);
    }

    private void updateContent() {
        // 更新来电号码（脱敏）
        String maskedNumber = maskPhoneNumber(mDescriptor.numberE164);
        String numberDisplay = "来电：" + maskedNumber;
        if (mDescriptor.callerName != null && !mDescriptor.callerName.isEmpty()) {
            numberDisplay = "来电：" + mDescriptor.callerName + " (" + maskedNumber + ")";
        }
        mNumberText.setText(numberDisplay);
        
        // 更新来电时间
        String timeDisplay = "时间：" + formatTimestamp(mDescriptor.timestampMs);
        mTimeText.setText(timeDisplay);
        
        // 更新接管模式
        String modeDisplay = "模式：" + formatHandleMode(mVerdict.handleMode);
        if (mVerdict.reason != null && !mVerdict.reason.isEmpty()) {
            modeDisplay += " (" + mVerdict.reason + ")";
        }
        mModeText.setText(modeDisplay);
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
        
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 100; // 距离顶部 100dp
        
        mWindowManager.addView(mRootView, params);
    }

    private void detachFromWindow() {
        if (mRootView != null && mRootView.getParent() != null) {
            mWindowManager.removeView(mRootView);
        }
        mRootView = null;
    }

    /**
     * 号码脱敏显示
     */
    private String maskPhoneNumber(String numberE164) {
        if (numberE164 == null || numberE164.length() < 8) {
            return numberE164 != null ? numberE164 : "未知号码";
        }
        int len = numberE164.length();
        String prefix = numberE164.substring(0, len - 8);
        String middle = "****";
        String suffix = numberE164.substring(len - 4);
        return prefix + middle + suffix;
    }

    /**
     * 格式化时间戳
     */
    private String formatTimestamp(long timestampMs) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm");
        return sdf.format(new java.util.Date(timestampMs));
    }

    /**
     * 格式化接管模式
     */
    private String formatHandleMode(int handleMode) {
        switch (handleMode) {
            case 1: // MODE_L1_SILENT_AI
                return "静默留言";
            case 2: // MODE_L2_AUDIO_DIALOG
                return "AI 对话";
            case 3: // MODE_L3_RECORD_ONLY
                return "仅记录";
            default:
                return "未知模式";
        }
    }
}
