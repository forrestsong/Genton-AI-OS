package com.genton.semantics;

/**
 * 歧义消解请求
 * 
 * 用于将文本提及（如"张总"）解析为具体实体候选
 */
parcelable DisambiguateRequest {
    /**
     * 提及文本（如"张总"）
     */
    String mentionText;
    
    /**
     * 上下文种子（同任务内已确认实体）
     */
    String[] contextEntityIds;
    
    /**
     * 返回候选数量上限
     */
    int topK = 5;
}
