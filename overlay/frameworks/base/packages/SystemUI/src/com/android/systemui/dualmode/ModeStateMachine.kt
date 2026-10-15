/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode

import android.content.Context
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 模式状态机：管理系统模式转换
 * 
 * 职责：
 * - 验证状态转换合法性
 * - 管理状态生命周期
 * - 触发状态变更回调
 * - 处理异常状态（超时、失败）
 * 
 * 状态转换规则：
 * - ASSISTANT_MODE ⇄ STANDARD_MODE（双向切换）
 * - 切换时必须经过 TRANSITION 中间态
 * - TRANSITION 态不允许再次切换（防止并发）
 * 
 * 超时保护：
 * - 切换超时阈值 100ms（远大于目标 16ms）
 * - 超时后自动回滚到原状态
 */
class ModeStateMachine(
    private val context: Context
) {
    companion object {
        private const val TAG = "ModeStateMachine"
        private const val TRANSITION_TIMEOUT_MS = 100L  // 超时阈值
    }
    
    // 当前状态（线程安全）
    @Volatile
    private var currentState: SystemMode = SystemMode.ASSISTANT_MODE
    
    // 切换开始时间（用于计算耗时和超时检测）
    private var transitionStartTime: Long = 0L
    
    // 切换前的状态（用于超时回滚）
    private var previousState: SystemMode = SystemMode.ASSISTANT_MODE
    
    // 监听器列表（线程安全）
    private val listeners = CopyOnWriteArrayList<ModeStateListener>()
    
    // 超时检测任务
    private var timeoutRunnable: Runnable? = null
    
    /**
     * 获取当前状态
     */
    fun getCurrentState(): SystemMode {
        return currentState
    }
    
    /**
     * 请求状态转换
     * 
     * @param targetMode 目标状态
     * @return true: 转换请求已接受，false: 转换被拒绝（不合法或正在切换中）
     */
    @Synchronized
    fun requestTransition(targetMode: SystemMode): Boolean {
        // 1. 验证转换合法性
        if (!canTransitionTo(targetMode)) {
            Log.w(TAG, "Transition rejected: $currentState -> $targetMode (invalid)")
            return false
        }
        
        // 2. 记录状态
        previousState = currentState
        currentState = SystemMode.TRANSITION
        transitionStartTime = System.currentTimeMillis()
        
        // 3. 通知监听器
        val estimatedDurationMs = 16L  // 目标切换时长
        notifyTransitionStarted(targetMode, estimatedDurationMs)
        
        // 4. 启动超时保护
        scheduleTimeoutCheck(targetMode)
        
        Log.d(TAG, "Transition started: $previousState -> $targetMode")
        return true
    }
    
    /**
     * 通知转换完成
     * 
     * @param targetMode 目标状态（应与 requestTransition 的参数一致）
     */
    @Synchronized
    fun onTransitionComplete(targetMode: SystemMode) {
        if (currentState != SystemMode.TRANSITION) {
            Log.w(TAG, "onTransitionComplete called but not in TRANSITION state")
            return
        }
        
        // 1. 取消超时检测
        cancelTimeoutCheck()
        
        // 2. 计算耗时
        val durationMs = System.currentTimeMillis() - transitionStartTime
        
        // 3. 更新状态
        val oldState = previousState
        currentState = targetMode
        
        // 4. 通知监听器
        notifyModeChanged(oldState, targetMode, durationMs)
        
        Log.d(TAG, "Transition completed: $oldState -> $targetMode in ${durationMs}ms")
        
        // 5. 性能警告
        if (durationMs > 16L) {
            Log.w(TAG, "Transition slower than target (<16ms): ${durationMs}ms")
        }
    }
    
    /**
     * 通知转换失败
     * 
     * @param targetMode 目标状态
     * @param reason 失败原因
     */
    @Synchronized
    fun onTransitionFailed(targetMode: SystemMode, reason: String) {
        if (currentState != SystemMode.TRANSITION) {
            Log.w(TAG, "onTransitionFailed called but not in TRANSITION state")
            return
        }
        
        // 1. 取消超时检测
        cancelTimeoutCheck()
        
        // 2. 回滚到之前的状态
        currentState = previousState
        
        // 3. 通知监听器
        notifyTransitionFailed(targetMode, reason)
        
        Log.e(TAG, "Transition failed: $previousState -> $targetMode, reason: $reason")
    }
    
    /**
     * 添加监听器
     */
    fun addListener(listener: ModeStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            Log.d(TAG, "Listener added: ${listener.javaClass.simpleName}")
        }
    }
    
    /**
     * 移除监听器
     */
    fun removeListener(listener: ModeStateListener) {
        if (listeners.remove(listener)) {
            Log.d(TAG, "Listener removed: ${listener.javaClass.simpleName}")
        }
    }
    
    /**
     * 验证是否可以转换到目标状态
     */
    private fun canTransitionTo(targetMode: SystemMode): Boolean {
        return when {
            // 正在切换中，拒绝新的切换请求
            currentState == SystemMode.TRANSITION -> false
            
            // 切换到相同状态，无需切换
            currentState == targetMode -> false
            
            // 不允许切换到 TRANSITION 状态（TRANSITION 只能是中间态）
            targetMode == SystemMode.TRANSITION -> false
            
            // ASSISTANT_MODE ⇄ STANDARD_MODE 合法
            else -> true
        }
    }
    
    /**
     * 安排超时检测
     */
    private fun scheduleTimeoutCheck(targetMode: SystemMode) {
        timeoutRunnable = Runnable {
            synchronized(this) {
                if (currentState == SystemMode.TRANSITION) {
                    val elapsedMs = System.currentTimeMillis() - transitionStartTime
                    Log.e(TAG, "Transition timeout: ${elapsedMs}ms")
                    
                    // 回滚状态
                    currentState = previousState
                    
                    // 通知超时
                    notifyTransitionTimeout(targetMode, elapsedMs)
                }
            }
        }
        
        // 延迟 TRANSITION_TIMEOUT_MS 执行
        android.os.Handler(context.mainLooper).postDelayed(
            timeoutRunnable!!,
            TRANSITION_TIMEOUT_MS
        )
    }
    
    /**
     * 取消超时检测
     */
    private fun cancelTimeoutCheck() {
        timeoutRunnable?.let {
            android.os.Handler(context.mainLooper).removeCallbacks(it)
            timeoutRunnable = null
        }
    }
    
    // ========== 监听器通知方法 ==========
    
    private fun notifyModeChanged(oldMode: SystemMode, newMode: SystemMode, durationMs: Long) {
        listeners.forEach { listener ->
            try {
                listener.onModeChanged(oldMode, newMode, durationMs)
            } catch (e: Exception) {
                Log.e(TAG, "Listener threw exception in onModeChanged", e)
            }
        }
    }
    
    private fun notifyTransitionStarted(targetMode: SystemMode, estimatedDurationMs: Long) {
        listeners.forEach { listener ->
            try {
                listener.onTransitionStarted(targetMode, estimatedDurationMs)
            } catch (e: Exception) {
                Log.e(TAG, "Listener threw exception in onTransitionStarted", e)
            }
        }
    }
    
    private fun notifyTransitionFailed(targetMode: SystemMode, reason: String) {
        listeners.forEach { listener ->
            try {
                listener.onTransitionFailed(targetMode, reason)
            } catch (e: Exception) {
                Log.e(TAG, "Listener threw exception in onTransitionFailed", e)
            }
        }
    }
    
    private fun notifyTransitionTimeout(targetMode: SystemMode, timeoutMs: Long) {
        listeners.forEach { listener ->
            try {
                listener.onTransitionTimeout(targetMode, timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Listener threw exception in onTransitionTimeout", e)
            }
        }
    }
}
