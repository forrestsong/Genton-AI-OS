// AI Native OS - Edge Gesture Handler
package com.android.systemui.genton.entry;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * 侧边手势处理器：从屏幕边缘滑入唤起助理
 * 
 * 职责：
 * - 监听屏幕边缘手势
 * - 防误触判断
 * - 唤起助理
 */
public class EdgeGestureHandler {
    private static final String TAG = "EdgeGestureHandler";
    
    // 边缘区域宽度（dp）
    private static final int EDGE_WIDTH_DP = 20;
    
    // 滑动距离阈值（dp）
    private static final int SWIPE_THRESHOLD_DP = 50;
    
    // 速度阈值（dp/s）
    private static final int VELOCITY_THRESHOLD_DP = 1000;
    
    private final Context mContext;
    private final Handler mHandler;
    private final GestureDetector mGestureDetector;
    
    private int mScreenWidth;
    private int mScreenHeight;
    private int mEdgeWidthPx;
    private int mSwipeThresholdPx;
    
    private boolean mIsEnabled = true;
    
    public EdgeGestureHandler(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        
        // 获取屏幕尺寸
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mScreenWidth = wm.getDefaultDisplay().getWidth();
        mScreenHeight = wm.getDefaultDisplay().getHeight();
        
        // 转换尺寸
        float density = context.getResources().getDisplayMetrics().density;
        mEdgeWidthPx = (int) (EDGE_WIDTH_DP * density);
        mSwipeThresholdPx = (int) (SWIPE_THRESHOLD_DP * density);
        
        // 创建手势检测器
        mGestureDetector = new GestureDetector(context, new GestureListener());
        
        Log.i(TAG, "Edge gesture handler initialized");
    }
    
    /**
     * 处理触摸事件
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (!mIsEnabled) {
            return false;
        }
        
        return mGestureDetector.onTouchEvent(event);
    }
    
    /**
     * 判断是否在边缘区域
     */
    private boolean isInEdgeArea(float x, float y) {
        // 左边缘或右边缘
        boolean isLeftEdge = x <= mEdgeWidthPx;
        boolean isRightEdge = x >= (mScreenWidth - mEdgeWidthPx);
        
        // 排除顶部和底部的系统手势区域
        boolean isInMiddleArea = y > 100 && y < (mScreenHeight - 100);
        
        return (isLeftEdge || isRightEdge) && isInMiddleArea;
    }
    
    /**
     * 唤起助理
     */
    private void triggerAssistant() {
        Log.i(TAG, "Edge gesture triggered");
        
        Intent intent = new Intent("com.genton.ACTION_SHOW_ASSISTANT");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }
    
    /**
     * 启用/禁用手势
     */
    public void setEnabled(boolean enabled) {
        mIsEnabled = enabled;
        Log.d(TAG, "Edge gesture " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * 手势监听器
     */
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        private float mStartX;
        private float mStartY;
        
        @Override
        public boolean onDown(MotionEvent e) {
            // 记录起始位置
            mStartX = e.getX();
            mStartY = e.getY();
            
            // 只处理边缘区域的触摸
            return isInEdgeArea(mStartX, mStartY);
        }
        
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null) {
                return false;
            }
            
            float deltaX = e2.getX() - e1.getX();
            float deltaY = e2.getY() - e1.getY();
            
            // 判断是否为有效的横向滑动
            boolean isHorizontalSwipe = Math.abs(deltaX) > Math.abs(deltaY);
            boolean isValidDistance = Math.abs(deltaX) > mSwipeThresholdPx;
            boolean isValidVelocity = Math.abs(velocityX) > VELOCITY_THRESHOLD_DP;
            
            if (isHorizontalSwipe && isValidDistance && isValidVelocity) {
                // 判断滑动方向
                boolean isLeftEdge = mStartX <= mEdgeWidthPx;
                boolean isRightEdge = mStartX >= (mScreenWidth - mEdgeWidthPx);
                
                boolean isSwipeIn = (isLeftEdge && deltaX > 0) || (isRightEdge && deltaX < 0);
                
                if (isSwipeIn) {
                    triggerAssistant();
                    return true;
                }
            }
            
            return false;
        }
    }
}
