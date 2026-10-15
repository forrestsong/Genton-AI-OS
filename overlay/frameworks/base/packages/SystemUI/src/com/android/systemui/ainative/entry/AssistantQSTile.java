// AI Native OS - Quick Settings Tile
package com.android.systemui.ainative.entry;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * 快捷设置磁贴（QS Tile）：从快捷设置面板唤起助理
 * 
 * 职责：
 * - 在快捷设置面板显示磁贴
 * - 点击唤起助理
 * - 显示助理状态（活跃/空闲）
 */
public class AssistantQSTile extends TileService {
    private static final String TAG = "AssistantQSTile";
    
    private boolean mIsAssistantActive = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Assistant QS Tile created");
    }
    
    @Override
    public void onStartListening() {
        super.onStartListening();
        
        // 更新磁贴状态
        updateTile();
        
        Log.d(TAG, "Started listening");
    }
    
    @Override
    public void onStopListening() {
        super.onStopListening();
        Log.d(TAG, "Stopped listening");
    }
    
    @Override
    public void onClick() {
        super.onClick();
        
        Log.i(TAG, "QS Tile clicked");
        
        // 唤起助理
        Intent intent = new Intent("com.ainative.ACTION_SHOW_ASSISTANT");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(intent);
        
        // 更新状态
        mIsAssistantActive = true;
        updateTile();
    }
    
    /**
     * 更新磁贴状态
     */
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        
        // 设置标题
        tile.setLabel("AI Assistant");
        
        // 设置状态
        if (mIsAssistantActive) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setSubtitle("Active");
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle("Tap to open");
        }
        
        // 设置图标（使用系统图标，后续可替换）
        Icon icon = Icon.createWithResource(this, android.R.drawable.ic_dialog_info);
        tile.setIcon(icon);
        
        // 更新磁贴
        tile.updateTile();
        
        Log.d(TAG, "Tile updated: " + (mIsAssistantActive ? "ACTIVE" : "INACTIVE"));
    }
    
    /**
     * 更新助理状态（供外部调用）
     */
    public void setAssistantActive(boolean active) {
        mIsAssistantActive = active;
        updateTile();
    }
}
