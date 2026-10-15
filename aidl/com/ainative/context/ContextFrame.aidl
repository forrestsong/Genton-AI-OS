// ContextFrame.aidl
package com.ainative.context;

/**
 * 上下文帧 - 结构化的当前世界状态快照
 */
parcelable ContextFrame {
    /**
     * 帧序列号（单调递增，用于去重和排序）
     */
    long sequenceId;

    /**
     * 帧生成时间（System.nanoTime()）
     */
    long timestampNanos;

    /**
     * 字段变化掩码（哪些字段在本帧发生了变化）
     */
    int fieldChangedMask;

    /**
     * 字段可用掩码（哪些字段在本帧可用，受授权/隐私控制）
     */
    int fieldAvailableMask;

    // ===== 字段内容 =====

    /**
     * FIELD_FOREGROUND_APP: 前台应用信息
     */
    @nullable ForegroundAppInfo foregroundApp;

    /**
     * FIELD_TOP_UI_SUMMARY: 前台UI语义摘要
     */
    @nullable String topUiSummary;

    /**
     * FIELD_MEDIA: 媒体会话信息
     */
    @nullable MediaInfo media;

    /**
     * FIELD_NOTIFICATIONS_DIGEST: 通知摘要
     */
    @nullable NotificationsDigest notificationsDigest;

    /**
     * FIELD_SENSORS: 传感器状态
     */
    @nullable SensorState sensors;

    /**
     * FIELD_LOCATION: 粗位置标签（需授权）
     */
    @nullable String locationLabel;

    /**
     * FIELD_AGENT_TASKS: 活跃Agent任务列表
     */
    @nullable AgentTaskInfo[] agentTasks;

    /**
     * FIELD_ACTIVE_INPUT: 是否正在输入
     */
    boolean activeInput;
}
