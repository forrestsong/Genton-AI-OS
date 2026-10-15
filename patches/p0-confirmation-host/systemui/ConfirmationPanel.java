// Genton (AI Native OS) - 确认面板视图
//
// 由 SystemUI 独占绘制的全屏安全确认窗口。
// 安全约束（依据 D5 §5.1）：
//   - FLAG_SECURE 防截屏；任何 Agent 不可伪造、不可覆盖
//   - 面板内容全部来自策略引擎签发的 ConfirmationUiRequest
//   - 用户决策不经过大脑，由 ConfirmationHostService 直接回灌策略引擎
package com.android.systemui.genton.confirmation;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.genton.ui.ConfirmationUiRequest;

/** 全屏确认面板 */
public class ConfirmationPanel {
    private static final String TAG = "GentonConfirmPanel";

    /** 用户决策回调 */
    public interface DecisionCallback {
        void onDecision(String confirmId, int userDecision);
    }

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private View mRootView;
    private boolean mAttached;
    private ConfirmationUiRequest mRequest;
    private DecisionCallback mCallback;

    public ConfirmationPanel(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return mAttached;
    }

    /** 展示确认面板 */
    public void show(ConfirmationUiRequest request, DecisionCallback callback) {
        mRequest = request;
        mCallback = callback;
        mHandler.post(() -> showInternal());
    }

    public void dismiss() {
        mHandler.post(this::dismissInternal);
    }

    private void dismissInternal() {
        if (!mAttached || mRootView == null) {
            return;
        }
        try {
            mWindowManager.removeView(mRootView);
        } catch (Throwable t) {
            Log.w(TAG, "removeView failed", t);
        }
        mAttached = false;
        mRootView = null;
    }

    private void showInternal() {
        if (mAttached) {
            return;
        }
        try {
            mRootView = buildView();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    // 最顶层系统窗口（SystemUI 持有 INTERNAL_SYSTEM_WINDOW 权限）
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.FLAG_SECURE
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.OPAQUE);
            lp.dimAmount = 0.75f;
            lp.gravity = Gravity.CENTER;
            mWindowManager.addView(mRootView, lp);
            mAttached = true;
            Log.i(TAG, "confirmation panel shown: " + mRequest.confirmId);
        } catch (Throwable t) {
            Log.e(TAG, "show confirmation panel failed", t);
        }
    }

    // ---------------------------------------------------------------- 视图构建
    private View buildView() {
        LinearLayout outer = new LinearLayout(mContext);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xF21A1A1E);
        outer.setPadding(dp(28), dp(28), dp(28), dp(28));
        outer.setGravity(Gravity.CENTER_HORIZONTAL);

        // 顶部留白
        outer.addView(spacer(0, 0, 1f));

        // 标题
        TextView title = new TextView(mContext);
        title.setText("需要你的确认");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        outer.addView(title);

        // 风险标签
        if (notEmpty(mRequest.riskLabel)) {
            TextView risk = new TextView(mContext);
            risk.setText(mRequest.riskLabel);
            risk.setTextColor(Color.WHITE);
            risk.setTextSize(13f);
            risk.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(riskColor(mRequest.riskLabel));
            bg.setCornerRadius(dp(12));
            risk.setBackground(bg);
            risk.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(14);
            outer.addView(risk, rlp);
        }

        // 操作摘要卡片
        outer.addView(card("要执行的操作", mRequest.targetSummaryMasked), cardLp());

        // 能力
        if (notEmpty(mRequest.capability)) {
            outer.addView(card("所需能力", mRequest.capability), cardLp());
        }

        // 来源意图
        if (notEmpty(mRequest.sourceIntentSummary)) {
            outer.addView(card("由意图触发", mRequest.sourceIntentSummary), cardLp());
        }

        // 策略版本
        if (notEmpty(mRequest.ruleVersion)) {
            TextView ver = new TextView(mContext);
            ver.setText("策略版本 " + mRequest.ruleVersion);
            ver.setTextColor(0xFF8A8A93);
            ver.setTextSize(11f);
            ver.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            vlp.topMargin = dp(12);
            outer.addView(ver, vlp);
        }

        outer.addView(spacer(0, 0, 1f));

        // 按钮组
        outer.addView(button("允许一次", 0xFF2E7D32, 0),
                buttonLp(0));
        outer.addView(button("允许本次任务", 0xFF1565C0, 1),
                buttonLp(dp(10)));
        outer.addView(button("拒绝", 0xFF8E2420, 2),
                buttonLp(dp(10)));

        TextView hint = new TextView(mContext);
        hint.setText("确认请求由系统策略引擎签发；你的选择不会被 Agent 读取或篡改。");
        hint.setTextColor(0xFF8A8A93);
        hint.setTextSize(11f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(16);
        outer.addView(hint, hlp);

        return outer;
    }

    private View card(String label, String value) {
        LinearLayout box = new LinearLayout(mContext);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF26262C);
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);
        box.setPadding(dp(18), dp(14), dp(18), dp(14));

        TextView l = new TextView(mContext);
        l.setText(label);
        l.setTextColor(0xFF9A9AA3);
        l.setTextSize(12f);
        box.addView(l);

        TextView v = new TextView(mContext);
        v.setText(value == null ? "" : value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(17f);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(6);
        box.addView(v, vlp);
        return box;
    }

    private Button button(String text, int color, int decision) {
        Button b = new Button(mContext);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16f);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(26));
        b.setBackground(bg);
        b.setOnClickListener(v -> onUserChose(decision));
        return b;
    }

    private void onUserChose(int decision) {
        final String id = (mRequest != null) ? mRequest.confirmId : null;
        Log.i(TAG, "user decision: confirmId=" + id + " decision=" + decision);
        dismissInternal();
        if (mCallback != null && id != null) {
            mCallback.onDecision(id, decision);
        }
    }

    // ---------------------------------------------------------------- 工具
    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = topMargin;
        return lp;
    }

    private View spacer(int w, int h, float weight) {
        View v = new View(mContext);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                w == 0 ? LinearLayout.LayoutParams.MATCH_PARENT : w,
                h == 0 ? 0 : h, weight));
        return v;
    }

    private int dp(int v) {
        return (int) (v * mContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static int riskColor(String label) {
        if (label == null) {
            return 0xFF455A64;
        }
        if (label.contains("高")) {
            return 0xFFB71C1C;
        }
        if (label.contains("中")) {
            return 0xFFE65100;
        }
        if (label.contains("敏感")) {
            return 0xFF6A1B9A;
        }
        return 0xFF455A64;
    }
}
