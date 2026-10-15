// WhitelistEntry.aidl
package com.ainative.call;

/**
 * 白名单条目
 */
parcelable WhitelistEntry {
    /**
     * E.164 格式号码或号段（如 +8613800138000 或 +86138*）
     */
    String numberPattern;

    /**
     * 来源类型：
     * "contact" = 通讯录联系人
     * "user_added" = 用户手动添加
     * "emergency" = 紧急号码（系统保底）
     * "system" = 系统号码（运营商服务号等）
     */
    String sourceType;

    /**
     * 联系人 ID（如果来源是通讯录）
     */
    String contactId;

    /**
     * 显示名称
     */
    String displayName;

    /**
     * 添加时间（System.currentTimeMillis()）
     */
    long addedAtMs;

    /**
     * 是否系统保护（紧急号码/系统号码不可删除）
     */
    boolean systemProtected;
}
