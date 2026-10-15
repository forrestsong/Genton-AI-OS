// Genton (AI Native OS) - 感知/操作指示器引导（P1）
//
// 组装 Manager + 悬浮层 + 控制器，并在审计服务就绪后订阅活性流。
package com.android.systemui.genton.indicator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** 指示器引导：幂等启动 */
public final class ActivityIndicatorBootstrap {

    private static final String TAG = "GentonIndicatorBoot";
    private static final long RETRY_DELAY_MS = 2000L;
    private static final int MAX_RETRY = 15;

    private static ActivityIndicatorManager sManager;
    private static ActivityIndicatorOverlay sOverlay;
    private static ActivityIndicatorController sController;

    private ActivityIndicatorBootstrap() {}

    public static synchronized void start(Context context) {
        if (sController != null) {
            return;
        }
        final Context app = context.getApplicationContext();
        sManager = new ActivityIndicatorManager();
        sOverlay = new ActivityIndicatorOverlay(app, sManager);
        sController = new ActivityIndicatorController(app, sManager);
        Log.i(TAG, "indicator bootstrap created");

        // 审计服务可能晚于 SystemUI 就绪 -> 有界重试
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] attempt = {0};
        Runnable tryStart = new Runnable() {
            @Override
            public void run() {
                if (sController.start()) {
                    return;
                }
                if (++attempt[0] < MAX_RETRY) {
                    h.postDelayed(this, RETRY_DELAY_MS);
                } else {
                    Log.w(TAG, "audit service unavailable after retries; indicator idle");
                }
            }
        };
        h.postDelayed(tryStart, RETRY_DELAY_MS);
    }

    public static ActivityIndicatorManager getManager() {
        return sManager;
    }
}
