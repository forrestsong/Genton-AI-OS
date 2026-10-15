// ToolResultEnvelope.aidl
// 工具执行结果封装
package com.genton.sandbox;

/**
 * 工具执行结果封装
 * 
 * 宿主执行工具调用后的结果
 */
parcelable ToolResultEnvelope {
    String callId;                  // 调用 ID（对应 SandboxToolCall.callId）
    boolean success;                // 是否成功
    String result;                  // 结果数据（JSON 格式）
    String errorMessage;            // 错误信息（失败时）
    int statusCode;                 // 状态码
}
