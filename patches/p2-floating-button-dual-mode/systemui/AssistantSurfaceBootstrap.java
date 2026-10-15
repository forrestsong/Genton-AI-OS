// Genton (AI Native OS) - 助理表面与双模式引导（P2）
//
// 职责（依据 D5 §2/§3）：
//   1. 宿主 Assistant Surface：以 SystemUI 系统窗口承载认知壳（并非常规 Activity）
//   2. 双模式管理：LAUNCHER（普通应用模式） <-> ASSISTANT（AI 全能助理模式）
//      - 切到 LAUNCHER：表面隐藏，助理以悬浮球形式最小化存在（任务不中断）
//      - 切回 ASSISTANT：表面恢复，状态源为系统服务而非 UI 本地缓存
//   3. 三入口统管：悬浮球 / 侧边手势 / QS Tile（EntryManager）
//   4. 响应 com.ainative.ACTION_SHOW_ASSISTANT 广播（悬浮球与胶囊点击）
//
// 说明：助理表面采用 WindowManager 系统窗口而非 Activity，
//       以避免 Home 栈重建导致的会话丢失与切换延迟（D5 §2 关键点）。
package com.android.systemui.ainative.assistantui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.ainative.AssistantSurfaceController;
import com.android.systemui.ainative.entry.EntryManager;

/** 助理表面与双模式引导 */
public final class AssistantSurfaceBootstrap {

    private static final String TAG = "GentonSurface";

    /** 悬浮球/胶囊点击后请求返回助理模式 */
    public static final String ACTION_SHOW_ASSISTANT = "com.ainative.ACTION_SHOW_ASSISTANT";
    /** 请求切到普通应用模式 */
    public static final String ACTION_SHOW_LAUNCHER = "com.ainative.ACTION_SHOW_LAUNCHER";

    private static volatile AssistantSurfaceBootstrap sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WindowManager mWindowManager;

    private AssistantSurfaceController mController;
    private EntryManager mEntryManager;
    private View mSurfaceView;
    private boolean mAttached;
    private boolean mReceiverRegistered;

    private final BroadcastReceiver mModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            switch (intent.getAction()) {
                case ACTION_SHOW_ASSISTANT:
                    showAssistant("broadcast");
                    break;
                case ACTION_SHOW_LAUNCHER:
                    showLauncher("broadcast");
                    break;
                default:
                    break;
            }
        }
    };

    private AssistantSurfaceBootstrap(Context context) {
        mContext = context.getApplicationContext();
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized void start(Context context) {
        if (sInstance != null) {
            return;
        }
        sInstance = new AssistantSurfaceBootstrap(context);
        sInstance.init();
    }

    public static AssistantSurfaceBootstrap get() {
        return sInstance;
    }

    private void init() {
        try {
            mController = new AssistantSurfaceController(mContext);
            mController.initialize();
            mSurfaceView = mController.getRootView();
            Log.i(TAG, "surface controller initialized, mode=" + mController.getCurrentMode());
        } catch (Throwable t) {
            Log.e(TAG, "surface controller init failed", t);
        }

        // 三入口统管（悬浮球 + 侧边手势；QS Tile 由系统 TileService 承载）
        try {
            mEntryManager = new EntryManager(mContext);
            mEntryManager.initialize();
            Log.i(TAG, "entry manager initialized (floating button + edge gesture)");
        } catch (Throwable t) {
            Log.e(TAG, "entry manager init failed", t);
        }

        registerModeReceiver();
    }

    private void registerModeReceiver() {
        if (mReceiverRegistered) {
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_SHOW_ASSISTANT);
        f.addAction(ACTION_SHOW_LAUNCHER);
        // [P2 修复] Android 14+ (targetSdk>=34) 必须显式声明导出标志。
        // 该广播受 SystemUI 自身签名级权限保护（仅 SystemUI 可发送），
        // 故使用 RECEIVER_EXPORTED；同时保留权限校验。
        mContext.registerReceiver(mModeReceiver, f,
                com.android.systemui.ainative.entry.FloatingButtonService
                        .PERMISSION_SELF, null,
                Context.RECEIVER_EXPORTED);
        mReceiverRegistered = true;
    }

    // ---------------------------------------------------------------- 模式控制
    /** 进入助理模式（拉起认知壳表面） */
    public void showAssistant(String reason) {
        Log.i(TAG, "showAssistant: " + reason);
        mHandler.post(() -> {
            attachSurface();
            if (mController != null) {
                mController.switchMode(AssistantSurfaceController.Mode.ASSISTANT, true);
            }
        });
    }

    /** 回到普通应用模式（表面隐藏，助理降级为悬浮球） */
    public void showLauncher(String reason) {
        Log.i(TAG, "showLauncher: " + reason);
        mHandler.post(() -> {
            if (mController != null) {
                mController.switchMode(AssistantSurfaceController.Mode.LAUNCHER, true);
            }
            detachSurface();
        });
    }

    public boolean isAssistantMode() {
        return mController != null
                && mController.getCurrentMode() == AssistantSurfaceController.Mode.ASSISTANT;
    }

    // ---------------------------------------------------------------- 表面窗口
    private void attachSurface() {
        if (mAttached || mSurfaceView == null) {
            return;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                // SystemUI 持有的助理表面：位于状态栏之下、应用之上
                WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            mWindowManager.addView(mSurfaceView, lp);
            mAttached = true;
            Log.i(TAG, "assistant surface attached");
        } catch (Throwable t) {
            Log.e(TAG, "attach surface failed", t);
        }
    }

    private void detachSurface() {
        if (!mAttached || mSurfaceView == null) {
            return;
        }
        try {
            mWindowManager.removeView(mSurfaceView);
        } catch (Throwable t) {
            Log.w(TAG, "detach surface failed", t);
        }
        mAttached = false;
        Log.i(TAG, "assistant surface detached (minimized to floating button)");
    }
}
