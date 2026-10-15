/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.genton.accessibility

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D8.1 无障碍集成协调器
 * 
 * 职责：
 * 1. 监听系统无障碍设置变化（字体缩放、减少动画等）
 * 2. 协调 AnimationAccessibilityManager 与业务组件
 * 3. 提供统一的无障碍能力查询接口
 * 4. 管理内容描述和焦点顺序
 * 
 * 设计文档：docs/models/D8.1-动效与可访问性设计文档.md
 * 
 * @author AI Native OS Team
 * @version 1.0
 * @since 2026-09-08
 */
@Singleton
class AccessibilityIntegration @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "AccessibilityIntegration"
    }
    
    // 动画与无障碍管理器
    val animationManager: AnimationAccessibilityManager = 
        AnimationAccessibilityManager(context)
    
    // Android 无障碍服务管理器
    private val accessibilityManager: AccessibilityManager = 
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 设置变化监听器
    private val settingsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            Log.d(TAG, "Accessibility settings changed: $uri")
            handleSettingsChange(uri)
        }
    }
    
    // 外部监听器列表
    private val listeners = mutableListOf<AccessibilityChangeListener>()
    
    /**
     * 初始化（SystemUI 启动时调用）
     */
    fun initialize() {
        Log.d(TAG, "Initializing AccessibilityIntegration")
        
        // 注册设置变化监听
        registerSettingsObserver()
        
        // 初始加载设置
        animationManager.refresh()
        
        Log.i(TAG, "AccessibilityIntegration initialized")
    }
    
    /**
     * 销毁（SystemUI 关闭时调用）
     */
    fun destroy() {
        Log.d(TAG, "Destroying AccessibilityIntegration")
        
        // 注销设置监听
        context.contentResolver.unregisterContentObserver(settingsObserver)
        
        listeners.clear()
        
        Log.i(TAG, "AccessibilityIntegration destroyed")
    }
    
    // ==================== 设置监听 ====================
    
    private fun registerSettingsObserver() {
        // 监听字体缩放设置
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.FONT_SCALE),
            false,
            settingsObserver
        )
        
        // 监听动画缩放设置
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            settingsObserver
        )
        
        Log.d(TAG, "Settings observers registered")
    }
    
    private fun handleSettingsChange(uri: Uri?) {
        // 刷新动画管理器
        animationManager.refresh()
        
        // 通知监听器
        notifyAccessibilityChanged()
    }
    
    // ==================== 无障碍能力查询 ====================
    
    /**
     * 检查是否启用了任何无障碍服务（TalkBack 等）
     */
    fun isAccessibilityEnabled(): Boolean {
        return accessibilityManager.isEnabled
    }
    
    /**
     * 检查是否启用了触摸探索模式（TalkBack 特有）
     */
    fun isTouchExplorationEnabled(): Boolean {
        return accessibilityManager.isTouchExplorationEnabled
    }
    
    /**
     * 检查是否启用了减少动画
     */
    fun isReduceMotionEnabled(): Boolean {
        return animationManager.isReduceMotionEnabled()
    }
    
    /**
     * 获取文本缩放因子
     */
    fun getTextScaleFactor(): Float {
        return animationManager.getTextScaleFactor()
    }
    
    /**
     * 获取推荐的动画时长（考虑无障碍设置）
     */
    fun getRecommendedAnimationDuration(baseDuration: Long): Long {
        return animationManager.getActualDuration(baseDuration)
    }
    
    // ==================== 内容描述辅助方法 ====================
    
    /**
     * 为双模式切换入口设置内容描述
     */
    fun setupModeEntryDescription(view: View, entryType: String, currentMode: String) {
        val description = when (entryType) {
            "floating_bubble" -> {
                if (currentMode == "STANDARD_MODE") {
                    "AI 助理悬浮球，双击切换到助理模式"
                } else {
                    "AI 助理悬浮球，当前在助理模式"
                }
            }
            "edge_gesture" -> "侧边栏手势触发区，从屏幕边缘滑入以唤出助理"
            "qs_tile" -> {
                if (currentMode == "STANDARD_MODE") {
                    "AI 助理模式磁贴，当前处于标准模式，点击切换到助理模式"
                } else {
                    "AI 助理模式磁贴，当前处于助理模式，点击返回标准模式"
                }
            }
            else -> "AI 助理切换入口"
        }
        
        view.contentDescription = description
    }
    
    /**
     * 为确认面板按钮设置内容描述
     */
    fun setupConfirmationButtonDescription(
        approveButton: View,
        denyButton: View,
        neverButton: View,
        actionName: String
    ) {
        approveButton.contentDescription = "批准$actionName"
        denyButton.contentDescription = "拒绝$actionName"
        neverButton.contentDescription = "永不批准此类操作"
    }
    
    /**
     * 为胶囊设置内容描述
     */
    fun setupCapsuleDescription(view: View, capsuleText: String, priority: String) {
        val priorityDesc = if (priority == "HIGH") "高优先级" else "普通"
        view.contentDescription = "$priorityDesc 提醒：$capsuleText，双击查看详情"
    }
    
    // ==================== 文本缩放辅助方法 ====================
    
    /**
     * 应用文本缩放到单个 TextView
     */
    fun applyTextScale(textView: TextView, baseTextSizeSp: Float) {
        animationManager.applyTextScale(textView, baseTextSizeSp)
    }
    
    /**
     * 批量应用文本缩放
     */
    fun applyTextScaleToViews(textViews: List<Pair<TextView, Float>>) {
        for ((textView, baseSize) in textViews) {
            applyTextScale(textView, baseSize)
        }
    }
    
    // ==================== 焦点管理 ====================
    
    /**
     * 设置视图焦点顺序（用于 Switch Access 导航）
     */
    fun setupFocusOrder(
        currentView: View,
        nextDown: View? = null,
        nextUp: View? = null,
        nextLeft: View? = null,
        nextRight: View? = null
    ) {
        currentView.isFocusable = true
        currentView.isFocusableInTouchMode = false
        
        nextDown?.let { currentView.nextFocusDownId = it.id }
        nextUp?.let { currentView.nextFocusUpId = it.id }
        nextLeft?.let { currentView.nextFocusLeftId = it.id }
        nextRight?.let { currentView.nextFocusRightId = it.id }
    }
    
    /**
     * 请求焦点到指定视图（TalkBack 会朗读内容描述）
     */
    fun requestFocusWithAnnouncement(view: View, delayMs: Long = 100L) {
        mainHandler.postDelayed({
            if (view.requestFocus()) {
                // TalkBack 会自动朗读 contentDescription
                Log.d(TAG, "Focus moved to: ${view.contentDescription}")
            }
        }, delayMs)
    }
    
    // ==================== 监听器管理 ====================
    
    /**
     * 无障碍设置变化监听器
     */
    interface AccessibilityChangeListener {
        fun onTextScaleChanged(newScale: Float)
        fun onReduceMotionChanged(enabled: Boolean)
    }
    
    fun addListener(listener: AccessibilityChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    fun removeListener(listener: AccessibilityChangeListener) {
        listeners.remove(listener)
    }
    
    private fun notifyAccessibilityChanged() {
        val textScale = getTextScaleFactor()
        val reduceMotion = isReduceMotionEnabled()
        
        for (listener in listeners) {
            try {
                listener.onTextScaleChanged(textScale)
                listener.onReduceMotionChanged(reduceMotion)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
    
    // ==================== 调试信息 ====================
    
    fun dumpDebugInfo(): String {
        return buildString {
            appendLine("AccessibilityIntegration Debug Info:")
            appendLine("  Accessibility Enabled: ${isAccessibilityEnabled()}")
            appendLine("  Touch Exploration: ${isTouchExplorationEnabled()}")
            appendLine("  Reduce Motion: ${isReduceMotionEnabled()}")
            appendLine("  Text Scale Factor: ${getTextScaleFactor()}")
            appendLine("  Registered Listeners: ${listeners.size}")
        }
    }
}
