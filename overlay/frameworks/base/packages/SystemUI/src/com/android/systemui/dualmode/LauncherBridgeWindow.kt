/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.android.launcher3.ILauncherBridge

/**
 * Launcher 桥接窗口：连接传统 Launcher3 的桥接层
 * 
 * 职责：
 * - 管理 Launcher3 的可见性（通过 WindowManager Z-order）
 * - 拦截 HOME 键事件（根据当前模式路由）
 * - 保持 Launcher 进程常驻（避免冷启动）
 * - 协调与 Launcher3 的生命周期
 * 
 * 设计要点：
 * - 不使用 startActivity，避免 Activity 生命周期重建
 * - Launcher 进程始终保活，只切换窗口可见性
 * - 通过 ILauncherBridge AIDL 与 Launcher 通信
 */
class LauncherBridgeWindow(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "LauncherBridgeWindow"
        private const val LAUNCHER_PACKAGE = "com.android.launcher3"
        private const val LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"
    }
    
    // Launcher 窗口视图（占位容器）
    var windowView: View? = null
        private set
    
    // Launcher 窗口布局参数
    private var layoutParams: WindowManager.LayoutParams? = null
    
    // Launcher Bridge 服务连接
    private var launcherBridge: ILauncherBridge? = null
    private var serviceConnection: ServiceConnection? = null
    
    // 当前可见性状态
    @Volatile
    private var isVisible: Boolean = false
    
    // Launcher 进程是否存活
    @Volatile
    private var isLauncherAlive: Boolean = false
    
    /**
     * 初始化窗口
     */
    fun initialize() {
        Log.d(TAG, "Initializing Launcher bridge window")
        
        // 1. 创建窗口视图（透明占位符，实际内容由 Launcher Activity 绘制）
        windowView = View(context).apply {
            // 透明背景，让 Launcher Activity 内容透过来
            setBackgroundColor(0x00000000)
        }
        
        // 2. 配置窗口布局参数
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            format = android.graphics.PixelFormat.TRANSLUCENT
            title = "LauncherBridge"
        }
        
        // 3. 添加窗口（初始不可见）
        windowManager.addView(windowView, layoutParams)
        windowView?.visibility = View.GONE
        
        // 4. 绑定 Launcher Bridge 服务
        bindLauncherBridge()
        
        // 5. 启动 Launcher 进程并保活
        ensureLauncherAlive()
    }
    
    /**
     * 显示 Launcher
     */
    fun show() {
        if (isVisible) {
            Log.d(TAG, "Launcher already visible, skipping")
            return
        }
        
        Log.d(TAG, "Showing Launcher")
        
        // 1. 确保 Launcher 进程存活
        ensureLauncherAlive()
        
        // 2. 通知 Launcher 即将进入前台（提前 50ms 预热）
        notifyLauncherAboutToShow()
        
        // 3. 延迟 50ms 后显示（给 Launcher 预热时间）
        android.os.Handler(context.mainLooper).postDelayed({
            // 提升 Z-order（移到前台）
            layoutParams?.let {
                windowManager.updateViewLayout(windowView, it)
            }
            
            // 显示窗口
            windowView?.visibility = View.VISIBLE
            isVisible = true
            
            Log.d(TAG, "Launcher shown")
        }, 50L)
    }
    
    /**
     * 隐藏 Launcher（降级到后台，但不销毁）
     */
    fun hide() {
        if (!isVisible) {
            Log.d(TAG, "Launcher already hidden, skipping")
            return
        }
        
        Log.d(TAG, "Hiding Launcher")
        
        // 1. 通知 Launcher 即将进入后台
        notifyLauncherAboutToHide()
        
        // 2. 隐藏窗口（不销毁）
        windowView?.visibility = View.GONE
        isVisible = false
        
        // 3. 降低 Z-order（移到后台）
        layoutParams?.let {
            windowManager.updateViewLayout(windowView, it)
        }
        
        Log.d(TAG, "Launcher hidden")
    }
    
    /**
     * 确保 Launcher 进程常驻（避免被系统杀死）
     */
    fun ensureLauncherAlive() {
        if (isLauncherAlive) {
            return
        }
        
        Log.d(TAG, "Ensuring Launcher process alive")
        
        try {
            // 启动 Launcher Activity（不可见，只启动进程）
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(LAUNCHER_PACKAGE, LAUNCHER_ACTIVITY)
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)  // 无动画
            }
            
            // 启动但不显示（通过窗口控制可见性）
            context.startActivity(intent)
            
            // 通知 Launcher Bridge 强制保活
            launcherBridge?.ensureAlive()
            
            isLauncherAlive = true
            Log.d(TAG, "Launcher process started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Launcher process", e)
            isLauncherAlive = false
        }
    }
    
    /**
     * 检查 Launcher 是否准备就绪
     */
    fun isLauncherReady(): Boolean {
        return try {
            launcherBridge?.isReady ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Launcher ready state", e)
            false
        }
    }
    
    /**
     * 销毁窗口（仅在 SystemUI 关闭时调用）
     */
    fun destroy() {
        Log.d(TAG, "Destroying Launcher bridge window")
        
        // 1. 解绑服务
        unbindLauncherBridge()
        
        // 2. 移除窗口
        windowView?.let {
            windowManager.removeView(it)
        }
        
        windowView = null
        layoutParams = null
        isVisible = false
        isLauncherAlive = false
    }
    
    // ========== Launcher Bridge 服务绑定 ==========
    
    /**
     * 绑定 Launcher Bridge 服务
     */
    private fun bindLauncherBridge() {
        if (serviceConnection != null) {
            Log.d(TAG, "Launcher Bridge already bound")
            return
        }
        
        val intent = Intent().apply {
            component = ComponentName(LAUNCHER_PACKAGE, "$LAUNCHER_PACKAGE.LauncherBridgeService")
        }
        
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                launcherBridge = ILauncherBridge.Stub.asInterface(service)
                Log.d(TAG, "Launcher Bridge connected")
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                launcherBridge = null
                Log.w(TAG, "Launcher Bridge disconnected")
            }
        }
        
        try {
            context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Launcher Bridge", e)
            serviceConnection = null
        }
    }
    
    /**
     * 解绑 Launcher Bridge 服务
     */
    private fun unbindLauncherBridge() {
        serviceConnection?.let {
            try {
                context.unbindService(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind Launcher Bridge", e)
            }
        }
        
        launcherBridge = null
        serviceConnection = null
    }
    
    // ========== Launcher 生命周期通知 ==========
    
    /**
     * 通知 Launcher 即将进入前台
     */
    private fun notifyLauncherAboutToShow() {
        try {
            launcherBridge?.onAboutToShow()
            Log.d(TAG, "Notified Launcher: about to show")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify Launcher about to show", e)
        }
    }
    
    /**
     * 通知 Launcher 即将进入后台
     */
    private fun notifyLauncherAboutToHide() {
        try {
            launcherBridge?.onAboutToHide()
            Log.d(TAG, "Notified Launcher: about to hide")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify Launcher about to hide", e)
        }
    }
}
