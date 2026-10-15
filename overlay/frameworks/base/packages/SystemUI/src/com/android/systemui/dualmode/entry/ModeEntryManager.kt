/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode.entry

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.android.systemui.dualmode.DualModeContainer
import com.android.systemui.dualmode.ModeStateListener
import com.android.systemui.dualmode.SystemMode
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D1.2 三入口切换 - 统一入口管理器
 * 
 * 职责：
 * 1. 统一管理三种切换入口（悬浮球、侧边手势、QS Tile）
 * 2. 协调触发事件，防止并发切换冲突
 * 3. 记录切换来源，用于统计和优化
 * 4. 提供全局启用/禁用接口
 * 5. 防误触策略全局协调
 * 
 * 设计文档：docs/models/D1.2-三入口切换设计文档.md §7 实现清单
 * 
 * @author AI Native OS Team
 * @version 1.0
 * @since 2026-09-08
 */
@Singleton
class ModeEntryManager @Inject constructor(
    private val context: Context,
    private val dualModeContainer: DualModeContainer
) : ModeStateListener {
    
    companion object {
        private const val TAG = "ModeEntryManager"
        
        // 防抖动时间窗口（ms）
        private const val DEBOUNCE_WINDOW_MS = 300L
        
        // 入口类型
        const val ENTRY_FLOATING_BUBBLE = "floating_bubble"
        const val ENTRY_EDGE_GESTURE = "edge_gesture"
        const val ENTRY_QS_TILE = "qs_tile"
        const val ENTRY_PROGRAMMATIC = "programmatic"
    }
    
    // ==================== 入口组件 ====================
    
    private var floatingBubble: FloatingBubble? = null
    private var edgeGestureHandler: EdgeGestureHandler? = null
    // QS Tile 由 SystemUI QS 系统管理，无需持有引用
    
    // ==================== 状态管理 ====================
    
    // 是否已初始化
    private var initialized = false
    
    // 全局启用/禁用标志
    private var entryEnabled = true
    
    // 各入口独立启用标志
    private val entryFlags = mutableMapOf(
        ENTRY_FLOATING_BUBBLE to true,
        ENTRY_EDGE_GESTURE to true,
        ENTRY_QS_TILE to true
    )
    
    // 最后一次切换请求
    private var lastSwitchTimestamp = 0L
    private var lastSwitchSource = ""
    
    // 切换来源统计
    private val switchSourceStats = mutableMapOf<String, Int>()
    
    // 外部监听器列表
    private val entryEventListeners = CopyOnWriteArrayList<EntryEventListener>()
    
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // ==================== 初始化与生命周期 ====================
    
    /**
     * 初始化三种入口组件
     * 
     * 调用时机：SystemUI DualModeIntegration.start()
     */
    fun initialize() {
        if (initialized) {
            Log.w(TAG, "initialize: already initialized")
            return
        }
        
        Log.d(TAG, "initialize: setting up entry components")
        
        // 初始化悬浮球
        if (entryFlags[ENTRY_FLOATING_BUBBLE] == true) {
            floatingBubble = FloatingBubble(
                context = context,
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) 
                    as android.view.WindowManager,
                dualModeContainer = dualModeContainer
            ).apply {
                setOnClickListener { 
                    handleEntryTrigger(ENTRY_FLOATING_BUBBLE, SystemMode.ASSISTANT_MODE, 150L)
                }
                show()
            }
            Log.d(TAG, "initialize: FloatingBubble created and shown")
        }
        
        // 初始化侧边手势
        if (entryFlags[ENTRY_EDGE_GESTURE] == true) {
            edgeGestureHandler = EdgeGestureHandler(
                context = context,
                dualModeContainer = dualModeContainer
            ).apply {
                setOnGestureTriggeredListener { targetMode ->
                    handleEntryTrigger(ENTRY_EDGE_GESTURE, targetMode, 300L)
                }
                enable()
            }
            Log.d(TAG, "initialize: EdgeGestureHandler created and enabled")
        }
        
        // QS Tile 由 SystemUI QS 系统自动管理
        Log.d(TAG, "initialize: QS Tile managed by SystemUI QS system")
        
        // 注册模式状态监听器
        dualModeContainer.addModeStateListener(this)
        
        initialized = true
        Log.i(TAG, "initialize: all entry components initialized")
    }
    
    /**
     * 销毁入口组件
     */
    fun destroy() {
        if (!initialized) {
            return
        }
        
        Log.d(TAG, "destroy: cleaning up entry components")
        
        // 销毁悬浮球
        floatingBubble?.hide()
        floatingBubble = null
        
        // 销毁侧边手势
        edgeGestureHandler?.disable()
        edgeGestureHandler = null
        
        // 注销监听器
        dualModeContainer.removeModeStateListener(this)
        entryEventListeners.clear()
        
        initialized = false
        Log.i(TAG, "destroy: all entry components destroyed")
    }
    
    // ==================== 入口触发协调 ====================
    
    /**
     * 处理入口触发事件（统一入口）
     * 
     * @param source 触发来源
     * @param targetMode 目标模式
     * @param durationMs 动画时长
     * @return 是否接受触发请求
     */
    fun handleEntryTrigger(
        source: String, 
        targetMode: SystemMode, 
        durationMs: Long
    ): Boolean {
        // 全局禁用检查
        if (!entryEnabled) {
            Log.d(TAG, "handleEntryTrigger: entry globally disabled")
            return false
        }
        
        // 单个入口禁用检查
        if (entryFlags[source] == false) {
            Log.d(TAG, "handleEntryTrigger: entry [$source] is disabled")
            return false
        }
        
        // 防抖动检查
        val now = SystemClock.elapsedRealtime()
        if (now - lastSwitchTimestamp < DEBOUNCE_WINDOW_MS) {
            Log.d(TAG, "handleEntryTrigger: debounce window active, " +
                    "last switch from [$lastSwitchSource] at ${now - lastSwitchTimestamp}ms ago")
            return false
        }
        
        // 过渡中检查
        if (dualModeContainer.isTransitioning()) {
            Log.d(TAG, "handleEntryTrigger: mode is transitioning, reject request")
            return false
        }
        
        // 相同模式检查
        val currentMode = dualModeContainer.getCurrentMode()
        if (currentMode == targetMode) {
            Log.d(TAG, "handleEntryTrigger: already in target mode [$targetMode]")
            return false
        }
        
        // 记录触发事件
        lastSwitchTimestamp = now
        lastSwitchSource = source
        switchSourceStats[source] = (switchSourceStats[source] ?: 0) + 1
        
        Log.i(TAG, "handleEntryTrigger: [$source] trigger mode switch " +
                "$currentMode -> $targetMode (duration=${durationMs}ms)")
        
        // 通知监听器
        notifyEntryTriggered(source, currentMode, targetMode)
        
        // 执行切换
        dualModeContainer.switchMode(targetMode, durationMs)
        
        return true
    }
    
    /**
     * QS Tile 专用触发接口
     * 
     * QS Tile 内部调用此方法，而非直接调用 DualModeContainer
     */
    fun handleQSTileTrigger(targetMode: SystemMode): Boolean {
        return handleEntryTrigger(ENTRY_QS_TILE, targetMode, 150L)
    }
    
    // ==================== 入口启用/禁用控制 ====================
    
    /**
     * 全局启用/禁用所有入口
     */
    fun setEntryEnabled(enabled: Boolean) {
        if (entryEnabled == enabled) {
            return
        }
        
        Log.i(TAG, "setEntryEnabled: $entryEnabled -> $enabled")
        entryEnabled = enabled
        
        // 更新组件状态
        if (enabled) {
            floatingBubble?.show()
            edgeGestureHandler?.enable()
        } else {
            floatingBubble?.hide()
            edgeGestureHandler?.disable()
        }
    }
    
    /**
     * 单独启用/禁用某个入口
     */
    fun setEntryEnabled(source: String, enabled: Boolean) {
        val oldValue = entryFlags[source]
        if (oldValue == enabled) {
            return
        }
        
        Log.i(TAG, "setEntryEnabled: [$source] $oldValue -> $enabled")
        entryFlags[source] = enabled
        
        // 更新对应组件
        when (source) {
            ENTRY_FLOATING_BUBBLE -> {
                if (enabled) floatingBubble?.show() else floatingBubble?.hide()
            }
            ENTRY_EDGE_GESTURE -> {
                if (enabled) edgeGestureHandler?.enable() else edgeGestureHandler?.disable()
            }
            ENTRY_QS_TILE -> {
                // QS Tile 由系统管理，暂不支持运行时禁用
                Log.w(TAG, "setEntryEnabled: QS Tile cannot be disabled at runtime")
            }
        }
    }
    
    /**
     * 检查入口是否启用
     */
    fun isEntryEnabled(source: String): Boolean {
        return entryEnabled && (entryFlags[source] == true)
    }
    
    // ==================== ModeStateListener 接口实现 ====================
    
    override fun onModeChanged(oldMode: SystemMode, newMode: SystemMode) {
        Log.d(TAG, "onModeChanged: $oldMode -> $newMode")
        
        // 更新悬浮球状态（仅在标准模式显示）
        mainHandler.post {
            if (newMode == SystemMode.STANDARD_MODE) {
                floatingBubble?.show()
            } else {
                floatingBubble?.hide()
            }
        }
    }
    
    override fun onModeTransitionStarted(
        fromMode: SystemMode, 
        toMode: SystemMode, 
        durationMs: Long
    ) {
        // 入口管理器无需处理过渡动画
    }
    
    override fun onModeTransitionCompleted(mode: SystemMode, actualDurationMs: Long) {
        Log.d(TAG, "onModeTransitionCompleted: mode=$mode, duration=${actualDurationMs}ms, " +
                "source=$lastSwitchSource")
    }
    
    override fun onModeTransitionFailed(
        fromMode: SystemMode, 
        toMode: SystemMode, 
        reason: String
    ) {
        Log.e(TAG, "onModeTransitionFailed: $fromMode -> $toMode, reason=$reason, " +
                "source=$lastSwitchSource")
    }
    
    // ==================== 事件监听器 ====================
    
    /**
     * 入口事件监听器接口
     */
    interface EntryEventListener {
        /**
         * 入口被触发
         */
        fun onEntryTriggered(source: String, fromMode: SystemMode, toMode: SystemMode)
    }
    
    fun addEntryEventListener(listener: EntryEventListener) {
        entryEventListeners.add(listener)
    }
    
    fun removeEntryEventListener(listener: EntryEventListener) {
        entryEventListeners.remove(listener)
    }
    
    private fun notifyEntryTriggered(source: String, fromMode: SystemMode, toMode: SystemMode) {
        for (listener in entryEventListeners) {
            try {
                listener.onEntryTriggered(source, fromMode, toMode)
            } catch (e: Exception) {
                Log.e(TAG, "notifyEntryTriggered: listener error", e)
            }
        }
    }
    
    // ==================== 统计与调试 ====================
    
    /**
     * 获取切换来源统计
     */
    fun getSwitchSourceStats(): Map<String, Int> {
        return switchSourceStats.toMap()
    }
    
    /**
     * 重置统计数据
     */
    fun resetStats() {
        switchSourceStats.clear()
        Log.d(TAG, "resetStats: statistics cleared")
    }
    
    /**
     * 输出调试信息
     */
    fun dumpDebugInfo(): String {
        return buildString {
            appendLine("ModeEntryManager Debug Info:")
            appendLine("  Initialized: $initialized")
            appendLine("  Entry Enabled: $entryEnabled")
            appendLine("  Entry Flags:")
            entryFlags.forEach { (source, enabled) ->
                appendLine("    $source: $enabled")
            }
            appendLine("  Last Switch: $lastSwitchSource at ${lastSwitchTimestamp}ms")
            appendLine("  Switch Source Stats:")
            switchSourceStats.forEach { (source, count) ->
                appendLine("    $source: $count times")
            }
            appendLine("  Components:")
            appendLine("    FloatingBubble: ${floatingBubble != null}")
            appendLine("    EdgeGestureHandler: ${edgeGestureHandler != null}")
        }
    }
}
