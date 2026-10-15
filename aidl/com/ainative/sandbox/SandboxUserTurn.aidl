// SandboxUserTurn.aidl
// 用户轮次（沙箱版）
package com.ainative.sandbox;

/**
 * 用户轮次
 * 
 * 第三方 Agent 接收的用户输入
 */
parcelable SandboxUserTurn {
    String turnId;                  // 轮次 ID
    String message;                 // 用户消息文本
    String[] attachmentRefs;        // 附件引用（安全作用域内）
    long timestamp;                 // 时间戳
}
