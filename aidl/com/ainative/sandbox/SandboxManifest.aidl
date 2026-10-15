// SandboxManifest.aidl
// 沙箱清单 - 能力与边界声明
package com.ainative.sandbox;

/**
 * 沙箱清单
 * 
 * 第三方 Agent 的能力声明文件，类似 AndroidManifest.xml。
 * 
 * 内容：
 * 1. Agent 身份（id、name、developer、version）
 * 2. 签名引用（系统校验）
 * 3. 请求的能力列表（逐项授权）
 * 4. 模型端点（远端 API 白名单）
 * 5. 记忆范围（own_only）
 * 6. 网络域名白名单（netd 配置）
 * 7. 预算限制（成本、墙钟时间）
 * 
 * 安全约束：
 * - 必须经应用签名验证
 * - 首启需用户逐项授权
 * - 能力可随时撤销
 */
parcelable SandboxManifest {
    // Agent 身份
    String agentId;                 // Agent ID（如 "com.example.gptmate.agent"）
    String agentName;               // 显示名称（如 "GPT 小助手"）
    String developer;               // 开发者（如 "com.example"）
    int version;                    // 版本号
    String signatureRef;            // 签名引用（系统校验）
    
    // 模型端点（远端 API 白名单）
    String[] modelEndpoints;        // 允许访问的模型 API 域名
    
    // 请求的能力列表（逐项授权）
    String[] requestedCapabilities; // 能力名（如 "agent.input_inject", "events.read"）
    String[] capabilityPurposes;    // 对应用途说明（向用户展示）
    
    // 记忆范围（固定为 own_only）
    String memoryScope;             // "own_only"（只读写自身记忆）
    
    // 网络域名白名单
    String[] networkDomains;        // 出网白名单（netd 配置）
    
    // 预算限制
    float costPerDayUsd;            // 每日成本限额（美元）
    long maxWalltimeMs;             // 单任务墙钟时间限制（毫秒）
    int maxCallsPerHour;            // 每小时网关调用次数限制
}
