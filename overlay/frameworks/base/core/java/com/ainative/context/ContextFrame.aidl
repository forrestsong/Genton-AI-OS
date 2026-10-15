package com.ainative.context;

import com.ainative.context.MediaSummary;
import com.ainative.context.NotificationDigest;
import com.ainative.context.SensorSnapshot;
import com.ainative.context.LocationContext;
import com.ainative.context.AgentTaskSummary;

/**
 * ContextFrame - 上下文帧
 * 
 * 表示系统当前状态的结构化快照。
 */
parcelable ContextFrame {
    long frameId;
    long timestampRealtimeNanos;
    int fieldMask;                 // 本帧包含哪些字段
    int fieldChangedMask;          // 相对上一帧哪些"变了"

    String foregroundPackage;      // FIELD_FOREGROUND_APP
    String foregroundActivity;
    String topUiSummary;           // FIELD_TOP_UI_SUMMARY（已掩码）
    MediaSummary media;            // FIELD_MEDIA
    NotificationDigest notifications; // FIELD_NOTIFICATIONS_DIGEST
    SensorSnapshot sensors;        // FIELD_SENSORS
    LocationContext location;      // FIELD_LOCATION（授权后才非空）
    AgentTaskSummary[] agentTasks; // FIELD_AGENT_TASKS
    boolean inputFocused;          // FIELD_ACTIVE_INPUT
}
