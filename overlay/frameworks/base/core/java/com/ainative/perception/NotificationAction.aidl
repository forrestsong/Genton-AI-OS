package com.ainative.perception;

/**
 * NotificationAction - 通知内联动作
 * 
 * 表示通知中的可执行动作（如回复、标记已读等）。
 */
parcelable NotificationAction {
    String actionId;               // 服务签发的稳定 id
    String label;                  // 展示文案（可能含诱导 → 属于 APP_CONTENT）
    boolean requiresInput;         // 是否需要文本输入（inline reply）
}