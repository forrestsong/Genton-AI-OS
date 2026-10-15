package com.genton.macro;

/**
 * 宏测试结果
 */
parcelable MacroTestResult {
    // 测试用例 ID
    String testCaseId;
    
    // 宏 ID
    String macroId;
    
    // 测试结果：pass / fail / timeout / error / skip
    String result;
    
    // 执行时间（毫秒）
    long executionTimeMs;
    
    // 失败原因（result=fail 时）
    @nullable String failureReason;
    
    // 失败步骤索引（-1 表示未知）
    int failedStepIndex;
    
    // 预期值
    @nullable String expectedValue;
    
    // 实际值
    @nullable String actualValue;
    
    // 截图引用（失败时的截图）
    @nullable String screenshotRef;
    
    // UI 快照引用（失败时的 UI 状态）
    @nullable String uiSnapshotRef;
    
    // 日志输出
    @nullable String logOutput;
    
    // 测试时间戳
    long testedAt;
    
    // 环境信息（JSON）
    @nullable String environment;
}
