// Genton (AI Native OS) - 感知/操作指示器悬浮层（D3.2）
//
// 为什么用 WindowManager 悬浮层而不是直接插入状态栏视图：
//   向 SystemUI 状态栏插入自定义视图需要改动 CollapsedStatusBarFragment 与
//   对应资源/槽位，侵入面大且易与 AOSP 升级冲突。本实现以最顶层悬浮层
//   承载指示器芯片，视觉上位于状态栏下方，安全语义（"谁在看/在点"常驻可见）
//   完全一致；后续可在不改本类的前提下换挂到状态栏槽位。
//
// 数据源：ActivityIndicatorManager（由 AuditService 活性流驱动）
package com.android.systemui.ainative.indicator;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.util.List;

/** 活性指示器悬浮层（承载若干芯片） */
public class ActivityIndicatorOverlay {

    private static final String TAG = "GentonIndicator";

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ActivityIndicatorManager mManager;

    private LinearLayout mRoot;
    private boolean mAttached;

    /** kind -> 芯片 */
    private final ActivityIndicatorChip[] mChips = new ActivityIndicatorChip[3];

    public ActivityIndicatorOverlay(Context context, ActivityIndicatorManager manager) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mManager = manager;
        mManager.setDisplayCallback(this::onIndicatorChanged);
    }

    /** 显示/隐藏由活跃状态驱动 */
    private void onIndicatorChanged(int kind, boolean active, int agentCount, List<String> agentIds) {
        Log.i(TAG, "indicator kind=" + kind + " active=" + active
                + " agents=" + agentCount);
        mHandler.post(() -> applyChange(kind, active, agentCount, agentIds));
    }

    private void applyChange(int kind, boolean active, int agentCount, List<String> agentIds) {
        if (kind < 0 || kind >= mChips.length) {
            return;
        }
        if (active) {
            ensureAttached();
            ActivityIndicatorChip chip = mChips[kind];
            if (chip == null) {
                chip = new ActivityIndicatorChip(mContext);
                mChips[kind] = chip;
                mRoot.addView(chip, chipLp());
            }
            chip.setVisibility(View.VISIBLE);
            updateChipText(chip, kind, agentCount, agentIds);
            try {
                chip.playEnterAnimation();
            } catch (Throwable ignored) {
            }
        } else {
            ActivityIndicatorChip chip = mChips[kind];
            if (chip != null) {
                chip.setVisibility(View.GONE);
            }
        }
        boolean any = false;
        for (ActivityIndicatorChip c : mChips) {
            if (c != null && c.getVisibility() == View.VISIBLE) {
                any = true;
                break;
            }
        }
        if (!any) {
            detach();
        }
    }

    private void updateChipText(ActivityIndicatorChip chip, int kind, int agentCount, List<String> ids) {
        String label;
        switch (kind) {
            case ActivityIndicatorManager.KIND_SCREEN_READ:
                label = "正在读屏";
                break;
            case ActivityIndicatorManager.KIND_INJECT:
                label = "正在操作";
                break;
            case ActivityIndicatorManager.KIND_RECORDING:
                label = "正在录音";
                break;
            default:
                label = "Agent 活动中";
        }
        if (agentCount > 1) {
            label = label + " ×" + agentCount;
        }
        // [P1 修复] 必须调用 setData 才会渲染胶囊内的图标与文本；
        // 仅设置 contentDescription 会得到一个空的圆角胶囊（视觉上像圆点）。
        chip.setData(kind, agentCount);
        chip.setContentDescription(label + "（点击查看详情）");
    }

    private void ensureAttached() {
        if (mAttached) {
            return;
        }
        mRoot = new LinearLayout(mContext);
        mRoot.setOrientation(LinearLayout.HORIZONTAL);
        mRoot.setGravity(Gravity.END);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // 状态栏之下的最顶层非交互式区域（点击仍可达）
                WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.y = dp(38);
        lp.x = dp(6);

        try {
            mWindowManager.addView(mRoot, lp);
            mAttached = true;
            Log.i(TAG, "indicator overlay attached");
        } catch (Throwable t) {
            Log.e(TAG, "attach indicator overlay failed", t);
            mRoot = null;
        }
    }

    private void detach() {
        if (!mAttached || mRoot == null) {
            return;
        }
        try {
            mWindowManager.removeView(mRoot);
        } catch (Throwable t) {
            Log.w(TAG, "detach failed", t);
        }
        mAttached = false;
        mRoot = null;
        for (int i = 0; i < mChips.length; i++) {
            mChips[i] = null;
        }
        Log.i(TAG, "indicator overlay detached");
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * mContext.getResources().getDisplayMetrics().density + 0.5f);
    }
}
