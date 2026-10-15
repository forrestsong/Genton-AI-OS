// AgentSandboxGateway.kt
// B6.1 沙箱网关：5 OP 类型白名单执行器
package com.genton.brain.sandbox

import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.genton.sandbox.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent 沙箱网关
 * 
 * 核心职责：
 * 1. 5 OP 类型白名单执行
 * 2. 逐调用令牌校验（handle + 授权能力匹配）
 * 3. 审计埋点（每次调用记录）
 * 4. 资源配额检查（调用次数、Token 消耗、墙钟时间）
 * 
 * 白名单 OP 类型（5 个）：
 * 1. OP_CONTEXT_READ：读取上下文（用户位置、时间、日程摘要，已脱敏）
 * 2. OP_UI_READ_MASKED：读取掩码 UI 树（敏感字段已删除）
 * 3. OP_ACTION_REQUEST：请求执行动作（需策略引擎评估）
 * 4. OP_MEMORY_OWN：读写自身记忆（记忆域隔离）
 * 5. OP_DOCUMENT_READ：读取用户显式授权的文档（安全作用域）
 * 
 * 安全约束：
 * - 超出白名单的 OP 一律拒绝
 * - 每次调用检查 handle 有效性
 * - 超出配额自动终止沙箱
 * - 敏感 OP 需二次授权
 */
class AgentSandboxGateway(
    private val context: Context
) : IAgentSandboxGateway.Stub() {
    
    companion object {
        private const val TAG = "AgentSandboxGateway"
        
        // OP 类型能力映射（OP -> 需要的能力）
        private val OP_CAPABILITY_MAP = mapOf(
            IAgentSandboxGateway.OP_CONTEXT_READ to "context.read",
            IAgentSandboxGateway.OP_UI_READ_MASKED to "ui.read_masked",
            IAgentSandboxGateway.OP_ACTION_REQUEST to "action.request",
            IAgentSandboxGateway.OP_MEMORY_OWN to "memory.own",
            IAgentSandboxGateway.OP_DOCUMENT_READ to "document.read"
        )
        
        // 高风险 OP（需用户二次确认）
        private val HIGH_RISK_OPS = setOf(
            IAgentSandboxGateway.OP_ACTION_REQUEST
        )
    }
    
    // 沙箱管理器引用（依赖注入）
    private var sandboxManager: AgentSandboxManager? = null
    
    // 网关消费端（B6.2 策略链映射）
    private val gatewayConsumer = GatewayConsumer(context)
    
    // 调用统计（handleId -> 调用计数器）
    private val callCounters = ConcurrentHashMap<String, AtomicLong>()
    
    // 墙钟时间统计（handleId -> 起始时间戳）
    private val walltimeTrackers = ConcurrentHashMap<String, Long>()
    
    /**
     * 设置沙箱管理器引用
     */
    fun setSandboxManager(manager: AgentSandboxManager) {
        this.sandboxManager = manager
    }
    
    /**
     * 设置网关消费端的服务引用（B6.2）
     */
    fun setConsumerServices(
        contextService: Any?,
        uiTreeService: Any?,
        memoryService: Any?,
        policyEngine: Any?,
        actionService: Any?,
        auditService: Any?
    ) {
        gatewayConsumer.setServices(
            contextService,
            uiTreeService,
            memoryService,
            policyEngine,
            actionService,
            auditService
        )
    }
    
    /**
     * 执行网关请求
     */
    override fun execute(
        handle: SandboxHandle,
        request: GatewayRequest,
        response: GatewayResponse
    ): Int {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. 校验 handle 有效性
            val validationResult = validateHandle(handle)
            if (validationResult != IAgentSandboxGateway.STATUS_OK) {
                response.statusCode = validationResult
                response.errorMessage = "Invalid handle"
                response.requestId = request.requestId
                return validationResult
            }
            
            // 2. 检查 OP 类型在白名单中
            if (!isOpTypeValid(request.opType)) {
                Log.e(TAG, "Invalid OP type: ${request.opType}")
                response.statusCode = IAgentSandboxGateway.STATUS_INVALID_OP
                response.errorMessage = "OP type not in whitelist"
                response.requestId = request.requestId
                auditGatewayCall(handle, request, false, "INVALID_OP")
                return IAgentSandboxGateway.STATUS_INVALID_OP
            }
            
            // 3. 检查该能力已授权
            val requiredCapability = OP_CAPABILITY_MAP[request.opType]
            if (requiredCapability != null && !hasCapability(handle, requiredCapability)) {
                Log.e(TAG, "Capability not granted: $requiredCapability")
                response.statusCode = IAgentSandboxGateway.STATUS_DENIED
                response.errorMessage = "Capability not granted: $requiredCapability"
                response.requestId = request.requestId
                auditGatewayCall(handle, request, false, "DENIED")
                return IAgentSandboxGateway.STATUS_DENIED
            }
            
            // 4. 检查配额（调用次数）
            if (!checkCallQuota(handle)) {
                Log.e(TAG, "Call quota exceeded: ${handle.handleId}")
                response.statusCode = IAgentSandboxGateway.STATUS_OVER_QUOTA
                response.errorMessage = "Call quota exceeded"
                response.requestId = request.requestId
                auditGatewayCall(handle, request, false, "OVER_QUOTA")
                return IAgentSandboxGateway.STATUS_OVER_QUOTA
            }
            
            // 5. 检查配额（墙钟时间）
            if (!checkWalltimeQuota(handle)) {
                Log.e(TAG, "Walltime quota exceeded: ${handle.handleId}")
                response.statusCode = IAgentSandboxGateway.STATUS_OVER_QUOTA
                response.errorMessage = "Walltime quota exceeded"
                response.requestId = request.requestId
                auditGatewayCall(handle, request, false, "OVER_QUOTA")
                return IAgentSandboxGateway.STATUS_OVER_QUOTA
            }
            
            // 6. 执行操作（路由到对应系统服务）
            val result = executeOp(handle, request)
            
            // 7. 填充响应
            response.statusCode = IAgentSandboxGateway.STATUS_OK
            response.result = result
            response.errorMessage = null
            response.requestId = request.requestId
            
            // 8. 审计埋点
            val elapsed = System.currentTimeMillis() - startTime
            auditGatewayCall(handle, request, true, "SUCCESS", elapsed)
            
            // 9. 更新配额
            incrementCallCount(handle.handleId)
            updateWalltimeUsage(handle.handleId, elapsed)
            
            Log.d(TAG, "Gateway execute success: handleId=${handle.handleId}, opType=${request.opType}, elapsed=${elapsed}ms")
            return IAgentSandboxGateway.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Gateway execute failed", e)
            response.statusCode = IAgentSandboxGateway.STATUS_INTERNAL_ERROR
            response.errorMessage = e.message ?: "Internal error"
            response.requestId = request.requestId
            auditGatewayCall(handle, request, false, "ERROR: ${e.message}")
            return IAgentSandboxGateway.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 批量执行
     */
    override fun executeBatch(
        handle: SandboxHandle,
        requests: Array<GatewayRequest>
    ): Array<GatewayResponse> {
        Log.d(TAG, "Gateway executeBatch: handleId=${handle.handleId}, count=${requests.size}")
        
        return requests.map { request ->
            val response = GatewayResponse()
            execute(handle, request, response)
            response
        }.toTypedArray()
    }
    
    /**
     * 查询配额状态
     */
    override fun getQuotaStatus(handle: SandboxHandle): String {
        val callCount = callCounters[handle.handleId]?.get() ?: 0
        val walltimeUsed = calculateWalltimeUsed(handle.handleId)
        
        val status = JSONObject().apply {
            put("handleId", handle.handleId)
            put("totalCalls", callCount)
            put("remainingCalls", maxOf(0, handle.remainingCalls - callCount.toInt()))
            put("walltimeUsedMs", walltimeUsed)
            put("remainingWalltimeMs", maxOf(0, handle.remainingWalltimeMs - walltimeUsed))
        }
        
        return status.toString()
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 校验 handle 有效性
     */
    private fun validateHandle(handle: SandboxHandle): Int {
        // TODO: 实际实现需从 SandboxManager 校验 handle
        // 1. handle 存在于活跃表
        // 2. 未过期
        // 3. 未被撤销授权
        if (handle.handleId.isEmpty()) {
            return IAgentSandboxGateway.STATUS_INVALID_HANDLE
        }
        return IAgentSandboxGateway.STATUS_OK
    }
    
    /**
     * 检查 OP 类型是否在白名单中
     */
    private fun isOpTypeValid(opType: Int): Boolean {
        return opType in listOf(
            IAgentSandboxGateway.OP_CONTEXT_READ,
            IAgentSandboxGateway.OP_UI_READ_MASKED,
            IAgentSandboxGateway.OP_ACTION_REQUEST,
            IAgentSandboxGateway.OP_MEMORY_OWN,
            IAgentSandboxGateway.OP_DOCUMENT_READ
        )
    }
    
    /**
     * 检查是否拥有能力
     */
    private fun hasCapability(handle: SandboxHandle, capability: String): Boolean {
        return handle.grantedCapabilities.contains(capability)
    }
    
    /**
     * 检查调用配额
     */
    private fun checkCallQuota(handle: SandboxHandle): Boolean {
        val callCount = callCounters[handle.handleId]?.get() ?: 0
        return callCount < handle.remainingCalls
    }
    
    /**
     * 检查墙钟时间配额
     */
    private fun checkWalltimeQuota(handle: SandboxHandle): Boolean {
        val walltimeUsed = calculateWalltimeUsed(handle.handleId)
        return walltimeUsed < handle.remainingWalltimeMs
    }
    
    /**
     * 计算已使用墙钟时间
     */
    private fun calculateWalltimeUsed(handleId: String): Long {
        val startTime = walltimeTrackers[handleId] ?: return 0L
        return System.currentTimeMillis() - startTime
    }
    
    /**
     * 执行操作（路由到对应执行器）
     */
    private fun executeOp(handle: SandboxHandle, request: GatewayRequest): String {
        Log.d(TAG, "Executing OP: type=${request.opType}, handleId=${handle.handleId}")
        
        return when (request.opType) {
            IAgentSandboxGateway.OP_CONTEXT_READ -> executeContextRead(handle, request)
            IAgentSandboxGateway.OP_UI_READ_MASKED -> executeUiReadMasked(handle, request)
            IAgentSandboxGateway.OP_ACTION_REQUEST -> executeActionRequest(handle, request)
            IAgentSandboxGateway.OP_MEMORY_OWN -> executeMemoryOwn(handle, request)
            IAgentSandboxGateway.OP_DOCUMENT_READ -> executeDocumentRead(handle, request)
            else -> throw IllegalArgumentException("Unsupported OP type: ${request.opType}")
        }
    }
    
    /**
     * 执行 OP_CONTEXT_READ：读取上下文（脱敏）
     */
    private fun executeContextRead(handle: SandboxHandle, request: GatewayRequest): String {
        // TODO: 调用 IAgentContextService，返回脱敏上下文
        // 1. 当前时间、星期、节假日
        // 2. 用户位置（城市级，不含精确坐标）
        // 3. 日程摘要（无详细内容）
        // 4. 天气、通勤状态
        Log.d(TAG, "OP_CONTEXT_READ: ${handle.handleId}")
        
        val context = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("location", "Beijing")  // 城市级
            put("weather", "sunny")
            put("nextEvent", "Meeting in 30 mins")  // 摘要
        }
        
        return context.toString()
    }
    
    /**
     * 执行 OP_UI_READ_MASKED：读取掩码 UI 树
     */
    private fun executeUiReadMasked(handle: SandboxHandle, request: GatewayRequest): String {
        // TODO: 调用 IAgentUiTreeService，返回掩码 UI 树
        // 1. 移除敏感文本（密码、银行卡、身份证）
        // 2. 移除敏感标签（FLAG_SECURE）
        // 3. 保留结构和可操作元素
        Log.d(TAG, "OP_UI_READ_MASKED: ${handle.handleId}")
        
        val uiTree = JSONObject().apply {
            put("packageName", "com.example.app")
            put("activity", "MainActivity")
            put("nodes", emptyArray<Any>())  // 掩码后的节点
        }
        
        return uiTree.toString()
    }
    
    /**
     * 执行 OP_ACTION_REQUEST：请求执行动作
     */
    private fun executeActionRequest(handle: SandboxHandle, request: GatewayRequest): String {
        // TODO: 路由到策略引擎评估
        // 1. 解析动作参数
        // 2. 调用 IPolicyEngine.evaluateAction
        // 3. 高风险操作弹出确认 UI
        // 4. 执行并审计
        Log.d(TAG, "OP_ACTION_REQUEST: ${handle.handleId}")
        
        val result = JSONObject().apply {
            put("status", "pending_confirmation")  // 需用户确认
            put("actionId", "action_123")
        }
        
        return result.toString()
    }
    
    /**
     * 执行 OP_MEMORY_OWN：读写自身记忆
     */
    private fun executeMemoryOwn(handle: SandboxHandle, request: GatewayRequest): String {
        // TODO: 调用 IAgentMemoryService，限定记忆域为 own_only
        // 1. 查询/写入时携带 agentId 过滤
        // 2. 系统记忆/他人实体不可见
        Log.d(TAG, "OP_MEMORY_OWN: ${handle.handleId}")
        
        val memories = JSONObject().apply {
            put("count", 0)
            put("items", emptyArray<Any>())
        }
        
        return memories.toString()
    }
    
    /**
     * 执行 OP_DOCUMENT_READ：读取授权文档
     */
    private fun executeDocumentRead(handle: SandboxHandle, request: GatewayRequest): String {
        // TODO: 读取用户显式授权的文档（安全作用域）
        // 1. 校验文档 URI 在授权列表中
        // 2. 调用 ContentResolver 读取
        // 3. 返回文档内容
        Log.d(TAG, "OP_DOCUMENT_READ: ${handle.handleId}")
        
        val document = JSONObject().apply {
            put("uri", "content://docs/123")
            put("content", "Document content here...")
        }
        
        return document.toString()
    }
    
    /**
     * 审计网关调用
     */
    private fun auditGatewayCall(
        handle: SandboxHandle,
        request: GatewayRequest,
        success: Boolean,
        reason: String,
        elapsedMs: Long = 0
    ) {
        // TODO: 调用 IAgentAuditService 记录
        Log.i(TAG, "Audit: handleId=${handle.handleId}, opType=${request.opType}, " +
                "success=$success, reason=$reason, elapsed=${elapsedMs}ms")
    }
    
    /**
     * 增加调用计数
     */
    private fun incrementCallCount(handleId: String) {
        callCounters.getOrPut(handleId) { AtomicLong(0) }.incrementAndGet()
    }
    
    /**
     * 更新墙钟时间使用
     */
    private fun updateWalltimeUsage(handleId: String, elapsedMs: Long) {
        walltimeTrackers.putIfAbsent(handleId, System.currentTimeMillis())
    }
    
    /**
     * 清理句柄相关统计
     */
    fun cleanupHandle(handleId: String) {
        callCounters.remove(handleId)
        walltimeTrackers.remove(handleId)
        Log.d(TAG, "Cleaned up handle: $handleId")
    }
}
