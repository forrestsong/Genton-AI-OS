// FormPayload.aidl
// 表单负载（L0.5 语义直连机制）
package com.ainative.agent;

import com.ainative.agent.FormField;

/**
 * 表单负载
 * 
 * L0.5 内联表单渲染与状态（语义直连机制）
 * 用于 DMI/宏资产的参数收集
 */
parcelable FormPayload {
    /**
     * 表单 ID（唯一标识）
     */
    String formId;
    
    /**
     * 资产 ID（DMI/宏资产 ID，schema 来源）
     */
    String assetId;
    
    /**
     * 工具名称（对应 ToolSchema.name，审计/回放引用）
     */
    String toolName;
    
    /**
     * 执行模式
     * 
     * 0 = semantic_direct（语义直连，免渲染直接提交）
     * 1 = macro_inject（有参宏，需渲染目标应用界面）
     */
    int executionMode;
    
    /**
     * 表单状态
     * 
     * 0 = awaiting_input（等待用户输入）
     * 1 = validating（校验中）
     * 2 = submitting（提交中）
     * 3 = done（已完成）
     * 4 = canceled（已取消）
     */
    int status;
    
    /**
     * 错误提示（校验/策略驳回）
     * 
     * 示例："字段 X 需重新输入"、"参数不符合策略要求"
     */
    String error;
    
    /**
     * 是否可提交
     * 
     * true = schema 全量校验通过且存在必填值
     * false = 缺少必填项或校验失败
     */
    boolean canSubmit;
    
    /**
     * 表单字段列表（schema 派生 + 预填）
     */
    FormField[] fields;
}
