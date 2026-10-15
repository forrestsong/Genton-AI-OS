/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode.entry

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 侧边栏面板视图：手势跟手反馈
 * 
 * 功能：
 * - 手势滑动时从边缘跟随手指显示半透明面板
 * - 滑动距离 <80dp：透明度 0 → 50%
 * - 滑动距离 ≥80dp：透明度 50% → 100%（触发）
 * - 触发后展开为侧边栏面板（显示任务列表/快速指令）
 */
class EdgePanelView(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "EdgePanelView"
        private const val PANEL_MAX_WIDTH_DP = 300  // 面板最大宽度
    }
    
    // WindowManager
    private val windowManager: WindowManager = 
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // 面板视图
    private var panelView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    
    // 当前可见性
    @Volatile
    private var isVisible: Boolean = false
    
    // 面板侧边（左侧/右侧）
    private var isLeftSide: Boolean = true
    
    /**
     * 初始化面板视图
     */
    fun initialize() {
        Log.d(TAG, "Initializing EdgePanelView")
        
        // 创建面板视图
        createPanelView()
        
        // 创建布局参数
        createLayoutParams()
        
        // 添加到窗口（初始不可见）
        windowManager.addView(panelView, layoutParams)
        panelView?.visibility = View.GONE
        
        Log.d(TAG, "EdgePanelView initialized")
    }
    
    /**
     * 显示面板（开始跟手）
     */
    fun show(isLeftSide: Boolean) {
        if (isVisible) {
            return
        }
        
        this.isLeftSide = isLeftSide
        
        Log.d(TAG, "Showing EdgePanelView: isLeftSide=$isLeftSide")
        
        // 更新布局参数（左侧或右侧）
        layoutParams?.let {
            it.gravity = if (isLeftSide) {
                Gravity.START or Gravity.TOP
            } else {
                Gravity.END or Gravity.TOP
            }
            it.width = 0  // 初始宽度为 0
            windowManager.updateViewLayout(panelView, it)
        }
        
        panelView?.visibility = View.VISIBLE
        panelView?.alpha = 0f
        
        isVisible = true
    }
    
    /**
     * 更新进度（跟手）
     * 
     * @param progress 0.0 ~ 1.0
     */
    fun updateProgress(progress: Float) {
        if (!isVisible) {
            return
        }
        
        val density = context.resources.displayMetrics.density
        val maxWidthPx = (PANEL_MAX_WIDTH_DP * density).toInt()
        
        // 宽度：0 → maxWidth（线性）
        val widthPx = (maxWidthPx * progress).toInt()
        
        // 透明度：0 → 0.5（前半段），0.5 → 1.0（后半段）
        val alpha = if (progress < 0.5f) {
            progress  // 0 → 0.5
        } else {
            0.5f + (progress - 0.5f)  // 0.5 → 1.0
        }
        
        // 更新视图
        layoutParams?.let {
            it.width = widthPx
            windowManager.updateViewLayout(panelView, it)
        }
        
        panelView?.alpha = alpha
    }
    
    /**
     * 展开面板（触发后）
     */
    fun expand() {
        if (!isVisible) {
            return
        }
        
        Log.d(TAG, "Expanding EdgePanelView")
        
        val density = context.resources.displayMetrics.density
        val maxWidthPx = (PANEL_MAX_WIDTH_DP * density).toInt()
        
        // 展开动画
        panelView?.animate()
            ?.translationX(if (isLeftSide) 0f else 0f)
            ?.alpha(1f)
            ?.setDuration(300L)
            ?.start()
        
        layoutParams?.let {
            it.width = maxWidthPx
            windowManager.updateViewLayout(panelView, it)
        }
    }
    
    /**
     * 隐藏面板
     */
    fun hide() {
        if (!isVisible) {
            return
        }
        
        Log.d(TAG, "Hiding EdgePanelView")
        
        // 收回动画
        panelView?.animate()
            ?.alpha(0f)
            ?.setDuration(200L)
            ?.withEndAction {
                panelView?.visibility = View.GONE
                isVisible = false
            }
            ?.start()
    }
    
    /**
     * 销毁面板
     */
    fun destroy() {
        Log.d(TAG, "Destroying EdgePanelView")
        
        panelView?.let {
            windowManager.removeView(it)
        }
        
        panelView = null
        layoutParams = null
        isVisible = false
    }
    
    /**
     * 获取根视图（用于触觉反馈）
     */
    fun getRootView(): View? {
        return panelView
    }
    
    // ========== 视图创建 ==========
    
    /**
     * 创建面板视图
     */
    private fun createPanelView() {
        panelView = FrameLayout(context).apply {
            // 半透明背景
            setBackgroundColor(0x80000000.toInt())
            
            // TODO: 添加面板内容（任务列表、快速指令）
        }
    }
    
    /**
     * 创建布局参数
     */
    private fun createLayoutParams() {
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = 0  // 初始宽度
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.START or Gravity.TOP
            title = "EdgePanelView"
        }
    }
}
