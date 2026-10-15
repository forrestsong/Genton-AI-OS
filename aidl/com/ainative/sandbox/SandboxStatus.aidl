// SandboxStatus.aidl
// 沙箱状态 - 运行时状态信息
package com.ainative.sandbox;

/**
 * 沙箱状态
 * 
 * 包含沙箱运行时的完整状态信息，用于：
 * 1. 隐私仪表盘展示
 * 2. 系统监控
 * 3. 调试诊断
 */
parcelable SandboxStatus {
    String handleId;                // 句柄 ID
    String agentId;                 // Agent ID
    String agentName;               // Agent 显示名称
    
    // 进程状态
    int pid;                        // 进程 PID
    int uid;                        // 进程 UID
    int state;                      // 状态（0=idle, 1=active, 2=paused, 3=stopped）
    boolean isPaused;               // 是否暂停
    
    // 资源使用
    long cpuTimeMs;                 // CPU 时间（毫秒）
    long memoryBytes;               // 内存使用（字节）
    long networkBytesIn;            // 入站流量
    long networkBytesOut;           // 出站流量
    
    // 配额状态
    int totalCalls;                 // 总网关调用次数
    int remainingCalls;             // 剩余调用次数
    long totalWalltimeMs;           // 总墙钟时间
    long remainingWalltimeMs;       // 剩余墙钟时间
    
    // 授权状态
    String[] grantedCapabilities;   // 已授权能力
    boolean consentRevoked;         // 授权是否已撤销
    
    // 审计统计
    long totalActionRequests;       // 总动作请求数
    long totalActionDenied;         // 被拒绝的动作数
    long totalAuditEvents;          // 总审计事件数
    
    // 时间戳
    long createdAtMs;               // 创建时间
    long lastActiveMs;              // 最后活跃时间
}
