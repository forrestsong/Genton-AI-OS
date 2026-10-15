// FormSubmitPayload.aidl
// 表单提交负载
package com.ainative.agent;

import com.ainative.agent.FormValueEntry;

/**
 * 表单提交负载
 * 
 * 用户提交表单时携带的值（敏感字段只传 hasValue 标志）
 */
parcelable FormSubmitPayload {
    /**
     * 表单 ID（对应 FormPayload.formId）
     */
    String formId;
    
    /**
     * 资产 ID
     */
    String assetId;
    
    /**
     * 工具名称
     */
    String toolName;
    
    /**
     * 字段值列表
     */
    FormValueEntry[] values;
}
