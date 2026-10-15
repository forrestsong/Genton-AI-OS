package com.genton.memory;

/**
 * ForgetRequest - 遗忘请求
 *
 * 服务端实现：AgentMemoryService.forget(ForgetRequest request, ForgetJob job)
 *
 * 语义（与实现一致）：
 * - topic 为主题关键字匹配（非精确 ID），命中内容的记忆将被删除；
 * - includeWorking 决定是否波及工作记忆（KIND_WORKING）；
 * - includeSystem 决定是否波及系统记忆（KIND_SYSTEM），且仅受信助手可要求；
 * - confirmCloudDeletion 表示用户已确认删除云端副本，未确认时任务标记为待同步。
 */
parcelable ForgetRequest {
    /** 遗忘主题（关键字，大小写不敏感，支撑 searchMemoriesByTopic） */
    String topic;

    /** 是否包含工作记忆 */
    boolean includeWorking;

    /** 是否包含系统记忆（仅受信助手可置 true） */
    boolean includeSystem;

    /** 遗忘原因（写入审计） */
    String reason;

    /** 是否已确认删除云端副本 */
    boolean confirmCloudDeletion;
}
