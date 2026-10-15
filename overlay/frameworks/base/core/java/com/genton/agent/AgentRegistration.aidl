// AgentRegistration.aidl
// Agent 注册信息
package com.genton.agent;

/**
 * Agent 注册信息
 * 
 * Agent 启动时向 ACB 服务注册，提供身份、能力需求、沙箱配置等信息
 */
parcelable AgentRegistration {
    /**
     * Agent 名称（人类可读）
     * 
     * 用于 UI 展示和审计
     */
    String agentName;
    
    /**
     * Persona 引用（签名的 persona/能力声明）
     * 
     * 防止 Agent 冒用其他 persona
     */
    String personaRef;
    
    /**
     * 请求的能力列表
     * 
     * 期望能力（实际授予由策略引擎按最小权限裁决）
     */
    String[] requestedCapabilities;
    
    /**
     * 是否允许免确认自主运行
     * 
     * 默认 false，需用户全局开启
     */
    boolean allowUserlessAutonomy;
    
    /**
     * 是否沙箱化
     * 
     * 第三方 Agent 强制 true（隔离文件/网络）
     */
    boolean sandboxed;
    
    /**
     * 托管大脑应用包名
     * 
     * 服务校验调用方 UID 与此包名一致
     */
    String ownerPackage;
}
