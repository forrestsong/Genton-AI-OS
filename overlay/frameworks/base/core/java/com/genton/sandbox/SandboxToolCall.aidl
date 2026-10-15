// SandboxToolCall.aidl
// 工具调用请求（第三方 -> 宿主）
package com.genton.sandbox;

/**
 * 工具调用请求
 * 
 * 第三方 Agent 请求宿主执行系统能力
 */
parcelable SandboxToolCall {
    String callId;                  // 调用 ID（用于结果匹配）
    int opType;                     // OP 类型（IAgentSandboxGateway.OP_*）
    String opParams;                // 参数（JSON 格式）
    boolean requiresConfirmation;   // 是否需要用户确认
}
