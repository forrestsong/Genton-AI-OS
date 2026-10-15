/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.dualmode

/**
 * 系统模式枚举
 * 
 * 定义 SystemUI 的两种运行模式及切换中状态
 */
enum class SystemMode {
    /**
     * AI 全能助理模式（默认）
     * 
     * 特征：
     * - Assistant Surface 全屏显示
     * - 会话式认知壳界面
     * - Agent 任务全面可见
     * - Launcher3 在后台保活但不可见
     */
    ASSISTANT_MODE,
    
    /**
     * 普通应用模式
     * 
     * 特征：
     * - Launcher3 全屏显示
     * - 传统应用网格/抽屉
     * - Assistant Surface 降级为悬浮球
     * - Agent 任务在悬浮球中摘要显示
     */
    STANDARD_MODE,
    
    /**
     * 切换中状态（过渡态）
     * 
     * 特征：
     * - 切换动画执行中
     * - 持续时间 <16ms（目标）
     * - 两个表面同时可见（Z-order 调整中）
     */
    TRANSITION;
    
    /**
     * 获取下一个可切换的模式
     */
    fun getToggleTarget(): SystemMode {
        return when (this) {
            ASSISTANT_MODE -> STANDARD_MODE
            STANDARD_MODE -> ASSISTANT_MODE
            TRANSITION -> this  // 切换中不允许再次切换
        }
    }
    
    /**
     * 判断是否为稳定状态（非切换中）
     */
    fun isStable(): Boolean {
        return this != TRANSITION
    }
}
