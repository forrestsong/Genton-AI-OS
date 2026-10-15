// SandboxSessionContext.aidl
// 沙箱会话上下文
package com.genton.sandbox;

/**
 * 沙箱会话上下文
 * 
 * 启动第三方 Agent 时传入的上下文信息
 */
parcelable SandboxSessionContext {
    String sessionId;               // 会话 ID
    String handleId;                // 沙箱句柄 ID
    String[] grantedCapabilities;   // 已授权能力列表
    long sessionStartMs;            // 会话开始时间
}
