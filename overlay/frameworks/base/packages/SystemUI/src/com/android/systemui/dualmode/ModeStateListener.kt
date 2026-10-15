/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode

/**
 * 模式状态监听器
 * 
 * 用途：通知外部组件模式切换事件
 * 
 * 典型消费方：
 * - 悬浮球管理器（根据模式显示/隐藏）
 * - 任务卡片管理器（根据模式调整展示形式）
 * - 性能监控器（记录切换延迟）
 */
interface ModeStateListener {
    /**
     * 模式已切换完成
     * 
     * @param oldMode 切换前的模式
     * @param newMode 切换后的模式
     * @param durationMs 切换耗时（毫秒）
     */
    fun onModeChanged(oldMode: SystemMode, newMode: SystemMode, durationMs: Long)
    
    /**
     * 模式切换开始
     * 
     * @param targetMode 目标模式
     * @param estimatedDurationMs 预估切换时长（毫秒）
     */
    fun onTransitionStarted(targetMode: SystemMode, estimatedDurationMs: Long)
    
    /**
     * 模式切换失败
     * 
     * @param targetMode 目标模式
     * @param reason 失败原因
     */
    fun onTransitionFailed(targetMode: SystemMode, reason: String)
    
    /**
     * 模式切换超时
     * 
     * @param targetMode 目标模式
     * @param timeoutMs 超时时长（毫秒）
     */
    fun onTransitionTimeout(targetMode: SystemMode, timeoutMs: Long) {
        onTransitionFailed(targetMode, "Timeout after ${timeoutMs}ms")
    }
}
