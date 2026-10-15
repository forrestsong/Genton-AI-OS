// AI Native OS - System UI Main Activity
package com.android.systemui.ainative;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.ainative.AssistantSurfaceController;
import com.android.systemui.ainative.AssistantSurfaceController.Mode;

/**
 * AI Native OS 系统UI主Activity
 * 
 * 职责：
 * - 托管双模式容器
 * - 接收模式切换请求（从入口触发）
 * - 协调系统级UI生命周期
 */
public class AINativeSystemUIActivity extends Activity {
    private static final String TAG = "AINativeSystemUIActivity";
    
    private AssistantSurfaceController mSurfaceController;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置全屏标志
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );
        
        // 初始化双模式容器
        mSurfaceController = new AssistantSurfaceController(this);
        mSurfaceController.initialize();
        
        // 设置模式切换监听
        mSurfaceController.setOnModeChangeListener(new AssistantSurfaceController.OnModeChangeListener() {
            @Override
            public void onModeChanged(Mode oldMode, Mode newMode) {
                Log.i(TAG, "Mode changed: " + oldMode + " -> " + newMode);
                // TODO: 通知其他组件（悬浮球、状态栏等）
            }
            
            @Override
            public void onModeChanging(Mode fromMode, Mode toMode) {
                Log.d(TAG, "Mode changing: " + fromMode + " -> " + toMode);
            }
        });
        
        // 设置根视图
        setContentView(mSurfaceController.getRootView());
        
        // 处理启动意图
        handleLaunchIntent();
        
        Log.i(TAG, "AI Native SystemUI Activity created");
    }
    
    /**
     * 处理启动意图
     */
    private void handleLaunchIntent() {
        String action = getIntent().getAction();
        if ("com.ainative.ACTION_SHOW_ASSISTANT".equals(action)) {
            // 直接切换到助理模式
            mSurfaceController.switchMode(Mode.ASSISTANT, false);
        }
        // 默认保持在 LAUNCHER 模式
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Activity resumed");
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Activity paused");
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (mSurfaceController != null) {
            mSurfaceController.destroy();
            mSurfaceController = null;
        }
        
        Log.i(TAG, "Activity destroyed");
    }
    
    @Override
    public void onBackPressed() {
        // 后退键处理
        Mode currentMode = mSurfaceController.getCurrentMode();
        if (currentMode == Mode.ASSISTANT) {
            // 从助理模式返回桌面
            mSurfaceController.switchMode(Mode.LAUNCHER, true);
        } else {
            // 桌面模式下后退键返回主屏
            super.onBackPressed();
        }
    }
    
    /**
     * 外部调用：切换到助理模式
     */
    public void showAssistant(boolean animated) {
        if (mSurfaceController != null) {
            mSurfaceController.switchMode(Mode.ASSISTANT, animated);
        }
    }
    
    /**
     * 外部调用：切换到桌面模式
     */
    public void showLauncher(boolean animated) {
        if (mSurfaceController != null) {
            mSurfaceController.switchMode(Mode.LAUNCHER, animated);
        }
    }
    
    /**
     * 获取当前模式
     */
    public Mode getCurrentMode() {
        return mSurfaceController != null ? mSurfaceController.getCurrentMode() : Mode.LAUNCHER;
    }
}
