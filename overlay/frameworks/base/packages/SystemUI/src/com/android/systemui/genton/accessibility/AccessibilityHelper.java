// AI Native OS - Accessibility Helper
package com.android.systemui.genton.accessibility;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/**
 * 无障碍辅助类：提供无障碍功能支持
 * 
 * 职责：
 * - 内容描述设置
 * - 无障碍事件发送
 * - 焦点管理
 * - TalkBack兼容
 */
public class AccessibilityHelper {
    private static final String TAG = "AccessibilityHelper";
    
    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;
    
    public AccessibilityHelper(Context context) {
        mContext = context;
        mAccessibilityManager = (AccessibilityManager) 
            context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }
    
    /**
     * 检查是否启用无障碍服务
     */
    public boolean isAccessibilityEnabled() {
        return mAccessibilityManager != null && mAccessibilityManager.isEnabled();
    }
    
    /**
     * 检查是否启用TalkBack
     */
    public boolean isTalkBackEnabled() {
        if (mAccessibilityManager == null) {
            return false;
        }
        return mAccessibilityManager.isTouchExplorationEnabled();
    }
    
    /**
     * 设置视图内容描述
     */
    public void setContentDescription(View view, String description) {
        if (view == null) {
            return;
        }
        view.setContentDescription(description);
    }
    
    /**
     * 发送无障碍事件
     */
    public void sendAccessibilityEvent(View view, int eventType) {
        if (!isAccessibilityEnabled() || view == null) {
            return;
        }
        
        AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
        view.onInitializeAccessibilityEvent(event);
        view.sendAccessibilityEventUnchecked(event);
        
        Log.d(TAG, "Sent accessibility event: " + eventType);
    }
    
    /**
     * 宣布无障碍消息（TalkBack朗读）
     */
    public void announce(View view, String message) {
        if (!isAccessibilityEnabled() || view == null) {
            return;
        }
        
        view.announceForAccessibility(message);
        Log.d(TAG, "Announced: " + message);
    }
    
    /**
     * 请求焦点（用于无障碍导航）
     */
    public void requestAccessibilityFocus(View view) {
        if (!isAccessibilityEnabled() || view == null) {
            return;
        }
        
        view.performAccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        );
        
        Log.d(TAG, "Requested accessibility focus");
    }
    
    /**
     * 设置视图重要性（无障碍）
     */
    public void setImportantForAccessibility(View view, boolean important) {
        if (view == null) {
            return;
        }
        
        view.setImportantForAccessibility(
            important ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : 
                       View.IMPORTANT_FOR_ACCESSIBILITY_NO
        );
    }
    
    /**
     * 配置按钮无障碍属性
     */
    public void configureButtonAccessibility(View button, String label, String hint) {
        if (button == null) {
            return;
        }
        
        button.setContentDescription(label);
        if (hint != null) {
            button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, 
                    android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setHintText(hint);
                }
            });
        }
        
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        button.setFocusable(true);
    }
    
    /**
     * 配置文本视图无障碍属性
     */
    public void configureTextAccessibility(android.widget.TextView textView, boolean isHeading) {
        if (textView == null) {
            return;
        }
        
        textView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        textView.setAccessibilityHeading(isHeading);
    }
    
    /**
     * 配置列表项无障碍属性
     */
    public void configureListItemAccessibility(View itemView, int position, int totalCount) {
        if (itemView == null) {
            return;
        }
        
        String description = "Item " + (position + 1) + " of " + totalCount;
        itemView.setContentDescription(description);
        itemView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
}
