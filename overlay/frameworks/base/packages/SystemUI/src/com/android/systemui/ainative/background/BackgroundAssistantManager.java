// AI Native OS - Background Assistant Manager
package com.android.systemui.ainative.background;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 后台助理管理器（D1.3）：普通模式助理后台化
 * 
 * 职责：
 * - 悬浮球任务状态显示
 * - 胶囊触达（任务进度提示）
 * - Agent冒泡回助理
 */
public class BackgroundAssistantManager {
    private static final String TAG = "BackgroundAssistantMgr";
    
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler;
    
    // 胶囊视图
    private FrameLayout mCapsuleView;
    private WindowManager.LayoutParams mCapsuleParams;
    private boolean mIsCapsuleShowing = false;
    
    // 任务状态
    private String mCurrentTaskStatus = "";
    private int mActiveTaskCount = 0;
    
    public BackgroundAssistantManager(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 初始化
     */
    public void initialize() {
        createCapsuleView();
        Log.i(TAG, "Background assistant manager initialized");
    }
    
    /**
     * 创建胶囊视图
     */
    private void createCapsuleView() {
        mCapsuleView = new FrameLayout(mContext);
        mCapsuleView.setBackgroundColor(0xE0424242); // 深灰色半透明
        mCapsuleView.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        
        // 添加状态文本
        TextView statusText = new TextView(mContext);
        statusText.setId(android.R.id.text1);
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setTextSize(14);
        statusText.setText("Task running...");
        
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.gravity = Gravity.CENTER;
        mCapsuleView.addView(statusText, textParams);
        
        // 设置窗口参数
        mCapsuleParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dpToPx(40),
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        );
        
        mCapsuleParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        mCapsuleParams.y = dpToPx(50); // 距离顶部50dp
        
        // 设置圆角
        mCapsuleView.setClipToOutline(true);
        
        // 点击监听：返回助理
        mCapsuleView.setOnClickListener(v -> {
            Log.i(TAG, "Capsule clicked, returning to assistant");
            returnToAssistant();
        });
    }
    
    /**
     * 显示胶囊
     */
    public void showCapsule(String taskStatus) {
        mCurrentTaskStatus = taskStatus;
        
        // 更新文本
        TextView statusText = mCapsuleView.findViewById(android.R.id.text1);
        if (statusText != null) {
            statusText.setText(taskStatus);
        }
        
        if (!mIsCapsuleShowing) {
            mWindowManager.addView(mCapsuleView, mCapsuleParams);
            mIsCapsuleShowing = true;
            Log.d(TAG, "Capsule shown: " + taskStatus);
        } else {
            mWindowManager.updateViewLayout(mCapsuleView, mCapsuleParams);
            Log.d(TAG, "Capsule updated: " + taskStatus);
        }
        
        // 自动隐藏（3秒后）
        mHandler.removeCallbacks(mHideCapsuleRunnable);
        mHandler.postDelayed(mHideCapsuleRunnable, 3000);
    }
    
    /**
     * 隐藏胶囊
     */
    public void hideCapsule() {
        if (!mIsCapsuleShowing) {
            return;
        }
        
        mWindowManager.removeView(mCapsuleView);
        mIsCapsuleShowing = false;
        
        Log.d(TAG, "Capsule hidden");
    }
    
    /**
     * 自动隐藏Runnable
     */
    private final Runnable mHideCapsuleRunnable = new Runnable() {
        @Override
        public void run() {
            hideCapsule();
        }
    };
    
    /**
     * 更新任务计数
     */
    public void updateTaskCount(int activeCount) {
        mActiveTaskCount = activeCount;
        
        if (activeCount > 0) {
            String status = activeCount == 1 ? 
                "1 task running" : 
                activeCount + " tasks running";
            showCapsule(status);
        } else {
            hideCapsule();
        }
        
        Log.d(TAG, "Task count updated: " + activeCount);
    }
    
    /**
     * 显示任务进度（胶囊触达）
     */
    public void showTaskProgress(String taskName, int progress) {
        String status = taskName + ": " + progress + "%";
        showCapsule(status);
        Log.d(TAG, "Task progress: " + status);
    }
    
    /**
     * Agent冒泡回助理
     */
    public void bubbleFromAgent(String agentId, String message) {
        Log.i(TAG, "Agent bubble: [" + agentId + "] " + message);
        showCapsule(message);
    }
    
    // ==================== D1.3 核心：接收推送并驱动胶囊/悬浮球 ====================
    
    /**
     * 处理 TaskCard 推送（由 SessionServiceConnector 调用）
     */
    public void onTaskCardChanged(TaskCardData card) {
        if (card == null) {
            return;
        }
        
        mCurrentTaskId = card.taskId;
        
        // 根据任务状态显示胶囊
        String statusText = buildTaskStatusText(card);
        showCapsule(statusText);
        
        // 广播通知悬浮球（活跃状态：PLANNING=1 或 EXECUTING=2）
        boolean isActive = (card.state == 1 || card.state == 2);
        notifyFloatingButtonTaskStatus(isActive, isActive ? 1 : 0);
        
        Log.i(TAG, "TaskCard changed: " + card.taskId + ", state=" + card.state + ", progress=" + card.progress);
    }
    
    /**
     * 构建任务状态文本
     */
    private String buildTaskStatusText(TaskCardData card) {
        String stateText;
        switch (card.state) {
            case 1: // PLANNING
                stateText = "Planning";
                break;
            case 2: // EXECUTING
                stateText = "Executing";
                break;
            case 3: // SUSPENDED
                stateText = "Waiting confirm";
                break;
            case 4: // TERMINATED
                stateText = "Completed";
                break;
            default:
                stateText = "Idle";
                break;
        }
        
        // 显示进度（如果有）
        if (card.progress >= 0 && card.progress <= 100) {
            return card.goalSummaryMasked + ": " + card.progress + "% (" + stateText + ")";
        } else {
            return card.goalSummaryMasked + " - " + stateText;
        }
    }
    
    /**
     * 广播通知悬浮球更新任务状态
     */
    private void notifyFloatingButtonTaskStatus(boolean hasActiveTask, int taskCount) {
        Intent intent = new Intent(ACTION_UPDATE_TASK_STATUS);
        intent.putExtra(EXTRA_HAS_ACTIVE_TASK, hasActiveTask);
        intent.putExtra(EXTRA_TASK_COUNT, taskCount);
        mContext.sendBroadcast(intent);
        
        Log.d(TAG, "Broadcast task status: hasActive=" + hasActiveTask + ", count=" + taskCount);
    }
    
    /**
     * 返回助理（携带任务 ID）
     */
    private void returnToAssistant(String taskId) {
        Intent intent = new Intent("com.ainative.ACTION_SHOW_ASSISTANT");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (taskId != null) {
            intent.putExtra("task_id", taskId);
        }
        mContext.startActivity(intent);
        
        Log.i(TAG, "Returning to assistant with taskId=" + taskId);
    }
    
    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        hideCapsule();
        mHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Background assistant manager destroyed");
    }
}
