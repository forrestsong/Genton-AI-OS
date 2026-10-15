package com.ainative.perception;

import com.ainative.perception.NotificationAction;

/**
 * NotificationEnvelope - 通知封装
 * 
 * 包含通知的完整元数据和内容（已按 maskPii 处理）。
 */
parcelable NotificationEnvelope {
    String key;
    String packageName;
    String appLabel;
    String category;               // 对齐 Notification.CATEGORY_*
    long postTime;
    int importance;
    String title;                  // 已按 maskPii 处理
    String text;                   // 已按 maskPii 处理
    boolean ongoing;               // 常驻（音乐/导航等）
    boolean autoCancel;
    boolean hasContentIntent;      // 是否可"打开"
    NotificationAction[] actions;
}