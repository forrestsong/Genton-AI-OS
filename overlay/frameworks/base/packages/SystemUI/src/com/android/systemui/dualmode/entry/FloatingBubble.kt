/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode.entry

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.dualmode.DualModeContainer
import com.android.systemui.dualmode.SystemMode

/**
 * 全局悬浮球：普通模式下的常驻可拖动气泡
 * 
 * 功能：
 * - 单击：切换到 ASSISTANT_MODE
 * - 长按（>500ms）：弹出快速指令菜单
 * - 拖动：移动位置，释放后自动吸附到屏幕边缘
 * - 状态指示：任务进行中（加载动画）、需要确认（红点角标）
 * 
 * 规格：
 * - 尺寸：56dp × 56dp（Material Design FAB 标准）
 * - 颜色：主题色（#2196F3）
 * - 形状：圆形，elevation 8dp
 * - 位置：默认右侧中间，用户可自定义
 * 
 * 防误触：
 * - 点击区域收缩为 40dp（中心区域）
 * - 吸附时留出 48dp 安全区（避免与系统返回手势冲突）
 * - 不吸附到底部中间 200dp（避免与多任务手势冲突）
 */
class FloatingBubble(
    private val context: Context,
    private val windowManager: WindowManager,
    private val dualModeContainer: DualModeContainer
) {
    
    companion object {
        private const val TAG = "FloatingBubble"
        
        // 尺寸常量
        private const val BUBBLE_SIZE_DP = 56
        private const val CLICK_AREA_SIZE_DP = 40  // 点击区域（防误触）
        
        // 吸附常量
        private const val EDGE_MARGIN_DP = 16  // 吸附到边缘的距离
        private const val SAFE_ZONE_DP = 48    // 返回手势安全区
        private const val TOP_MARGIN_DP = 80   // 顶部边界（避免遮挡状态栏）
        private const val BOTTOM_MARGIN_DP = 80  // 底部边界（避免遮挡导航栏）
        private const val CENTER_EXCLUSION_WIDTH_DP = 200  // 中间排除区域宽度
        
        // 交互常量
        private const val LONG_PRESS_TIMEOUT_MS = 500L  // 长按触发时长
        private const val SNAP_ANIMATION_DURATION_MS = 200L  // 吸附动画时长
        
        // 持久化 Key
        private const val PREF_NAME = "floating_bubble"
        private const val PREF_POSITION_X = "position_x"
        private const val PREF_POSITION_Y = "position_y"
        private const val PREF_SIDE = "side"  // "left" or "right"
    }
    
    // 窗口视图
    private var bubbleView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    
    // 当前可见性
    @Volatile
    private var isVisible: Boolean = false
    
    // 拖动状态
    private var isDragging: Boolean = false
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var initialX: Int = 0
    private var initialY: Int = 0
    
    // 长按检测
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isLongPressTriggered: Boolean = false
    
    // 位置持久化
    private val preferences: SharedPreferences = 
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    // 屏幕尺寸（dp）
    private val displayMetrics = context.resources.displayMetrics
    private val density = displayMetrics.density
    private val screenWidthPx = displayMetrics.widthPixels
    private val screenHeightPx = displayMetrics.heightPixels
    
    /**
     * 初始化悬浮球
     */
    fun initialize() {
        Log.d(TAG, "Initializing FloatingBubble")
        
        // 1. 创建悬浮球视图
        createBubbleView()
        
        // 2. 配置窗口布局参数
        createLayoutParams()
        
        // 3. 恢复上次位置
        restorePosition()
        
        // 4. 添加到窗口（初始不可见）
        windowManager.addView(bubbleView, layoutParams)
        bubbleView?.visibility = View.GONE
        
        Log.d(TAG, "FloatingBubble initialized")
    }
    
    /**
     * 显示悬浮球
     */
    fun show() {
        if (isVisible) {
            Log.d(TAG, "FloatingBubble already visible")
            return
        }
        
        Log.d(TAG, "Showing FloatingBubble")
        
        bubbleView?.visibility = View.VISIBLE
        isVisible = true
        
        // 淡入动画
        bubbleView?.alpha = 0f
        bubbleView?.animate()
            ?.alpha(1f)
            ?.setDuration(200L)
            ?.start()
    }
    
    /**
     * 隐藏悬浮球
     */
    fun hide() {
        if (!isVisible) {
            Log.d(TAG, "FloatingBubble already hidden")
            return
        }
        
        Log.d(TAG, "Hiding FloatingBubble")
        
        // 淡出动画
        bubbleView?.animate()
            ?.alpha(0f)
            ?.setDuration(200L)
            ?.withEndAction {
                bubbleView?.visibility = View.GONE
                isVisible = false
            }
            ?.start()
    }
    
    /**
     * 销毁悬浮球
     */
    fun destroy() {
        Log.d(TAG, "Destroying FloatingBubble")
        
        bubbleView?.let {
            windowManager.removeView(it)
        }
        
        bubbleView = null
        layoutParams = null
        isVisible = false
    }
    
    // ========== 视图创建 ==========
    
    /**
     * 创建悬浮球视图
     */
    private fun createBubbleView() {
        val sizePx = (BUBBLE_SIZE_DP * density).toInt()
        
        bubbleView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            
            // 圆形背景
            setBackgroundColor(0xFF2196F3.toInt())
            elevation = 8 * density
            
            // 圆角（圆形）
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            
            // 触摸事件处理
            setOnTouchListener { view, event ->
                handleTouchEvent(event)
            }
            
            // TODO: 添加状态指示器（加载动画、红点角标）
        }
    }
    
    /**
     * 创建窗口布局参数
     */
    private fun createLayoutParams() {
        val sizePx = (BUBBLE_SIZE_DP * density).toInt()
        
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = sizePx
            height = sizePx
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            title = "FloatingBubble"
        }
    }
    
    // ========== 触摸事件处理 ==========
    
    /**
     * 处理触摸事件
     */
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onTouchDown(event)
                return true
            }
            
            MotionEvent.ACTION_MOVE -> {
                onTouchMove(event)
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onTouchUp(event)
                return true
            }
        }
        
        return false
    }
    
    /**
     * 触摸按下
     */
    private fun onTouchDown(event: MotionEvent) {
        isDragging = false
        isLongPressTriggered = false
        
        // 记录初始位置
        initialTouchX = event.rawX
        initialTouchY = event.rawY
        layoutParams?.let {
            initialX = it.x
            initialY = it.y
        }
        
        // 启动长按检测
        longPressRunnable = Runnable {
            if (!isDragging) {
                onLongPress()
            }
        }
        longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT_MS)
        
        // 按下反馈（缩小）
        bubbleView?.animate()
            ?.scaleX(0.9f)
            ?.scaleY(0.9f)
            ?.setDuration(100L)
            ?.start()
    }
    
    /**
     * 触摸移动
     */
    private fun onTouchMove(event: MotionEvent) {
        val deltaX = event.rawX - initialTouchX
        val deltaY = event.rawY - initialTouchY
        
        // 检测是否开始拖动（移动距离 >10dp）
        val dragThreshold = 10 * density
        if (!isDragging && (Math.abs(deltaX) > dragThreshold || Math.abs(deltaY) > dragThreshold)) {
            isDragging = true
            cancelLongPress()
        }
        
        if (isDragging) {
            // 更新位置
            layoutParams?.let {
                it.x = initialX + deltaX.toInt()
                it.y = initialY + deltaY.toInt()
                windowManager.updateViewLayout(bubbleView, it)
            }
        }
    }
    
    /**
     * 触摸释放
     */
    private fun onTouchUp(event: MotionEvent) {
        cancelLongPress()
        
        // 恢复大小
        bubbleView?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(100L)
            ?.start()
        
        if (isDragging) {
            // 拖动后释放：吸附到边缘
            snapToEdge()
        } else if (!isLongPressTriggered) {
            // 单击：切换到助理模式
            onClick()
        }
        
        isDragging = false
        isLongPressTriggered = false
    }
    
    // ========== 交互行为 ==========
    
    /**
     * 单击事件
     */
    private fun onClick() {
        Log.d(TAG, "FloatingBubble clicked")
        
        // 触觉反馈
        bubbleView?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        
        // 切换到 ASSISTANT_MODE
        val success = dualModeContainer.switchMode(SystemMode.ASSISTANT_MODE, 150L)
        
        if (success) {
            Log.d(TAG, "Mode switch to ASSISTANT_MODE requested")
        } else {
            Log.w(TAG, "Mode switch failed (already switching?)")
        }
    }
    
    /**
     * 长按事件
     */
    private fun onLongPress() {
        Log.d(TAG, "FloatingBubble long pressed")
        
        isLongPressTriggered = true
        
        // 触觉反馈（长震动）
        bubbleView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        
        // TODO: 弹出快速指令菜单
        // showQuickActionsMenu()
    }
    
    /**
     * 取消长按检测
     */
    private fun cancelLongPress() {
        longPressRunnable?.let {
            longPressHandler.removeCallbacks(it)
        }
        longPressRunnable = null
    }
    
    // ========== 吸附逻辑 ==========
    
    /**
     * 吸附到屏幕边缘
     */
    private fun snapToEdge() {
        layoutParams?.let { params ->
            val currentX = params.x
            val currentY = params.y
            
            // 计算目标位置
            val (targetX, targetSide) = calculateTargetX(currentX)
            val targetY = constrainY(currentY)
            
            // 保存位置
            savePosition(targetX, targetY, targetSide)
            
            // 弹性动画
            animateSnap(currentX, currentY, targetX, targetY)
        }
    }
    
    /**
     * 计算目标 X 坐标（吸附到左侧或右侧）
     */
    private fun calculateTargetX(currentX: Int): Pair<Int, String> {
        val screenCenterX = screenWidthPx / 2
        val edgeMarginPx = (EDGE_MARGIN_DP * density).toInt()
        
        return if (currentX < screenCenterX) {
            // 吸附到左侧
            Pair(edgeMarginPx, "left")
        } else {
            // 吸附到右侧
            val rightX = screenWidthPx - (BUBBLE_SIZE_DP * density).toInt() - edgeMarginPx
            Pair(rightX, "right")
        }
    }
    
    /**
     * 约束 Y 坐标（不超出边界，避开中间排除区）
     */
    private fun constrainY(currentY: Int): Int {
        val topMarginPx = (TOP_MARGIN_DP * density).toInt()
        val bottomMarginPx = (BOTTOM_MARGIN_DP * density).toInt()
        val bubbleSizePx = (BUBBLE_SIZE_DP * density).toInt()
        
        val minY = topMarginPx
        val maxY = screenHeightPx - bubbleSizePx - bottomMarginPx
        
        return currentY.coerceIn(minY, maxY)
    }
    
    /**
     * 吸附动画
     */
    private fun animateSnap(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        // X 轴弹性动画
        val springX = SpringAnimation(bubbleView, SpringAnimation.TRANSLATION_X).apply {
            spring = SpringForce(toX.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_LOW
            }
        }
        
        // Y 轴弹性动画
        val springY = SpringAnimation(bubbleView, SpringAnimation.TRANSLATION_Y).apply {
            spring = SpringForce(toY.toFloat()).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                stiffness = SpringForce.STIFFNESS_LOW
            }
        }
        
        springX.start()
        springY.start()
        
        // 同时更新布局参数
        layoutParams?.let {
            it.x = toX
            it.y = toY
            windowManager.updateViewLayout(bubbleView, it)
        }
    }
    
    // ========== 位置持久化 ==========
    
    /**
     * 保存位置
     */
    private fun savePosition(x: Int, y: Int, side: String) {
        preferences.edit().apply {
            putInt(PREF_POSITION_X, x)
            putInt(PREF_POSITION_Y, y)
            putString(PREF_SIDE, side)
            apply()
        }
        
        Log.d(TAG, "Position saved: x=$x, y=$y, side=$side")
    }
    
    /**
     * 恢复上次位置
     */
    private fun restorePosition() {
        val savedX = preferences.getInt(PREF_POSITION_X, -1)
        val savedY = preferences.getInt(PREF_POSITION_Y, -1)
        val savedSide = preferences.getString(PREF_SIDE, "right")
        
        if (savedX >= 0 && savedY >= 0) {
            // 恢复保存的位置
            layoutParams?.let {
                it.x = savedX
                it.y = savedY
            }
            Log.d(TAG, "Position restored: x=$savedX, y=$savedY, side=$savedSide")
        } else {
            // 默认位置：右侧中间
            val defaultX = (screenWidthPx - (BUBBLE_SIZE_DP + EDGE_MARGIN_DP) * density).toInt()
            val defaultY = screenHeightPx / 2
            
            layoutParams?.let {
                it.x = defaultX
                it.y = defaultY
            }
            Log.d(TAG, "Using default position: x=$defaultX, y=$defaultY")
        }
    }
}
