package com.genton.memory;

/**
 * AgentHandle - Agent 句柄（记忆服务视角）
 *
 * 说明：AIDL 的跨模块类型引用需要 aidl_interface 声明 imports 与版本，
 * 因此各模块按同一契约各自维护一份 AgentHandle 定义。字段与
 * com.genton.agent.AgentHandle 保持一致，避免调用方语义歧义。
 *
 * 服务端实现：AgentRuntimeService.registerAgent() 返回该句柄。
 */
parcelable AgentHandle {
    /** Agent 全局唯一标识（注册时由 ACB 服务签发） */
    String agentId;

    /** 身份证书引用（绑定 personaRef + ownerPackage，防伪造） */
    String certificateRef;

    /** 创建时间戳（单调时钟纳秒） */
    long createdAtRealtimeNanos;
}
