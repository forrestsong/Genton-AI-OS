// AI Native OS - Launcher View Controller
package com.android.systemui.genton.launcher;

import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;

/**
 * Launcher 视图控制器：管理桌面模式
 * 
 * 职责：
 * - 集成 Launcher3
 * - 桌面生命周期管理
 * - 与双模式容器协调
 */
public class LauncherViewController {
    private static final String TAG = "LauncherViewController";
    
    private final Context mContext;
    private final ViewGroup mContainer;
    
    private LauncherView mLauncherView;
    private boolean mIsActive = true; // 默认活跃（桌面模式）
    
    public LauncherViewController(Context context, ViewGroup container) {
        mContext = context;
        mContainer = container;
    }
    
    /**
     * 初始化 Launcher 视图
     */
    public void initialize() {
        // 创建 Launcher 视图
        mLauncherView = new LauncherView(mContext);
        mContainer.removeAllViews();
        mContainer.addView(mLauncherView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        
        mLauncherView.initialize();
        
        Log.i(TAG, "Launcher view initialized");
    }
    
    /**
     * 成为活跃状态
     */
    public void onBecomeActive() {
        mIsActive = true;
        if (mLauncherView != null) {
            mLauncherView.onResume();
        }
        Log.d(TAG, "Launcher view became active");
    }
    
    /**
     * 成为非活跃状态
     */
    public void onBecomeInactive() {
        mIsActive = false;
        if (mLauncherView != null) {
            mLauncherView.onPause();
        }
        Log.d(TAG, "Launcher view became inactive");
    }
    
    /**
     * 是否活跃
     */
    public boolean isActive() {
        return mIsActive;
    }
    
    /**
     * 获取 Launcher 视图
     */
    public LauncherView getLauncherView() {
        return mLauncherView;
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        if (mLauncherView != null) {
            mLauncherView.destroy();
            mLauncherView = null;
        }
        mContainer.removeAllViews();
        Log.i(TAG, "Launcher view destroyed");
    }
}
