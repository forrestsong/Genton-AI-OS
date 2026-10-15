package com.ainative.memory;

/**
 * 遗忘任务
 * 
 * 异步遗忘任务的状态和结果
 */
parcelable ForgetJob {
    /**
     * 任务唯一标识
     */
    String jobId;
    
    /**
     * 任务状态：0=queued 1=running 2=done 3=failed
     */
    int status;
    
    /**
     * 已删除本地记忆条数
     */
    long deletedLocalCount;
    
    /**
     * 已删除向量/图谱节点数
     */
    long deletedVectorCount;
    
    /**
     * 云端同步是否待处理
     */
    boolean cloudSyncPending;
    
    /**
     * 错误信息（失败时）
     */
    String error;
}
