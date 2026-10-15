// FormValueEntry.aidl
// 表单值条目
package com.ainative.agent;

/**
 * 表单值条目
 * 
 * 单个字段的提交值
 * 敏感字段只传 hasValue 标志，实际值不出会话卡进程
 */
parcelable FormValueEntry {
    /**
     * 字段键名
     */
    String key;
    
    /**
     * 字段值
     * 
     * 敏感字段（sensitive=true）时为空串，仅 hasValue=true
     */
    String value;
    
    /**
     * 是否有值（用于敏感字段的标志位）
     */
    boolean hasValue;
}
