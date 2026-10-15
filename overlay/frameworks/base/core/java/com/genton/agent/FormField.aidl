// FormField.aidl
// 表单字段
package com.genton.agent;

/**
 * 表单字段
 * 
 * 单个表单输入字段的渲染数据与状态
 */
parcelable FormField {
    /**
     * 字段键名（schema property 名）
     */
    String key;
    
    /**
     * 控件类型
     * 
     * 0 = text（文本输入）
     * 1 = number（数字输入）
     * 2 = date（日期选择器）
     * 3 = contact（联系人选择器）
     * 4 = file（文件选择器）
     * 5 = enum（枚举下拉框）
     * 6 = switch（开关）
     * 7 = secret（密码/敏感输入，FLAG_SECURE）
     */
    int widgetType;
    
    /**
     * 字段标签（显示名称）
     */
    String label;
    
    /**
     * 当前值（预填/用户输入）
     * 
     * widgetType=7（secret）时恒为空串（仅 hasValue 标志）
     */
    String value;
    
    /**
     * 是否敏感字段
     * 
     * true = FLAG_SECURE 类：不回显、不预填、不落记忆/审计值
     */
    boolean sensitive;
    
    /**
     * 是否预填（来自用户记忆/S4 实体）
     */
    boolean prefilled;
    
    /**
     * 是否必填
     */
    boolean required;
    
    /**
     * 枚举值列表（widgetType=5 时使用）
     */
    String[] enumValues;
    
    /**
     * schema 原始描述/格式提示
     */
    String schemaHint;
}
