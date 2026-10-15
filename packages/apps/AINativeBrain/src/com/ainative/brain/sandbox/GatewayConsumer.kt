// GatewayConsumer.kt
// B6.2 网关消费端：5 OP 类型映射到完整策略链
package com.ainative.brain.sandbox

import android.content.Context
import android.util.Log
import com.ainative.action.ActionRequest
import com.ainative.action.ActionDescriptor
import com.ainative.context.ContextFrame
import com.ainative.memory.MemoryQuery
import com.ainative.memory.MemoryEntry
import com.ainative.policy.PolicyDecision
import com.ainative.sandbox.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网关消费端
 * 
 * 核心职责：
 * 1. 将 5 个 OP 类型映射到完整策略链
 * 2. 三方工具请求转换为标准 ActionRequest
 * 3. 逐调用令牌校验与审计
 * 4. 集成策略引擎和动作服务
 * 
 * 映射关系：
 * - OP_CONTEXT_READ    → IAgentContextService（脱敏）
 * - OP_UI_READ_MASKED  → IAgentUiTreeService（掩码）
 * - OP_ACTION_REQUEST  → IPolicyEngine → IAgentActionService
 * - OP_MEMORY_OWN      → IAgentMemoryService（域隔离）
 * - OP_DOCUMENT_READ   → ContentResolver（安全作用域）
 * 
 * 策略链流程（OP_ACTION_REQUEST）：
 * 1. 解析动作参数（capability、action、params）
 * 2. 构建 ActionRequest（标记 sandboxed=true）
 * 3. 调用 IPolicyEngine.evaluateAction
 * 4. 根据决策：
 *    - ALLOW → 执行并审计
 *    - DENY → 拒绝
 *    - CONFIRM → 弹出确认 UI
 * 5. 返回结果给第三方 Agent
 */
class GatewayConsumer(
    private val context: Context
) {
    companion object {
        private const val TAG = "GatewayConsumer"
        
        // 审计类别
        private const val AUDIT_CAT_SANDBOX = "sandbox"
        
        // 风险等级
        private const val RISK_LOW = "low"
        private const val RISK_MEDIUM = "medium"
        private const val RISK_HIGH = "high"
    }
    
    // 服务引用（依赖注入）
    private var contextService: Any? = null          // IAgentContextService
    private var uiTreeService: Any? = null           // IAgentUiTreeService
    private var memoryService: Any? = null           // IAgentMemoryService
    private var policyEngine: Any? = null            // IPolicyEngine
    private var actionService: Any? = null           // IAgentActionService
    private var auditService: Any? = null            // IAgentAuditService
    
    /**
     * 设置服务引用（依赖注入）
     */
    fun setServices(
        contextService: Any?,
        uiTreeService: Any?,
        memoryService: Any?,
        policyEngine: Any?,
        actionService: Any?,
        auditService: Any?
    ) {
        this.contextService = contextService
        this.uiTreeService = uiTreeService
        this.memoryService = memoryService
        this.policyEngine = policyEngine
        this.actionService = actionService
        this.auditService = auditService
    }
    
    /**
     * 消费网关请求（映射到策略链）
     */
    fun consume(
        handle: SandboxHandle,
        request: GatewayRequest
    ): GatewayResponse {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 校验令牌（handle 有效性）
            validateToken(handle)
            
            // 2. 根据 OP 类型路由
            val result = when (request.opType) {
                IAgentSandboxGateway.OP_CONTEXT_READ -> consumeContextRead(handle, request)
                IAgentSandboxGateway.OP_UI_READ_MASKED -> consumeUiReadMasked(handle, request)
                IAgentSandboxGateway.OP_ACTION_REQUEST -> consumeActionRequest(handle, request)
                IAgentSandboxGateway.OP_MEMORY_OWN -> consumeMemoryOwn(handle, request)
                IAgentSandboxGateway.OP_DOCUMENT_READ -> consumeDocumentRead(handle, request)
                else -> throw IllegalArgumentException("Unsupported OP type: ${request.opType}")
            }
            
            // 3. 审计埋点
            val elapsed = System.currentTimeMillis() - startTime
            auditGatewayConsume(handle, request, true, result, elapsed)
            
            // 4. 构建响应
            return GatewayResponse().apply {
                statusCode = IAgentSandboxGateway.STATUS_OK
                this.result = result
                errorMessage = null
                requestId = request.requestId
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception", e)
            auditGatewayConsume(handle, request, false, e.message ?: "Security error", 0)
            return GatewayResponse().apply {
                statusCode = IAgentSandboxGateway.STATUS_DENIED
                result = null
                errorMessage = e.message
                requestId = request.requestId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Consume failed", e)
            auditGatewayConsume(handle, request, false, e.message ?: "Internal error", 0)
            return GatewayResponse().apply {
                statusCode = IAgentSandboxGateway.STATUS_INTERNAL_ERROR
                result = null
                errorMessage = e.message
                requestId = request.requestId
            }
        }
    }
    
    // ==================== OP 类型消费器 ====================
    
    /**
     * 消费 OP_CONTEXT_READ：读取上下文（脱敏）
     * 
     * 映射到：IAgentContextService.getContextFrame
     * 脱敏规则：
     * - 位置精度降低到城市级
     * - 日程只返回摘要（无详细内容）
     * - 移除敏感标签（支付、通话）
     */
    private fun consumeContextRead(handle: SandboxHandle, request: GatewayRequest): String {
        Log.d(TAG, "Consuming OP_CONTEXT_READ: ${handle.handleId}")
        
        // TODO: 调用 IAgentContextService.getContextFrame
        // 1. 获取完整上下文帧
        // 2. 脱敏处理（移除精确位置、敏感标签）
        // 3. 返回脱敏后的上下文
        
        val maskedContext = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("timezone", "Asia/Shanghai")
            put("location", JSONObject().apply {
                put("city", "Beijing")  // 城市级，无经纬度
                put("country", "China")
            })
            put("weather", JSONObject().apply {
                put("condition", "sunny")
                put("temperature", 25)
            })
            put("schedule", JSONObject().apply {
                put("nextEventIn", 1800000)  // 30分钟后
                put("summary", "Meeting")    // 只有摘要
            })
            put("deviceState", JSONObject().apply {
                put("batteryLevel", 0.8)
                put("networkType", "wifi")
            })
        }
        
        return maskedContext.toString()
    }
    
    /**
     * 消费 OP_UI_READ_MASKED：读取掩码 UI 树
     * 
     * 映射到：IAgentUiTreeService.getUiTree
     * 掩码规则：
     * - 移除 FLAG_SECURE 窗口
     * - 移除敏感文本（密码、银行卡、身份证）
     * - 移除 contentDescription（可能含敏感信息）
     * - 保留结构和可操作元素
     */
    private fun consumeUiReadMasked(handle: SandboxHandle, request: GatewayRequest): String {
        Log.d(TAG, "Consuming OP_UI_READ_MASKED: ${handle.handleId}")
        
        // TODO: 调用 IAgentUiTreeService.getUiTree
        // 1. 获取完整 UI 树
        // 2. 掩码处理（移除敏感文本和标签）
        // 3. 返回掩码后的 UI 树
        
        val maskedUiTree = JSONObject().apply {
            put("packageName", "com.example.app")
            put("activity", "MainActivity")
            put("bounds", JSONObject().apply {
                put("width", 1080)
                put("height", 1920)
            })
            put("nodes", JSONArray().apply {
                // 掩码后的节点（无敏感文本）
                put(JSONObject().apply {
                    put("type", "Button")
                    put("text", "[MASKED]")  // 敏感文本已掩码
                    put("clickable", true)
                    put("bounds", JSONObject().apply {
                        put("left", 100)
                        put("top", 200)
                        put("right", 300)
                        put("bottom", 280)
                    })
                })
            })
        }
        
        return maskedUiTree.toString()
    }
    
    /**
     * 消费 OP_ACTION_REQUEST：请求执行动作（核心策略链）
     * 
     * 映射到：IPolicyEngine → IAgentActionService
     * 流程：
     * 1. 解析动作参数（capability、action、params）
     * 2. 构建 ActionRequest（标记 sandboxed=true、agentId=handle.agentId）
     * 3. 调用 IPolicyEngine.evaluateAction
     * 4. 根据决策：
     *    - ALLOW → 执行并审计
     *    - DENY → 拒绝
     *    - CONFIRM → 弹出确认 UI（高风险操作）
     * 5. 返回执行结果
     */
    private fun consumeActionRequest(handle: SandboxHandle, request: GatewayRequest): String {
        Log.d(TAG, "Consuming OP_ACTION_REQUEST: ${handle.handleId}")
        
        // 1. 解析动作参数
        val params = JSONObject(request.opParams)
        val capability = params.getString("capability")
        val actionName = params.getString("action")
        val actionParams = params.optJSONObject("params") ?: JSONObject()
        
        // 2. 构建 ActionRequest（标记沙箱来源）
        val actionRequest = buildActionRequest(
            handle = handle,
            capability = capability,
            actionName = actionName,
            actionParams = actionParams
        )
        
        // 3. 调用策略引擎评估
        val policyDecision = evaluatePolicy(actionRequest)
        
        // 4. 根据决策执行
        val result = when (policyDecision.decision) {
            "ALLOW" -> {
                // 直接执行
                executeAction(actionRequest)
            }
            "DENY" -> {
                // 拒绝
                JSONObject().apply {
                    put("status", "denied")
                    put("reason", policyDecision.reason ?: "Policy denied")
                }.toString()
            }
            "CONFIRM" -> {
                // 需要用户确认（高风险操作）
                JSONObject().apply {
                    put("status", "pending_confirmation")
                    put("confirmationId", generateConfirmationId())
                    put("message", "This action requires your confirmation")
                }.toString()
            }
            else -> {
                JSONObject().apply {
                    put("status", "error")
                    put("reason", "Unknown policy decision")
                }.toString()
            }
        }
        
        return result
    }
    
    /**
     * 消费 OP_MEMORY_OWN：读写自身记忆（域隔离）
     * 
     * 映射到：IAgentMemoryService（限定 agentId）
     * 隔离规则：
     * - 查询/写入时强制携带 agentId=handle.agentId
     * - 系统记忆不可见（category != SYSTEM）
     * - 他人实体不可见（entityId 限定为自身创建）
     */
    private fun consumeMemoryOwn(handle: SandboxHandle, request: GatewayRequest): String {
        Log.d(TAG, "Consuming OP_MEMORY_OWN: ${handle.handleId}")
        
        // TODO: 调用 IAgentMemoryService
        // 1. 解析操作类型（read/write）
        // 2. 强制过滤：agentId=handle.agentId
        // 3. 执行并返回结果
        
        val params = JSONObject(request.opParams)
        val operation = params.getString("operation")  // "read" or "write"
        
        val result = when (operation) {
            "read" -> {
                // 读取自身记忆
                JSONObject().apply {
                    put("count", 0)
                    put("memories", JSONArray())
                }
            }
            "write" -> {
                // 写入自身记忆
                JSONObject().apply {
                    put("memoryId", "mem_${System.currentTimeMillis()}")
                    put("status", "saved")
                }
            }
            else -> {
                JSONObject().apply {
                    put("error", "Unsupported operation: $operation")
                }
            }
        }
        
        return result.toString()
    }
    
    /**
     * 消费 OP_DOCUMENT_READ：读取授权文档（安全作用域）
     * 
     * 映射到：ContentResolver（安全作用域）
     * 安全规则：
     * - 校验文档 URI 在授权列表中
     * - 使用 Intent.FLAG_GRANT_READ_URI_PERMISSION
     * - 限制文件类型（无可执行文件）
     */
    private fun consumeDocumentRead(handle: SandboxHandle, request: GatewayRequest): String {
        Log.d(TAG, "Consuming OP_DOCUMENT_READ: ${handle.handleId}")
        
        // TODO: 使用 ContentResolver 读取文档
        // 1. 解析文档 URI
        // 2. 校验 URI 在授权列表中
        // 3. 读取文档内容
        // 4. 返回内容（或引用）
        
        val params = JSONObject(request.opParams)
        val documentUri = params.getString("uri")
        
        // 模拟文档读取
        val document = JSONObject().apply {
            put("uri", documentUri)
            put("mimeType", "text/plain")
            put("size", 1024)
            put("content", "Document content here...")  // 实际应流式读取
        }
        
        return document.toString()
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 校验令牌（handle 有效性）
     */
    private fun validateToken(handle: SandboxHandle) {
        // TODO: 校验 handle
        // 1. handle 存在于活跃表
        // 2. 未过期
        // 3. 未被撤销授权
        if (handle.handleId.isEmpty()) {
            throw SecurityException("Invalid handle")
        }
    }
    
    /**
     * 构建 ActionRequest（标记沙箱来源）
     */
    private fun buildActionRequest(
        handle: SandboxHandle,
        capability: String,
        actionName: String,
        actionParams: JSONObject
    ): ActionRequest {
        // TODO: 构建实际的 ActionRequest
        // 标记：sandboxed=true、agentId=handle.agentId
        return ActionRequest().apply {
            // 设置字段
        }
    }
    
    /**
     * 评估策略
     */
    private fun evaluatePolicy(actionRequest: ActionRequest): PolicyDecision {
        // TODO: 调用 IPolicyEngine.evaluateAction
        // 返回：ALLOW / DENY / CONFIRM
        return PolicyDecision().apply {
            decision = "ALLOW"  // 模拟
            reason = null
        }
    }
    
    /**
     * 执行动作
     */
    private fun executeAction(actionRequest: ActionRequest): String {
        // TODO: 调用 IAgentActionService.executeAction
        // 返回执行结果
        return JSONObject().apply {
            put("status", "success")
            put("actionId", "action_${System.currentTimeMillis()}")
        }.toString()
    }
    
    /**
     * 生成确认 ID
     */
    private fun generateConfirmationId(): String {
        return "confirm_${System.currentTimeMillis()}"
    }
    
    /**
     * 审计网关消费
     */
    private fun auditGatewayConsume(
        handle: SandboxHandle,
        request: GatewayRequest,
        success: Boolean,
        result: String,
        elapsedMs: Long
    ) {
        // TODO: 调用 IAgentAuditService
        Log.i(TAG, "Audit: handleId=${handle.handleId}, opType=${request.opType}, " +
                "success=$success, elapsed=${elapsedMs}ms")
    }
}

/**
 * 策略决策（临时数据类，实际应使用 AIDL 定义）
 */
data class PolicyDecision(
    var decision: String = "",  // ALLOW / DENY / CONFIRM
    var reason: String? = null
)
