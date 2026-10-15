/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Assistant Surface 窗口：SystemUI 系统窗口，承载 AI 助理界面
 * 
 * 职责：
 * - 持有会话流、任务卡片、输入区等助理界面组件
 * - 管理全屏 ⇄ 悬浮球两种形态
 * - 防止外部截图（FLAG_SECURE）
 * - 作为系统级高优先级窗口（TYPE_SYSTEM_OVERLAY）
 * 
 * 设计要点：
 * - 窗口内容由 SystemUI 独占绘制，Agent 不可伪造
 * - 可见性由 DualModeContainer 控制，不自行决定
 * - 状态数据全部来自 ACB 服务，不缓存业务状态
 */
class AssistantSurfaceWindow(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "AssistantSurfaceWindow"
    }
    
    // 窗口根视图
    var windowView: FrameLayout? = null
        private set
    
    // 窗口布局参数
    private var layoutParams: WindowManager.LayoutParams? = null
    
    // 当前形态：true=全屏，false=悬浮球
    @Volatile
    private var isFullScreen: Boolean = true
    
    // 当前可见性
    @Volatile
    private var isVisible: Boolean = false
    
    // 悬浮球视图（最小化状态）
    private var floatingBubbleView: View? = null
    
    // 助理内容视图（全屏状态）
    private var assistantContentView: View? = null
    
    /**
     * 初始化窗口
     */
    fun initialize() {
        Log.d(TAG, "Initializing Assistant Surface window")
        
        // 1. 创建窗口根视图
        windowView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        // 2. 配置窗口布局参数
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SECURE or  // 防止外部截图
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            format = android.graphics.PixelFormat.TRANSLUCENT
            title = "AssistantSurface"
        }
        
        // 3. 创建助理内容视图（全屏）
        createAssistantContentView()
        
        // 4. 创建悬浮球视图（最小化）
        createFloatingBubbleView()
        
        // 5. 默认显示全屏内容
        showFullScreen()
        
        // 6. 添加窗口
        windowManager.addView(windowView, layoutParams)
        
        Log.d(TAG, "Assistant Surface window initialized")
    }
    
    /**
     * 显示窗口（全屏模式）
     */
    fun show() {
        if (isVisible && isFullScreen) {
            Log.d(TAG, "Assistant Surface already visible in full screen, skipping")
            return
        }
        
        Log.d(TAG, "Showing Assistant Surface (full screen)")
        
        // 提升 Z-order（移到前台）
        layoutParams?.let {
            windowManager.updateViewLayout(windowView, it)
        }
        
        // 显示全屏内容
        showFullScreen()
        windowView?.visibility = View.VISIBLE
        
        isVisible = true
        isFullScreen = true
    }
    
    /**
     * 隐藏窗口
     */
    fun hide() {
        if (!isVisible) {
            Log.d(TAG, "Assistant Surface already hidden, skipping")
            return
        }
        
        Log.d(TAG, "Hiding Assistant Surface")
        
        windowView?.visibility = View.GONE
        isVisible = false
    }
    
    /**
     * 最小化为悬浮球
     */
    fun minimize() {
        if (!isFullScreen) {
            Log.d(TAG, "Assistant Surface already minimized, skipping")
            return
        }
        
        Log.d(TAG, "Minimizing Assistant Surface to floating bubble")
        
        // 切换到悬浮球视图
        showFloatingBubble()
        isFullScreen = false
        isVisible = true
    }
    
    /**
     * 从悬浮球恢复全屏
     */
    fun restore() {
        if (isFullScreen) {
            Log.d(TAG, "Assistant Surface already in full screen, skipping")
            return
        }
        
        Log.d(TAG, "Restoring Assistant Surface from floating bubble")
        
        // 切换到全屏视图
        showFullScreen()
        isFullScreen = true
        isVisible = true
    }
    
    /**
     * 销毁窗口（仅在 SystemUI 关闭时调用）
     */
    fun destroy() {
        Log.d(TAG, "Destroying Assistant Surface window")
        
        windowView?.let {
            windowManager.removeView(it)
        }
        
        windowView = null
        layoutParams = null
        assistantContentView = null
        floatingBubbleView = null
        isVisible = false
        isFullScreen = false
    }
    
    // ========== 内部视图管理 ==========
    
    /**
     * 创建助理内容视图（全屏）
     */
    private fun createAssistantContentView() {
        assistantContentView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            
            // 临时占位背景（后续由 D2.1 会话流替换）
            setBackgroundColor(0xFF1E1E1E.toInt())
            
            // TODO: 添加会话流、任务卡片、输入区等子组件（D2.1）
            // - ConversationView: 会话流
            // - TaskCardView: 任务卡片
            // - InputBar: 输入区
        }
    }
    
    /**
     * 创建悬浮球视图（最小化）
     */
    private fun createFloatingBubbleView() {
        floatingBubbleView = FrameLayout(context).apply {
            val bubbleSize = (56 * context.resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = (16 * context.resources.displayMetrics.density).toInt()
            }
            
            // 圆形悬浮球背景
            setBackgroundColor(0xFF2196F3.toInt())
            
            // 圆角
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            
            // 点击事件：恢复全屏（由 DualModeContainer 处理）
            setOnClickListener {
                Log.d(TAG, "Floating bubble clicked")
                // 触发模式切换事件（回到 ASSISTANT_MODE）
                // 实际处理在 DualModeContainer 中
            }
            
            // TODO: 添加任务状态指示器（D2.3）
        }
    }
    
    /**
     * 显示全屏内容
     */
    private fun showFullScreen() {
        windowView?.let { root ->
            // 移除所有子视图
            root.removeAllViews()
            
            // 添加助理内容视图
            assistantContentView?.let {
                root.addView(it)
            }
        }
    }
    
    /**
     * 显示悬浮球
     */
    private fun showFloatingBubble() {
        windowView?.let { root ->
            // 移除所有子视图
            root.removeAllViews()
            
            // 添加悬浮球视图
            floatingBubbleView?.let {
                root.addView(it)
            }
        }
    }
    
    /**
     * 获取助理内容视图（供外部组件挂载子视图）
     */
    fun getContentView(): View? {
        return assistantContentView
    }
    
    /**
     * 获取悬浮球视图（供外部组件更新状态）
     */
    fun getFloatingBubbleView(): View? {
        return floatingBubbleView
    }
}
