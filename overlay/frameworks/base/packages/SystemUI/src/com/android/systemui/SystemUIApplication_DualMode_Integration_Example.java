/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * SystemUI 启动入口修改示例
 * 
 * 修改位置：frameworks/base/packages/SystemUI/src/com/android/systemui/SystemUIApplication.java
 * 
 * 说明：本文件仅为示例，展示如何在 SystemUIApplication 中集成双模式容器
 */

package com.android.systemui;

import android.app.Application;
import android.util.Log;

public class SystemUIApplication extends Application {
    
    private static final String TAG = "SystemUIApplication";
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        Log.i(TAG, "SystemUI starting...");
        
        // ========== 双模式容器集成（新增） ==========
        // 
        // 在 SystemUI 启动时初始化双模式容器
        // 位置：所有其他初始化之后，但在 startServicesIfNeeded() 之前
        //
        initializeDualMode();
        
        // ========== 原有 SystemUI 初始化逻辑 ==========
        // 
        // startServicesIfNeeded();
        // ...
        //
    }
    
    /**
     * 初始化双模式容器（新增方法）
     */
    private void initializeDualMode() {
        try {
            Log.d(TAG, "Initializing DualMode container...");
            
            // 获取双模式集成单例并初始化
            DualModeIntegration dualModeIntegration = DualModeIntegration.getInstance(this);
            dualModeIntegration.initialize();
            
            Log.i(TAG, "DualMode container initialized successfully");
            
        } catch (Exception e) {
            // 双模式容器初始化失败不应导致 SystemUI 崩溃
            // 记录错误并继续（降级到单模式）
            Log.e(TAG, "Failed to initialize DualMode container", e);
        }
    }
    
    @Override
    public void onTerminate() {
        super.onTerminate();
        
        // 销毁双模式容器
        try {
            DualModeIntegration.getInstance(this).destroy();
        } catch (Exception e) {
            Log.e(TAG, "Failed to destroy DualMode container", e);
        }
    }
}
