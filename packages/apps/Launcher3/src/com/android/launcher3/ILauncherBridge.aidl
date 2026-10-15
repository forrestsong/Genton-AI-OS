/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.launcher3;

/**
 * Launcher3 桥接接口：用于 SystemUI 双模式容器与 Launcher 通信
 * 
 * 用途：
 * - 通知 Launcher 即将进入/离开前台
 * - 查询 Launcher 准备状态
 * - 协调模式切换（避免冷启动延迟）
 * 
 * 实现方：Launcher3
 * 消费方：SystemUI (DualModeContainer)
 */
interface ILauncherBridge {
    /**
     * 通知 Launcher 即将进入前台
     * 
     * 调用时机：SystemUI 切换到 STANDARD_MODE 前 ~50ms
     * Launcher 应：
     * - 预热必要的视图组件
     * - 准备应用列表数据
     * - 恢复壁纸/图标布局
     */
    oneway void onAboutToShow();
    
    /**
     * 通知 Launcher 即将进入后台
     * 
     * 调用时机：SystemUI 切换到 ASSISTANT_MODE 前 ~50ms
     * Launcher 应：
     * - 保存当前状态（滚动位置、文件夹展开状态等）
     * - 释放非必要资源（减少内存占用）
     * - 但不应完全销毁（保持进程存活）
     */
    oneway void onAboutToHide();
    
    /**
     * 查询 Launcher 是否准备就绪
     * 
     * @return true: Launcher 已初始化完成，可立即切换
     *         false: Launcher 未就绪，切换可能有延迟
     * 
     * 用途：SystemUI 在切换前检查，如果未就绪可显示加载指示器
     */
    boolean isReady();
    
    /**
     * 强制保活 Launcher 进程
     * 
     * 调用时机：SystemUI 启动时，确保 Launcher 常驻内存
     * Launcher 应：
     * - 标记自身为 PERSISTENT（如果有权限）
     * - 防止被系统低内存杀手清理
     */
    oneway void ensureAlive();
}
