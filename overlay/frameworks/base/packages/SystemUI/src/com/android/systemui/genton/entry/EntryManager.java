// AI Native OS - Entry Manager
package com.android.systemui.genton.entry;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 入口管理器：统一管理三种入口（悬浮球/侧边手势/QS Tile）
 * 
 * 职责：
 * - 启动/停止各入口
 * - 协调入口状态
 * - 处理配置变更
 */
public class EntryManager {
    private static final String TAG = "EntryManager";
    
    private final Context mContext;
    
    private FloatingButtonService mFloatingButtonService;
    private EdgeGestureHandler mEdgeGestureHandler;
    
    private boolean mIsFloatingButtonEnabled = true;
    private boolean mIsEdgeGestureEnabled = true;
    
    public EntryManager(Context context) {
        mContext = context;
    }
    
    /**
     * 初始化所有入口
     */
    public void initialize() {
        // 启动悬浮球服务
        if (mIsFloatingButtonEnabled) {
            startFloatingButton();
        }
        
        // 初始化侧边手势
        if (mIsEdgeGestureEnabled) {
            mEdgeGestureHandler = new EdgeGestureHandler(mContext);
        }
        
        Log.i(TAG, "Entry manager initialized");
    }
    
    /**
     * 启动悬浮球
     */
    private void startFloatingButton() {
        Intent intent = new Intent(mContext, FloatingButtonService.class);
        mContext.startService(intent);
        Log.d(TAG, "Floating button started");
    }
    
    /**
     * 停止悬浮球
     */
    private void stopFloatingButton() {
        Intent intent = new Intent(mContext, FloatingButtonService.class);
        mContext.stopService(intent);
        Log.d(TAG, "Floating button stopped");
    }
    
    /**
     * 启用/禁用悬浮球
     */
    public void setFloatingButtonEnabled(boolean enabled) {
        if (mIsFloatingButtonEnabled == enabled) {
            return;
        }
        
        mIsFloatingButtonEnabled = enabled;
        
        if (enabled) {
            startFloatingButton();
        } else {
            stopFloatingButton();
        }
        
        Log.i(TAG, "Floating button " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * 启用/禁用侧边手势
     */
    public void setEdgeGestureEnabled(boolean enabled) {
        mIsEdgeGestureEnabled = enabled;
        
        if (mEdgeGestureHandler != null) {
            mEdgeGestureHandler.setEnabled(enabled);
        }
        
        Log.i(TAG, "Edge gesture " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * 获取边缘手势处理器
     */
    public EdgeGestureHandler getEdgeGestureHandler() {
        return mEdgeGestureHandler;
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        stopFloatingButton();
        
        if (mEdgeGestureHandler != null) {
            mEdgeGestureHandler.setEnabled(false);
            mEdgeGestureHandler = null;
        }
        
        Log.i(TAG, "Entry manager destroyed");
    }
}
