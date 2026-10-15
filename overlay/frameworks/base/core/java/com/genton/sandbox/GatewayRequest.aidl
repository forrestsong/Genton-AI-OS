// GatewayRequest.aidl
// 网关请求
package com.genton.sandbox;

/**
 * 网关请求
 * 
 * 第三方 Agent 通过网关执行受控操作的请求结构
 */
parcelable GatewayRequest {
    int opType;                     // OP 类型（IAgentSandboxGateway.OP_*）
    String opParams;                // 参数（JSON 格式）
    String requestId;               // 请求 ID（用于追踪）
}
