package com.genton.macro;

/**
 * 宏测试用例（黄金样例）
 */
parcelable MacroTestCase {
    // 测试用例 ID
    String testCaseId;
    
    // 关联的宏 ID
    String macroId;
    
    // 测试用例名称
    String name;
    
    // 测试描述
    String description;
    
    // 测试类型：smoke / functional / regression / golden
    String testType;
    
    // 初始状态描述（UI 快照 JSON）
    String initialState;
    
    // 输入参数（JSON）
    @nullable String inputParameters;
    
    // 预期结果（JSON）
    String expectedResult;
    
    // 预期 UI 状态（UI 快照 JSON）
    @nullable String expectedUiState;
    
    // 超时时间（毫秒）
    long timeoutMs;
    
    // 是否为黄金样例
    boolean isGolden;
    
    // 优先级：high / medium / low
    String priority;
    
    // 创建时间戳
    long createdAt;
    
    // 最后运行时间戳
    long lastRunAt;
    
    // 最后运行结果：pass / fail / timeout / skip
    @nullable String lastRunResult;
}
