// AI Native OS - Floating Button Service
package com.android.systemui.ainative.entry;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.systemui.ainative.background.BackgroundAssistantManager;

/**
 * 悬浮球服务：提供快速唤起助理的悬浮入口
 * 
 * 职责：
 * - 显示悬浮球
 * - 处理点击/长按事件
 * - 自动贴边/收起
 * - 任务状态指示（D1.3）
 */
public class FloatingButtonService extends Service {

    /** 与 SystemUI 自身签名权限一致（广播保护） */
    public static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";

    private static final String TAG = "FloatingButtonService";
    
    // 悬浮球尺寸
    private static final int BUTTON_SIZE_DP = 56;
    private static final int COLLAPSED_SIZE_DP = 20; // 收起后的宽度
    
    // 长按阈值
    private static final long LONG_PRESS_TIMEOUT = 500;
    
    private WindowManager mWindowManager;
    private ImageView mFloatingButton;
    private WindowManager.LayoutParams mLayoutParams;
    
    private boolean mIsExpanded = true;
    private boolean mIsShowing = false;
    
    // 触摸相关
    private float mInitialTouchX;
    private float mInitialTouchY;
    private int mInitialX;
    private int mInitialY;
    private long mTouchDownTime;
    private boolean mIsDragging = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        
        createFloatingButton();
        
        Log.i(TAG, "Floating button service created");
    }
    
    /**
     * 创建悬浮球
     */
    private void createFloatingButton() {
        // 创建悬浮球视图
        mFloatingButton = new ImageView(this);
        mFloatingButton.setImageResource(android.R.drawable.ic_dialog_info); // 临时图标
        mFloatingButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mFloatingButton.setBackgroundColor(0xCC2196F3); // 蓝色半透明
        
        // 设置窗口参数
        mLayoutParams = new WindowManager.LayoutParams(
            dpToPx(BUTTON_SIZE_DP),
            dpToPx(BUTTON_SIZE_DP),
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        
        mLayoutParams.gravity = Gravity.TOP | Gravity.START;
        
        // 初始位置（右侧中间）
        Display display = mWindowManager.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        mLayoutParams.x = size.x - dpToPx(BUTTON_SIZE_DP);
        mLayoutParams.y = size.y / 2;
        
        // 设置触摸监听
        mFloatingButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });
    }
    
    /**
     * 处理触摸事件
     */
    private boolean handleTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mInitialTouchX = event.getRawX();
                mInitialTouchY = event.getRawY();
                mInitialX = mLayoutParams.x;
                mInitialY = mLayoutParams.y;
                mTouchDownTime = System.currentTimeMillis();
                mIsDragging = false;
                return true;
                
            case MotionEvent.ACTION_MOVE:
                float deltaX = event.getRawX() - mInitialTouchX;
                float deltaY = event.getRawY() - mInitialTouchY;
                
                // 判断是否开始拖动
                if (!mIsDragging && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
                    mIsDragging = true;
                }
                
                if (mIsDragging) {
                    mLayoutParams.x = mInitialX + (int) deltaX;
                    mLayoutParams.y = mInitialY + (int) deltaY;
                    mWindowManager.updateViewLayout(mFloatingButton, mLayoutParams);
                }
                return true;
                
            case MotionEvent.ACTION_UP:
                long pressDuration = System.currentTimeMillis() - mTouchDownTime;
                
                if (mIsDragging) {
                    // 拖动结束，贴边
                    snapToEdge();
                } else if (pressDuration >= LONG_PRESS_TIMEOUT) {
                    // 长按事件
                    onLongPress();
                } else {
                    // 点击事件
                    onClick();
                }
                return true;
        }
        return false;
    }
    
    /**
     * 点击事件：唤起助理
     */
    private void onClick() {
        Log.i(TAG, "Floating button clicked");
        
        // 启动助理
        Intent intent = new Intent("com.ainative.ACTION_SHOW_ASSISTANT");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
    
    /**
     * 长按事件：显示快捷指令菜单
     */
    private void onLongPress() {
        Log.i(TAG, "Floating button long pressed");
        // TODO: 显示快捷指令菜单（D1.2要求）
    }
    
    /**
     * 贴边动画
     */
    private void snapToEdge() {
        Display display = mWindowManager.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        
        int screenCenterX = size.x / 2;
        
        // 判断靠近哪一边
        if (mLayoutParams.x < screenCenterX) {
            // 贴左边
            mLayoutParams.x = -dpToPx(BUTTON_SIZE_DP - COLLAPSED_SIZE_DP);
        } else {
            // 贴右边
            mLayoutParams.x = size.x - dpToPx(COLLAPSED_SIZE_DP);
        }
        
        mWindowManager.updateViewLayout(mFloatingButton, mLayoutParams);
        Log.d(TAG, "Snapped to edge");
    }
    
    /**
     * 显示悬浮球
     */
    public void show() {
        if (mIsShowing) {
            return;
        }
        
        mWindowManager.addView(mFloatingButton, mLayoutParams);
        mIsShowing = true;
        
        Log.i(TAG, "Floating button shown");
    }
    
    /**
     * 隐藏悬浮球
     */
    public void hide() {
        if (!mIsShowing) {
            return;
        }
        
        mWindowManager.removeView(mFloatingButton);
        mIsShowing = false;
        
        Log.i(TAG, "Floating button hidden");
    }
    
    /**
     * 更新任务状态指示（D1.3）
     * 
     * @param hasActiveTask 是否有活跃任务
     */
    public void updateTaskStatus(boolean hasActiveTask) {
        if (hasActiveTask) {
            // 显示任务指示（例如：改变颜色、添加徽章）
            mFloatingButton.setBackgroundColor(0xCCFF9800); // 橙色表示有任务
        } else {
            // 恢复正常状态
            mFloatingButton.setBackgroundColor(0xCC2196F3); // 蓝色
        }
    }
    
    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        show();
        return START_STICKY;
    }
    
    /** 任务状态广播接收器（P2：由 BackgroundAssistantManager 广播驱动悬浮球） */
    private final BroadcastReceiver mTaskStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            boolean hasActive = intent.getBooleanExtra(
                    com.android.systemui.ainative.background.BackgroundAssistantManager
                            .EXTRA_HAS_ACTIVE_TASK, false);
            int count = intent.getIntExtra(
                    com.android.systemui.ainative.background.BackgroundAssistantManager
                            .EXTRA_TASK_COUNT, 0);
            Log.d(TAG, "Task status: hasActive=" + hasActive + " count=" + count);
            onTaskStatusChanged(hasActive, count);
        }
    };

    private boolean mTaskReceiverRegistered;

    /** 注册任务状态广播 */
    public void registerTaskStatusReceiver() {
        if (mTaskReceiverRegistered) {
            return;
        }
        IntentFilter f = new IntentFilter(
                com.android.systemui.ainative.background.BackgroundAssistantManager
                        .ACTION_UPDATE_TASK_STATUS);
        // [P2 修复] 同上：显式声明导出标志（仅同应用内广播）
        registerReceiver(mTaskStatusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        mTaskReceiverRegistered = true;
    }

    /** 注销任务状态广播 */
    private void unregisterTaskStatusReceiver() {
        if (!mTaskReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(mTaskStatusReceiver);
        } catch (Throwable t) {
            Log.w(TAG, "unregister failed", t);
        }
        mTaskReceiverRegistered = false;
    }

    /** 任务状态变化（子类/后续可覆盖以更新徽标） */
    protected void onTaskStatusChanged(boolean hasActiveTask, int taskCount) {
        Log.i(TAG, "Floating button task status updated: active=" + hasActiveTask
                + ", count=" + taskCount);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // D1.3：注销广播接收器
        unregisterTaskStatusReceiver();
        
        hide();
        Log.i(TAG, "Floating button service destroyed");
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
