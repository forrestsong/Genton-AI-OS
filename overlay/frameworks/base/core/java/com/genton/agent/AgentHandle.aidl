// AgentHandle.aidl
// Agent 句柄
package com.genton.agent;

/**
 * Agent 句柄
 * 
 * registerAgent() 成功后返回，后续所有 Agent 操作使用此句柄
 */
parcelable AgentHandle {
    /**
     * Agent 全局唯一标识
     * 
     * 注册时由 ACB 服务签发
     */
    String agentId;
    
    /**
     * 身份证书引用
     * 
     * 绑定 personaRef + ownerPackage，防伪造
     */
    String certificateRef;
    
    /**
     * 创建时间戳（纳秒，SystemClock.elapsedRealtimeNanos）
     */
    long createdAtRealtimeNanos;
}
