// MacroExecutionResult.aidl
package com.genton.macro;

/**
 * 宏执行结果
 * 
 * 重放校验器的输出，包含执行状态、失败原因、审计信息
 * 
 * 对应任务：F1.1 重放校验器
 */
parcelable MacroExecutionResult {
    // 执行 ID（唯一标识）
    String executionId;
    
    // 脚本 ID
    String scriptId;
    
    // 执行会话 ID（关联 Agent Turn）
    String sessionId;
    
    // 执行状态
    // 0 = success（成功）
    // 1 = failed（失败）
    // 2 = timeout（超时）
    // 3 = partial_success（部分成功）
    int status;
    
    // 执行的步骤数
    int executedSteps;
    
    // 总步骤数
    int totalSteps;
    
    // 失败的步骤 ID（status != success 时）
    @nullable int[] failedStepIds;
    
    // 失败原因
    @nullable String failureReason;
    
    // 错误码
    int errorCode = 0;
    
    // 执行延迟（毫秒）
    long latencyMs;
    
    // 执行开始时间戳
    long startTime;
    
    // 执行结束时间戳
    long endTime;
    
    // 审计引用（用于 ActionCardData）
    String auditRef;
    
    // 截图证据路径（可选）
    @nullable String[] screenshotPaths;
    
    // 设备信息
    String deviceModel;
    String deviceManufacturer;
    int sdkVersion;
    
    // 是否需要停用（失败自动停用）
    boolean shouldDisable = false;
    
    // 扩展信息
    @nullable String extras;
}
