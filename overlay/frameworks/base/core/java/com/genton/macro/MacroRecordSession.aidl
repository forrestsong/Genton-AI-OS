package com.genton.macro;

/**
 * 宏录制会话
 */
parcelable MacroRecordSession {
    // 会话 ID
    String sessionId;
    
    // 会话状态：recording / stopped / generating
    String state;
    
    // 录制开始时间
    long startedAt;
    
    // 录制结束时间（0 = 进行中）
    long endedAt;
    
    // 已录制步骤数
    int stepCount;
    
    // 意图动作（用于生成宏）
    @nullable String intentAction;
    
    // 录制元数据
    @nullable String metadata;
}
