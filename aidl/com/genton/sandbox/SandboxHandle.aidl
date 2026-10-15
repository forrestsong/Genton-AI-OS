// SandboxHandle.aidl
// 沙箱句柄 - 沙箱身份凭证
package com.genton.sandbox;

/**
 * 沙箱句柄
 * 
 * 沙箱进程的身份凭证，用于：
 * 1. 网关调用身份校验
 * 2. 授权能力检查
 * 3. 配额管理
 * 4. 审计埋点
 * 
 * 生命周期：
 * - startSandbox 时创建
 * - stopSandbox 时失效
 * - 不可伪造（系统签名）
 */
parcelable SandboxHandle {
    String handleId;                // 句柄 ID（唯一标识符）
    String agentId;                 // Agent ID
    int sandboxPid;                 // 沙箱进程 PID
    int sandboxUid;                 // 沙箱进程 UID（isolatedProcess）
    long createdAtMs;               // 创建时间戳
    String sessionId;               // 关联的会话 ID（可空）
    
    // 授权能力列表（运行时有效）
    String[] grantedCapabilities;   // 已授权的能力
    
    // 配额信息
    int remainingCalls;             // 剩余网关调用次数
    long remainingWalltimeMs;       // 剩余墙钟时间
}
