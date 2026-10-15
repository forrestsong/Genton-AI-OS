// SessionOpenRequest.aidl
// 会话打开请求
package com.genton.agent;

/**
 * 会话打开请求
 * 
 * SystemUI 调用 IAgentSessionService.openSession() 时传递
 * 支持新建会话和恢复冷会话（从 checkpoint）
 */
parcelable SessionOpenRequest {
    /**
     * 会话标识（由 UI 生成，跨模式切换保持）
     * 
     * 格式：UUID v4
     * 用途：关联多轮对话、checkpoint、审计链
     */
    String sessionId;
    
    /**
     * 主对话 Agent ID
     * 
     * 空值 = 默认助手
     * 未来支持切换到特定 Agent（如文档助手、健康助手）
     */
    String agentId;
    
    /**
     * 入口模式
     * 
     * 0 = user_init（用户主动打开）
     * 1 = voice_wake（语音唤醒）
     * 2 = event_proactive（事件触发的主动服务）
     * 3 = resume（恢复冷会话）
     */
    int entryMode;
    
    /**
     * 恢复 checkpoint 引用（entryMode=3 时携带）
     * 
     * 指向 ContextPack/checkpoint 持久化记录
     * 用于热/冷会话切换（目标：≤300ms 恢复）
     */
    String resumePackId;
}
