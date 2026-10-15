/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui;

import android.content.Context;
import android.util.Log;
import com.android.systemui.dualmode.DualModeContainer;
import com.android.systemui.dualmode.SystemMode;
import com.android.systemui.dualmode.entry.ModeEntryManager;
import com.android.systemui.ainative.accessibility.AccessibilityIntegration;

/**
 * SystemUI 双模式集成类
 * 
 * 职责：
 * - 在 SystemUI 启动时初始化双模式容器
 * - 初始化三入口管理器（D1.2）
 * - 初始化无障碍集成（D8.1）
 * - 提供全局访问双模式容器的接口
 * - 管理双模式容器的生命周期
 * 
 * 集成位置：SystemUIApplication.onCreate()
 */
public class DualModeIntegration {
    
    private static final String TAG = "DualModeIntegration";
    
    // 单例
    private static DualModeIntegration sInstance;
    
    // 双模式容器
    private DualModeContainer mDualModeContainer;
    
    // D1.2 三入口管理器
    private ModeEntryManager mModeEntryManager;
    
    // D8.1 无障碍集成
    private AccessibilityIntegration mAccessibilityIntegration;
    
    private Context mContext;
    
    private DualModeIntegration(Context context) {
        mContext = context.getApplicationContext();
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized DualModeIntegration getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DualModeIntegration(context);
        }
        return sInstance;
    }
    
    /**
     * 初始化双模式容器（在 SystemUIApplication.onCreate() 中调用）
     */
    public void initialize() {
        Log.d(TAG, "Initializing DualMode integration");
        
        if (mDualModeContainer != null) {
            Log.w(TAG, "DualModeContainer already initialized");
            return;
        }
        
        // 创建并初始化双模式容器
        mDualModeContainer = new DualModeContainer(mContext);
        mDualModeContainer.initialize();
        
        Log.i(TAG, "DualMode integration initialized successfully");
    }
    
    /**
     * 获取双模式容器
     */
    public DualModeContainer getDualModeContainer() {
        if (mDualModeContainer == null) {
            Log.w(TAG, "DualModeContainer not initialized, call initialize() first");
        }
        return mDualModeContainer;
    }
    
    /**
     * 切换模式（便捷方法）
     */
    public boolean switchMode(SystemMode targetMode) {
        if (mDualModeContainer == null) {
            Log.e(TAG, "Cannot switch mode: DualModeContainer not initialized");
            return false;
        }
        
        return mDualModeContainer.switchMode(targetMode, 150L);
    }
    
    /**
     * 切换到助理模式
     */
    public boolean switchToAssistantMode() {
        return switchMode(SystemMode.ASSISTANT_MODE);
    }
    
    /**
     * 切换到普通模式
     */
    public boolean switchToStandardMode() {
        return switchMode(SystemMode.STANDARD_MODE);
    }
    
    /**
     * 切换模式（无动画）
     */
    public boolean switchModeInstant(SystemMode targetMode) {
        if (mDualModeContainer == null) {
            Log.e(TAG, "Cannot switch mode: DualModeContainer not initialized");
            return false;
        }
        
        return mDualModeContainer.switchMode(targetMode, 0L);
    }
    
    /**
     * 获取当前模式
     */
    public SystemMode getCurrentMode() {
        if (mDualModeContainer == null) {
            return SystemMode.ASSISTANT_MODE;  // 默认
        }
        
        return mDualModeContainer.getCurrentMode();
    }
    
    /**
     * 检查 Launcher 是否准备就绪
     */
    public boolean isLauncherReady() {
        if (mDualModeContainer == null) {
            return false;
        }
        
        return mDualModeContainer.isLauncherReady();
    }
    
    /**
     * 销毁（SystemUI 关闭时调用）
     */
    public void destroy() {
        Log.d(TAG, "Destroying DualMode integration");
        
        // D1.2: 先销毁三入口管理器
        if (mModeEntryManager != null) {
            mModeEntryManager.destroy();
            mModeEntryManager = null;
        }
        
        if (mDualModeContainer != null) {
            mDualModeContainer.destroy();
            mDualModeContainer = null;
        }
        
        sInstance = null;
    }
}
