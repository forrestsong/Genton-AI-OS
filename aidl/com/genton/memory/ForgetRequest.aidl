package com.genton.memory;

/**
 * 遗忘请求
 * 
 * "清除关于 X 的所有记忆"，异步任务
 */
parcelable ForgetRequest {
    /**
     * 遗忘主题（"关于 X 的记忆"：实体/话题描述）
     */
    String topic;
    
    /**
     * 是否包含工作记忆（默认只清长期，工作记忆随会话自然过期）
     */
    boolean includeWorking = false;
    
    /**
     * 是否包含系统记忆（需用户显式授权才允许）
     */
    boolean includeSystem = false;
    
    /**
     * 遗忘原因（→ 审计）
     */
    String reason;
    
    /**
     * 确认云端删除（端云同步副本的删除凭证）
     */
    boolean confirmCloudDeletion;
}
