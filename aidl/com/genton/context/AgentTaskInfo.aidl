// AgentTaskInfo.aidl
package com.genton.context;

/**
 * Agent任务信息
 */
parcelable AgentTaskInfo {
    /**
     * Agent标识符
     */
    String agentId;

    /**
     * 任务状态（idle/thinking/executing/waiting_confirmation）
     */
    String taskState;

    /**
     * 目标脱敏摘要
     */
    @nullable String goalSummary;

    /**
     * 是否为前台任务
     */
    boolean isForeground;

    /**
     * 任务开始时间（System.currentTimeMillis()）
     */
    long startedAtMs;
}
