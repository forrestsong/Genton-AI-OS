// CallDescriptor.aidl
package com.ainative.call;

/**
 * 来电描述符
 */
parcelable CallDescriptor {
    /**
     * E.164 格式号码（如 +8613800138000）
     */
    String numberE164;

    /**
     * 来电显示名称（可能为空）
     */
    String callerName;

    /**
     * 来电时间（System.currentTimeMillis()）
     */
    long timestampMs;

    /**
     * 是否紧急号码（110/119/120 等）
     */
    boolean isEmergency;

    /**
     * 是否系统号码（运营商服务号等）
     */
    boolean isSystemNumber;

    /**
     * 号码哈希（隐私保护，用于会话关联）
     */
    String numberHash;

    /**
     * 重复来电窗口（ms）：该号码在此窗口内曾来电
     * -1 表示首次来电
     */
    long repeatedInWindowMs;

    /**
     * 最近有主动拨出到该号码（天数）
     * -1 表示无记录
     */
    int hasRecentOutgoingWithinDays;
}
