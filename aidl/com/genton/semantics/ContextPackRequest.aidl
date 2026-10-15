package com.genton.semantics;

/**
 * 上下文打包请求
 * 
 * 任务启动时的上下文预打包请求，一次性获取实体子图 + 相关记忆 + 系统记忆页
 */
parcelable ContextPackRequest {
    /**
     * 任务核心实体（如"客户A + 项目X"）
     */
    String[] seedEntityIds;
    
    /**
     * 子图半径（上限保护）
     */
    int relationDepth = 2;
    
    /**
     * 相关记忆断言上限
     */
    int maxMemories = 20;
    
    /**
     * 是否包含常驻系统记忆页（身份/偏好/设备）
     */
    boolean includeSystemMemoryPage = true;
    
    /**
     * 上下文预算（供打包器裁剪）
     */
    int maxTokensHint;
    
    /**
     * 任务一句话描述（指导子图裁剪）→ 审计
     */
    String taskDescription;
    
    /**
     * 来源意图ID（授权与审计关联）
     */
    String intentId;
}
