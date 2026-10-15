/*
 * Copyright (C) 2026 AI Native OS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dualmode.DualModeContainer;
import com.android.systemui.dualmode.ModeStateListener;
import com.android.systemui.dualmode.SystemMode;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

/**
 * D1.2 三入口切换 - QS Tile 快捷设置磁贴
 * 
 * <p>功能设计：
 * <ul>
 *   <li>显示当前双模式状态（标准桌面 / AI助理）</li>
 *   <li>单击：切换模式（标准 ⇄ 助理）</li>
 *   <li>长按：打开双模式设置页面（配置悬浮球、侧边手势等）</li>
 *   <li>实时状态同步：监听 DualModeContainer 状态变化</li>
 * </ul>
 * 
 * <p>设计文档：docs/models/D1.2-三入口切换设计文档.md §4 QS Tile 设计
 * 
 * @author AI Native OS Team
 * @version 1.0
 * @since 2026-09-08
 */
public class AssistantModeTile extends QSTileImpl<QSTile.BooleanState> 
        implements ModeStateListener {
    
    private static final String TAG = "AssistantModeTile";
    
    /**
     * QS Tile Intent Action
     * 打开双模式设置页面
     */
    private static final String SETTINGS_ACTION = 
            "com.android.systemui.action.DUAL_MODE_SETTINGS";
    
    // Core Dependencies
    private final DualModeContainer mDualModeContainer;
    private final ModeEntryManager mModeEntryManager;
    private final ActivityStarter mActivityStarter;
    
    // Current mode state
    private SystemMode mCurrentMode = SystemMode.STANDARD_MODE;
    
    /**
     * Dagger 注入构造器
     */
    @Inject
    public AssistantModeTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            DualModeContainer dualModeContainer
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        
        mDualModeContainer = dualModeContainer;
        mActivityStarter = activityStarter;
        
        // 注册模式状态监听器
        mDualModeContainer.addModeStateListener(this);
        
        // 初始化当前模式
        mCurrentMode = mDualModeContainer.getCurrentMode();
        
        Log.d(TAG, "AssistantModeTile initialized, current mode: " + mCurrentMode);
    }
    
    // ==================== QSTileImpl 核心方法 ====================
    
    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = true; // 支持长按
        return state;
    }
    
    @Override
    protected void handleClick(@Nullable View view) {
        // 防误触检查
        if (mDualModeContainer.isTransitioning()) {
            Log.d(TAG, "handleClick: mode is transitioning, ignore click");
            return;
        }
        
        // 切换模式
        SystemMode targetMode = (mCurrentMode == SystemMode.STANDARD_MODE) 
                ? SystemMode.ASSISTANT_MODE 
                : SystemMode.STANDARD_MODE;
        
        Log.d(TAG, "handleClick: switch mode " + mCurrentMode + " -> " + targetMode);
        
        // 通过 DualModeContainer 切换（150ms 动画）
        mDualModeContainer.switchMode(targetMode, 150L);
        
        // 记录 Metrics
        mMetricsLogger.action(MetricsEvent.ACTION_QS_CLICK, getTileSpec());
    }
    
    @Override
    protected void handleLongClick(@Nullable View view) {
        Log.d(TAG, "handleLongClick: open dual mode settings");
        
        // 打开双模式设置页面
        Intent intent = new Intent(SETTINGS_ACTION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        
        // 记录 Metrics
        mMetricsLogger.action(MetricsEvent.ACTION_QS_LONG_PRESS, getTileSpec());
    }
    
    @Override
    public Intent getLongClickIntent() {
        return new Intent(SETTINGS_ACTION);
    }
    
    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        // 从参数或当前状态获取模式
        SystemMode mode = (arg instanceof SystemMode) 
                ? (SystemMode) arg 
                : mCurrentMode;
        
        // 更新磁贴状态
        boolean isAssistantMode = (mode == SystemMode.ASSISTANT_MODE);
        state.value = isAssistantMode;
        
        // 根据模式设置图标和文本
        if (isAssistantMode) {
            state.label = mContext.getString(R.string.quick_settings_assistant_mode_label);
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_assistant_mode_secondary);
            state.icon = ResourceIcon.get(R.drawable.ic_qs_assistant_mode_on);
            state.state = Tile.STATE_ACTIVE;
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_assistant_mode_on);
        } else {
            state.label = mContext.getString(R.string.quick_settings_assistant_mode_label);
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_standard_mode_secondary);
            state.icon = ResourceIcon.get(R.drawable.ic_qs_assistant_mode_off);
            state.state = Tile.STATE_INACTIVE;
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_assistant_mode_off);
        }
        
        // 扩展状态信息
        state.expandedAccessibilityClassName = Switch.class.getName();
    }
    
    @Override
    public int getMetricsCategory() {
        // TODO: 定义专属的 Metrics Category
        return MetricsEvent.QS_INTENT;
    }
    
    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_assistant_mode_label);
    }
    
    @Override
    public boolean isAvailable() {
        // 检查 DualModeContainer 是否可用
        return mDualModeContainer != null && mDualModeContainer.isEnabled();
    }
    
    // ==================== ModeStateListener 接口实现 ====================
    
    @Override
    public void onModeChanged(@NonNull SystemMode oldMode, @NonNull SystemMode newMode) {
        Log.d(TAG, "onModeChanged: " + oldMode + " -> " + newMode);
        
        mCurrentMode = newMode;
        
        // 刷新磁贴状态（在主线程）
        mHandler.post(() -> refreshState(newMode));
    }
    
    @Override
    public void onModeTransitionStarted(@NonNull SystemMode fromMode, 
                                       @NonNull SystemMode toMode, 
                                       long durationMs) {
        // QS Tile 无需处理过渡动画
        Log.d(TAG, "onModeTransitionStarted: " + fromMode + " -> " + toMode 
                + ", duration=" + durationMs + "ms");
    }
    
    @Override
    public void onModeTransitionCompleted(@NonNull SystemMode mode, long actualDurationMs) {
        Log.d(TAG, "onModeTransitionCompleted: " + mode 
                + ", actual duration=" + actualDurationMs + "ms");
    }
    
    @Override
    public void onModeTransitionFailed(@NonNull SystemMode fromMode, 
                                      @NonNull SystemMode toMode, 
                                      @NonNull String reason) {
        Log.e(TAG, "onModeTransitionFailed: " + fromMode + " -> " + toMode 
                + ", reason=" + reason);
        
        // 切换失败，保持旧状态
        mCurrentMode = fromMode;
        mHandler.post(() -> refreshState(fromMode));
    }
    
    // ==================== 生命周期管理 ====================
    
    @Override
    public void destroy() {
        // 注销监听器
        if (mDualModeContainer != null) {
            mDualModeContainer.removeModeStateListener(this);
        }
        
        super.destroy();
        Log.d(TAG, "AssistantModeTile destroyed");
    }
}
