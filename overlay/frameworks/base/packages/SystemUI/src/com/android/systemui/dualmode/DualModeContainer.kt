/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager

/**
 * 双模式容器：持有 Assistant Surface 和 Launcher Bridge，管理优先级切换
 * 
 * 核心职责：
 * - 持有两个表面的窗口引用（AssistantSurfaceWindow + LauncherBridgeWindow）
 * - 管理 Z-order 和可见性切换（<1 帧性能目标）
 * - 协调切换动画（硬件加速）
 * - 保证切换期间不销毁任何窗口（零拷贝切换）
 * - 与状态机联动，保证状态一致性
 * 
 * 性能优化策略：
 * 1. 预加载：启动时初始化所有窗口，避免切换时创建
 * 2. 零拷贝：通过 WindowManager Z-order 切换，避免内容重建
 * 3. 硬件加速：动画使用 GPU 加速
 * 4. 预热通知：提前 50ms 通知 Launcher 准备
 * 
 * 目标切换性能：<16ms (1 帧)
 */
class DualModeContainer(
    private val context: Context
) : ModeStateListener {
    
    companion object {
        private const val TAG = "DualModeContainer"
        private const val PRELOAD_DELAY_MS = 50L  // Launcher 预热时长
        private const val ANIMATION_DURATION_MS = 150L  // 切换动画时长
    }
    
    // WindowManager 服务
    private val windowManager: WindowManager = 
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // 状态机
    private val stateMachine: ModeStateMachine = ModeStateMachine(context)
    
    // Assistant Surface 窗口
    private var assistantSurface: AssistantSurfaceWindow? = null
    
    // Launcher Bridge 窗口
    private var launcherBridge: LauncherBridgeWindow? = null
    
    // 当前模式（与状态机同步）
    @Volatile
    private var currentMode: SystemMode = SystemMode.ASSISTANT_MODE
    
    // 性能统计
    private var lastTransitionDurationMs: Long = 0L
    
    init {
        // 注册状态机监听器
        stateMachine.addListener(this)
    }
    
    /**
     * 初始化容器（SystemUI 启动时调用）
     */
    fun initialize() {
        Log.d(TAG, "Initializing DualModeContainer")
        
        // 1. 创建 Assistant Surface 窗口
        assistantSurface = AssistantSurfaceWindow(context, windowManager).apply {
            initialize()
        }
        
        // 2. 创建 Launcher Bridge 窗口
        launcherBridge = LauncherBridgeWindow(context, windowManager).apply {
            initialize()
        }
        
        // 3. 默认显示 Assistant Mode（无动画）
        switchModeInternal(SystemMode.ASSISTANT_MODE, animationDurationMs = 0L)
        
        Log.d(TAG, "DualModeContainer initialized")
    }
    
    /**
     * 切换模式（公开接口）
     * 
     * @param targetMode 目标模式
     * @param animationDurationMs 动画时长（默认 150ms，0 表示无动画）
     * @return true: 切换请求已接受，false: 切换被拒绝
     */
    fun switchMode(targetMode: SystemMode, animationDurationMs: Long = ANIMATION_DURATION_MS): Boolean {
        Log.d(TAG, "Switch mode requested: $currentMode -> $targetMode")
        
        // 1. 请求状态机转换
        if (!stateMachine.requestTransition(targetMode)) {
            Log.w(TAG, "State machine rejected transition")
            return false
        }
        
        // 2. 执行切换（异步，不阻塞）
        android.os.Handler(context.mainLooper).post {
            switchModeInternal(targetMode, animationDurationMs)
        }
        
        return true
    }
    
    /**
     * 内部切换实现
     */
    private fun switchModeInternal(targetMode: SystemMode, animationDurationMs: Long) {
        val startTime = SystemClock.elapsedRealtime()
        
        try {
            when (targetMode) {
                SystemMode.ASSISTANT_MODE -> switchToAssistantMode(animationDurationMs)
                SystemMode.STANDARD_MODE -> switchToStandardMode(animationDurationMs)
                SystemMode.TRANSITION -> {
                    Log.w(TAG, "Cannot switch to TRANSITION state directly")
                    stateMachine.onTransitionFailed(targetMode, "Invalid target state")
                    return
                }
            }
            
            // 切换完成
            currentMode = targetMode
            val duration = SystemClock.elapsedRealtime() - startTime
            lastTransitionDurationMs = duration
            
            stateMachine.onTransitionComplete(targetMode)
            
            Log.d(TAG, "Mode switched to $targetMode in ${duration}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Mode switch failed", e)
            stateMachine.onTransitionFailed(targetMode, e.message ?: "Unknown error")
        }
    }
    
    /**
     * 切换到助理模式
     */
    private fun switchToAssistantMode(animationDurationMs: Long) {
        Log.d(TAG, "Switching to ASSISTANT_MODE")
        
        if (animationDurationMs > 0L) {
            // 1. 启动恢复动画
            animateToAssistant(animationDurationMs)
            
            // 2. 动画结束后切换可见性
            android.os.Handler(context.mainLooper).postDelayed({
                finalizeAssistantMode()
            }, animationDurationMs)
            
        } else {
            // 无动画，直接切换
            finalizeAssistantMode()
        }
    }
    
    /**
     * 完成助理模式切换
     */
    private fun finalizeAssistantMode() {
        // 1. Assistant Surface 从悬浮球恢复全屏
        assistantSurface?.restore()
        assistantSurface?.show()
        
        // 2. Launcher 降至后台（不销毁）
        launcherBridge?.hide()
        
        Log.d(TAG, "ASSISTANT_MODE finalized")
    }
    
    /**
     * 切换到普通模式
     */
    private fun switchToStandardMode(animationDurationMs: Long) {
        Log.d(TAG, "Switching to STANDARD_MODE")
        
        // 1. 提前通知 Launcher 准备（预热）
        android.os.Handler(context.mainLooper).postDelayed({
            launcherBridge?.show()
        }, PRELOAD_DELAY_MS)
        
        if (animationDurationMs > 0L) {
            // 2. 启动最小化动画
            animateToStandard(animationDurationMs)
            
            // 3. 动画结束后切换可见性
            android.os.Handler(context.mainLooper).postDelayed({
                finalizeStandardMode()
            }, animationDurationMs)
            
        } else {
            // 无动画，直接切换
            finalizeStandardMode()
        }
    }
    
    /**
     * 完成普通模式切换
     */
    private fun finalizeStandardMode() {
        // 1. Launcher 升至前台
        launcherBridge?.show()
        
        // 2. Assistant Surface 降级为悬浮球（不销毁窗口）
        assistantSurface?.minimize()
        
        Log.d(TAG, "STANDARD_MODE finalized")
    }
    
    // ========== 动画处理 ==========
    
    /**
     * 动画：Assistant 恢复（悬浮球 → 全屏）
     */
    private fun animateToAssistant(durationMs: Long) {
        assistantSurface?.windowView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(durationMs)
            ?.withLayer()  // 硬件加速
            ?.start()
        
        launcherBridge?.windowView?.animate()
            ?.alpha(0f)
            ?.setDuration(durationMs)
            ?.withLayer()
            ?.start()
    }
    
    /**
     * 动画：Standard 最小化（全屏 → 悬浮球）
     */
    private fun animateToStandard(durationMs: Long) {
        assistantSurface?.windowView?.animate()
            ?.alpha(0.2f)
            ?.scaleX(0.1f)
            ?.scaleY(0.1f)
            ?.setDuration(durationMs)
            ?.withLayer()  // 硬件加速
            ?.start()
        
        launcherBridge?.windowView?.animate()
            ?.alpha(1f)
            ?.setDuration(durationMs)
            ?.withLayer()
            ?.start()
    }
    
    // ========== 查询接口 ==========
    
    /**
     * 获取当前模式
     */
    fun getCurrentMode(): SystemMode {
        return currentMode
    }
    
    /**
     * 获取最后一次切换耗时
     */
    fun getLastTransitionDuration(): Long {
        return lastTransitionDurationMs
    }
    
    /**
     * 获取 Assistant Surface 窗口
     */
    fun getAssistantSurface(): AssistantSurfaceWindow? {
        return assistantSurface
    }
    
    /**
     * 获取 Launcher Bridge 窗口
     */
    fun getLauncherBridge(): LauncherBridgeWindow? {
        return launcherBridge
    }
    
    /**
     * 检查 Launcher 是否准备就绪
     */
    fun isLauncherReady(): Boolean {
        return launcherBridge?.isLauncherReady() ?: false
    }
    
    // ========== ModeStateListener 实现 ==========
    
    override fun onModeChanged(oldMode: SystemMode, newMode: SystemMode, durationMs: Long) {
        Log.i(TAG, "Mode changed: $oldMode -> $newMode in ${durationMs}ms")
        
        // 性能警告
        if (durationMs > 16L) {
            Log.w(TAG, "⚠️ Transition slower than target (<16ms): ${durationMs}ms")
        }
    }
    
    override fun onTransitionStarted(targetMode: SystemMode, estimatedDurationMs: Long) {
        Log.d(TAG, "Transition started: -> $targetMode (estimated ${estimatedDurationMs}ms)")
    }
    
    override fun onTransitionFailed(targetMode: SystemMode, reason: String) {
        Log.e(TAG, "Transition failed: -> $targetMode, reason: $reason")
    }
    
    // ========== 生命周期管理 ==========
    
    /**
     * 销毁容器（SystemUI 关闭时调用）
     */
    fun destroy() {
        Log.d(TAG, "Destroying DualModeContainer")
        
        stateMachine.removeListener(this)
        assistantSurface?.destroy()
        launcherBridge?.destroy()
        
        assistantSurface = null
        launcherBridge = null
    }
}
