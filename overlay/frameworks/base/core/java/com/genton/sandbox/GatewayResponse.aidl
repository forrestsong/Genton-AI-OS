// GatewayResponse.aidl
// 网关响应
package com.genton.sandbox;

/**
 * 网关响应
 * 
 * 网关执行结果
 */
parcelable GatewayResponse {
    int statusCode;                 // 状态码（IAgentSandboxGateway.STATUS_*）
    String result;                  // 结果数据（JSON 格式）
    String errorMessage;            // 错误信息（失败时）
    String requestId;               // 请求 ID（对应 GatewayRequest.requestId）
}
