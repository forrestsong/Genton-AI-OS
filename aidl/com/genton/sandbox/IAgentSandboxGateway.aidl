// IAgentSandboxGateway.aidl
// 沙箱网关接口 - 系统服务实现，沙箱进程唯一能力入口
package com.genton.sandbox;

import com.genton.sandbox.SandboxHandle;
import com.genton.sandbox.GatewayRequest;
import com.genton.sandbox.GatewayResponse;

/**
 * Agent 沙箱网关接口
 * 
 * 实现方：系统服务（大脑特权 App 内）
 * 调用方：第三方 Agent（isolatedProcess，需 USE_SANDBOX 权限）
 * 
 * 核心职责：
 * 1. 5 OP 类型白名单执行（上下文+掩码 UI+动作+自身记忆+文档）
 * 2. 逐调用令牌校验（handle + 授权能力匹配）
 * 3. 审计埋点（每次调用记录到审计服务）
 * 4. 资源配额检查（调用次数、Token 消耗、墙钟时间）
 * 
 * 白名单 OP 类型（5 个）：
 * - OP_CONTEXT_READ：读取上下文（用户位置、时间、日程摘要，已脱敏）
 * - OP_UI_READ_MASKED：读取掩码 UI 树（敏感字段已删除）
 * - OP_ACTION_REQUEST：请求执行动作（需策略引擎评估）
 * - OP_MEMORY_OWN：读写自身记忆（记忆域隔离，不可见系统/他人）
 * - OP_DOCUMENT_READ：读取用户显式授权的文档（安全作用域）
 * 
 * 安全约束：
 * - 超出白名单的 OP 一律拒绝
 * - 每次调用检查 handle 有效性
 * - 超出配额自动终止沙箱
 * - 敏感 OP 需二次授权（动态 Toast 确认）
 */
interface IAgentSandboxGateway {
    // OP 类型常量（白名单）
    const int OP_CONTEXT_READ      = 1;  // 读取上下文（脱敏）
    const int OP_UI_READ_MASKED    = 2;  // 读取掩码 UI 树
    const int OP_ACTION_REQUEST    = 3;  // 请求执行动作
    const int OP_MEMORY_OWN        = 4;  // 自身记忆读写
    const int OP_DOCUMENT_READ     = 5;  // 授权文档读取
    
    // 状态码
    const int STATUS_OK              = 0;
    const int STATUS_DENIED          = 1;   // 能力未授权
    const int STATUS_INVALID_HANDLE  = 2;   // 句柄无效
    const int STATUS_OVER_QUOTA      = 3;   // 超出配额
    const int STATUS_INVALID_OP      = 4;   // OP 类型不在白名单
    const int STATUS_INTERNAL_ERROR  = -1;
    
    /**
     * 执行网关请求
     * 
     * 流程：
     * 1. 校验 handle 有效性
     * 2. 检查 opType 在白名单中
     * 3. 检查该能力已授权
     * 4. 检查配额（调用次数/Token/时间）
     * 5. 审计埋点
     * 6. 执行操作（路由到对应系统服务）
     * 7. 返回结果（成功/失败/部分结果）
     * 
     * @param handle 沙箱句柄（携带身份和授权信息）
     * @param request 网关请求（opType、参数）
     * @param response 输出参数，返回执行结果
     * @return STATUS_OK 成功；STATUS_DENIED 能力未授权；
     *         STATUS_INVALID_OP OP 类型无效；STATUS_OVER_QUOTA 超出配额
     */
    int execute(in SandboxHandle handle, in GatewayRequest request, out GatewayResponse response);
    
    /**
     * 批量执行（优化多次调用开销）
     * 
     * @param handle 沙箱句柄
     * @param requests 请求列表
     * @return 响应列表（与请求一一对应）
     */
    GatewayResponse[] executeBatch(in SandboxHandle handle, in GatewayRequest[] requests);
    
    /**
     * 查询配额状态
     * 
     * 第三方 Agent 可查询当前剩余配额（避免超限被终止）
     * 
     * @param handle 沙箱句柄
     * @return 配额状态（剩余调用次数、Token 预算、墙钟时间）
     */
    String getQuotaStatus(in SandboxHandle handle);
}
