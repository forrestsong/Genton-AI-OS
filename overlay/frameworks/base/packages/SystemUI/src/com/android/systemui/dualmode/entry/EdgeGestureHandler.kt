/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode.entry

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.android.systemui.dualmode.DualModeContainer
import com.android.systemui.dualmode.SystemMode
import kotlin.math.abs
import kotlin.math.atan2

/**
 * 侧边栏手势识别器：屏幕边缘内滑唤出助理
 * 
 * 功能：
 * - 检测屏幕左侧/右侧边缘内滑手势
 * - 触发条件：从边缘 16dp 内开始滑动，向内滑动 >80dp
 * - 速度阈值：>1000 dp/s（防慢速误触）
 * - 角度限制：水平方向 ±30° 内（防斜滑误触）
 * 
 * 行为：
 * - STANDARD_MODE：切换到 ASSISTANT_MODE（全屏）
 * - ASSISTANT_MODE：打开侧边栏面板（半屏，不切换模式）
 * 
 * 防误触：
 * - 触发阈值 >80dp（系统返回手势 ~50dp）
 * - 仅响应单点触控（多点触控取消识别）
 * - 速度过慢取消识别
 */
class EdgeGestureHandler(
    private val context: Context,
    private val dualModeContainer: DualModeContainer
) {
    
    companion object {
        private const val TAG = "EdgeGestureHandler"
        
        // 触发区域（dp）
        private const val EDGE_WIDTH_DP = 16  // 边缘触发区宽度
        
        // 触发阈值
        private const val TRIGGER_DISTANCE_DP = 80  // 滑动距离阈值
        private const val MIN_VELOCITY_DP_PER_SEC = 1000  // 最小速度（防慢速误触）
        private const val MAX_ANGLE_DEGREES = 30  // 角度限制（水平方向 ±30°）
        
        // 视觉反馈
        private const val PANEL_MAX_WIDTH_DP = 300  // 侧边栏面板最大宽度
    }
    
    // 手势状态机
    private enum class GestureState {
        IDLE,           // 空闲
        TOUCH_DOWN,     // 触摸按下
        SLIDING,        // 滑动中
        TRIGGERED,      // 已触发
        CANCELLED       // 已取消
    }
    
    // 当前状态
    private var currentState: GestureState = GestureState.IDLE
    
    // 触摸起始位置
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var startTime: Long = 0L
    
    // 当前位置
    private var currentX: Float = 0f
    private var currentY: Float = 0f
    
    // 手势侧边（左侧/右侧）
    private var gestureSide: GestureSide = GestureSide.NONE
    
    private enum class GestureSide {
        NONE, LEFT, RIGHT
    }
    
    // 屏幕尺寸
    private val displayMetrics = context.resources.displayMetrics
    private val density = displayMetrics.density
    private val screenWidthPx = displayMetrics.widthPixels
    private val screenHeightPx = displayMetrics.heightPixels
    
    // 触发区域宽度（px）
    private val edgeWidthPx = (EDGE_WIDTH_DP * density).toInt()
    
    // 触发距离阈值（px）
    private val triggerDistancePx = (TRIGGER_DISTANCE_DP * density)
    
    // 速度追踪器
    private val velocityTracker = android.view.VelocityTracker.obtain()
    
    // 侧边栏面板视图（跟手效果）
    private var edgePanelView: EdgePanelView? = null
    
    /**
     * 初始化手势识别器
     */
    fun initialize() {
        Log.d(TAG, "Initializing EdgeGestureHandler")
        
        // 创建侧边栏面板视图（用于跟手反馈）
        edgePanelView = EdgePanelView(context)
        edgePanelView?.initialize()
        
        Log.d(TAG, "EdgeGestureHandler initialized")
    }
    
    /**
     * 处理触摸事件（由 SystemUI 全局触摸拦截器调用）
     * 
     * @return true: 事件已消费，false: 事件未消费
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        // 多点触控：取消识别
        if (event.pointerCount > 1) {
            if (currentState != GestureState.IDLE) {
                cancelGesture()
            }
            return false
        }
        
        // 添加到速度追踪器
        velocityTracker.addMovement(event)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                return handleTouchDown(event)
            }
            
            MotionEvent.ACTION_MOVE -> {
                return handleTouchMove(event)
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return handleTouchUp(event)
            }
        }
        
        return false
    }
    
    // ========== 触摸事件处理 ==========
    
    /**
     * 处理触摸按下
     */
    private fun handleTouchDown(event: MotionEvent): Boolean {
        val x = event.rawX
        val y = event.rawY
        
        // 检查是否在边缘触发区内
        val side = detectEdgeSide(x)
        if (side == GestureSide.NONE) {
            return false  // 不在边缘，不处理
        }
        
        // 记录起始位置
        startX = x
        startY = y
        currentX = x
        currentY = y
        startTime = System.currentTimeMillis()
        gestureSide = side
        
        // 进入 TOUCH_DOWN 状态
        currentState = GestureState.TOUCH_DOWN
        
        Log.d(TAG, "Edge touch down: side=$side, x=$x, y=$y")
        
        return false  // 不消费事件，允许其他处理器处理
    }
    
    /**
     * 处理触摸移动
     */
    private fun handleTouchMove(event: MotionEvent): Boolean {
        if (currentState == GestureState.IDLE || currentState == GestureState.CANCELLED) {
            return false
        }
        
        currentX = event.rawX
        currentY = event.rawY
        
        val deltaX = currentX - startX
        val deltaY = currentY - startY
        val distance = abs(deltaX)
        
        // 检查滑动方向（必须向内滑动）
        val isInwardSwipe = when (gestureSide) {
            GestureSide.LEFT -> deltaX > 0  // 左侧向右滑
            GestureSide.RIGHT -> deltaX < 0  // 右侧向左滑
            GestureSide.NONE -> false
        }
        
        if (!isInwardSwipe) {
            cancelGesture()
            return false
        }
        
        // 检查角度（防止斜滑误触）
        val angle = Math.toDegrees(atan2(abs(deltaY).toDouble(), abs(deltaX).toDouble()))
        if (angle > MAX_ANGLE_DEGREES) {
            Log.d(TAG, "Gesture cancelled: angle too steep ($angle°)")
            cancelGesture()
            return false
        }
        
        // 进入 SLIDING 状态
        if (currentState == GestureState.TOUCH_DOWN) {
            currentState = GestureState.SLIDING
            
            // 显示侧边栏面板（跟手反馈）
            edgePanelView?.show(gestureSide == GestureSide.LEFT)
        }
        
        // 更新面板宽度（跟手）
        val progress = (distance / triggerDistancePx).coerceIn(0f, 1f)
        edgePanelView?.updateProgress(progress)
        
        // 检查是否达到触发阈值
        if (distance >= triggerDistancePx && currentState == GestureState.SLIDING) {
            triggerGesture()
            return true  // 消费事件
        }
        
        return currentState == GestureState.SLIDING
    }
    
    /**
     * 处理触摸释放
     */
    private fun handleTouchUp(event: MotionEvent): Boolean {
        val wasTriggered = (currentState == GestureState.TRIGGERED)
        
        if (currentState == GestureState.SLIDING) {
            // 检查速度
            velocityTracker.computeCurrentVelocity(1000)  // 单位：px/s
            val velocityX = abs(velocityTracker.xVelocity)
            val velocityDp = velocityX / density
            
            val distance = abs(currentX - startX)
            
            // 未达到距离阈值但速度足够：也触发
            if (distance < triggerDistancePx && velocityDp >= MIN_VELOCITY_DP_PER_SEC) {
                Log.d(TAG, "Gesture triggered by velocity: ${velocityDp} dp/s")
                triggerGesture()
            } else {
                cancelGesture()
            }
        } else {
            cancelGesture()
        }
        
        // 重置状态
        currentState = GestureState.IDLE
        gestureSide = GestureSide.NONE
        velocityTracker.clear()
        
        return wasTriggered
    }
    
    // ========== 手势识别 ==========
    
    /**
     * 检测触摸位置是否在边缘触发区
     */
    private fun detectEdgeSide(x: Float): GestureSide {
        return when {
            x <= edgeWidthPx -> GestureSide.LEFT
            x >= screenWidthPx - edgeWidthPx -> GestureSide.RIGHT
            else -> GestureSide.NONE
        }
    }
    
    /**
     * 触发手势
     */
    private fun triggerGesture() {
        if (currentState == GestureState.TRIGGERED) {
            return  // 已触发，避免重复
        }
        
        Log.d(TAG, "Edge gesture triggered: side=$gestureSide")
        
        currentState = GestureState.TRIGGERED
        
        // 触觉反馈
        edgePanelView?.getRootView()?.performHapticFeedback(
            android.view.HapticFeedbackConstants.VIRTUAL_KEY
        )
        
        // 展开面板动画
        edgePanelView?.expand()
        
        // 根据当前模式执行不同行为
        val currentMode = dualModeContainer.getCurrentMode()
        when (currentMode) {
            SystemMode.STANDARD_MODE -> {
                // 切换到 ASSISTANT_MODE（全屏）
                dualModeContainer.switchMode(SystemMode.ASSISTANT_MODE, 300L)
            }
            
            SystemMode.ASSISTANT_MODE -> {
                // 打开侧边栏面板（半屏，不切换模式）
                // TODO: 显示任务列表和快速指令
                Log.d(TAG, "Show edge panel in ASSISTANT_MODE")
            }
            
            SystemMode.TRANSITION -> {
                // 正在切换中，不处理
                Log.d(TAG, "Ignore gesture: mode is transitioning")
            }
        }
    }
    
    /**
     * 取消手势
     */
    private fun cancelGesture() {
        Log.d(TAG, "Edge gesture cancelled")
        
        currentState = GestureState.CANCELLED
        
        // 收回面板
        edgePanelView?.hide()
    }
    
    /**
     * 销毁手势识别器
     */
    fun destroy() {
        Log.d(TAG, "Destroying EdgeGestureHandler")
        
        edgePanelView?.destroy()
        edgePanelView = null
        
        velocityTracker.recycle()
    }
}
