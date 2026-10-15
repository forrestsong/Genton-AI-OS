package com.ainative.action

/**
 * A3.6 内部动作模型
 * 
 * 内部动作（Internal Actions）是系统专用的特殊动作，不对外暴露给普通应用或用户。
 * 这些动作用于系统内部优化、策略管理、性能调优等场景。
 * 
 * 特点：
 * 1. 仅大脑/系统服务可调用（signature|privileged权限）
 * 2. 不计入用户可见的审计流（内部审计记录）
 * 3. 不需要用户确认（系统自动化）
 * 4. 不受普通ACB预算约束（内部预算池）
 * 5. 具有更高的执行优先级
 * 
 * 命名规范：
 * - sys.*：系统级操作（prewarm/render/schedule等）
 * - cmd.*：命令行/测试工具操作
 * - policy.*：策略管理操作（activate/deactivate等）
 * - internal.*：其他内部操作
 */

/**
 * 内部动作注册条目
 * 
 * 与普通ActionRegistryEntry的区别：
 * - internal = true（内部动作标记）
 * - requiredCapability通常为SYSTEM_INTERNAL
 * - requiresConfirmation = false（内部动作不需要确认）
 * - visibleToUser = false（不在用户界面显示）
 */
data class InternalActionEntry(
    val actionName: String,
    val description: String,
    val category: InternalActionCategory,
    val requiredPermission: String = "android.permission.MANAGE_AINATIVE_INTERNAL",
    val parameters: List<ActionParameter>,
    val executorType: String,
    val priority: InternalActionPriority = InternalActionPriority.NORMAL,
    val auditLevel: InternalAuditLevel = InternalAuditLevel.SYSTEM_ONLY,
    val budgetExempt: Boolean = true,  // 默认不受ACB预算约束
    val minApiLevel: Int = 1
)

/**
 * 内部动作分类
 */
enum class InternalActionCategory {
    /**
     * 性能优化类
     * - sys.prewarm_app：预热应用进程
     * - sys.set_render_policy：设置渲染策略
     * - sys.schedule_prep：任务预调度
     */
    PERFORMANCE_OPTIMIZATION,
    
    /**
     * 策略管理类
     * - policy.activate_suggestion：激活learn建议
     * - policy.deactivate_rule：停用策略规则
     * - policy.reload_policy_set：重载策略集
     */
    POLICY_MANAGEMENT,
    
    /**
     * 资源准备类
     * - sys.camera.prepare_qa：相机Q&A准备
     * - sys.audio.record_memo：备忘录录音
     */
    RESOURCE_PREPARATION,
    
    /**
     * 测试与调试类
     * - cmd.run_harness：运行测试套件
     * - cmd.dump_state：转储系统状态
     */
    TESTING_DEBUG,
    
    /**
     * 姿态交互类（S7）
     * - sys.motion.enable_shake_ready：启用摇一摇就绪
     * - sys.motion.start_memo_recording：开始备忘录录音
     */
    MOTION_INTERACTION
}

/**
 * 内部动作优先级
 */
enum class InternalActionPriority(val value: Int) {
    /**
     * 最高优先级：紧急系统操作
     */
    CRITICAL(100),
    
    /**
     * 高优先级：性能优化操作
     */
    HIGH(80),
    
    /**
     * 普通优先级：常规内部操作
     */
    NORMAL(50),
    
    /**
     * 低优先级：后台维护操作
     */
    LOW(20);
    
    companion object {
        fun fromValue(value: Int): InternalActionPriority {
            return entries.firstOrNull { it.value == value } ?: NORMAL
        }
    }
}

/**
 * 内部审计级别
 */
enum class InternalAuditLevel {
    /**
     * 无审计：临时/测试操作
     */
    NONE,
    
    /**
     * 仅系统审计：记录在系统内部审计流
     */
    SYSTEM_ONLY,
    
    /**
     * 完整审计：记录在用户可见的审计流（罕见）
     */
    FULL
}

/**
 * 内部动作请求
 * 
 * 与ActionRequest的区别：
 * - 增加internalContext（系统上下文信息）
 * - 增加skipBudgetCheck（跳过预算检查标记）
 */
data class InternalActionRequest(
    val actionName: String,
    val sessionId: String,
    val arguments: Map<String, Any>,
    val argumentSources: Map<String, String> = emptyMap(),
    val internalContext: InternalActionContext? = null,
    val skipBudgetCheck: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 转换为普通ActionRequest（用于执行器接口）
     */
    fun toActionRequest(): ActionModels.ActionRequest {
        return ActionModels.ActionRequest(
            actionName = actionName,
            sessionId = sessionId,
            arguments = arguments,
            argumentSources = argumentSources
        )
    }
}

/**
 * 内部动作上下文
 * 
 * 携带系统级上下文信息，用于执行器决策
 */
data class InternalActionContext(
    /**
     * 调用来源（大脑/规划器/策略引擎等）
     */
    val caller: String,
    
    /**
     * 触发原因
     */
    val reason: String,
    
    /**
     * 关联的用户会话ID（如果有）
     */
    val relatedSessionId: String? = null,
    
    /**
     * 系统状态快照（用于执行器决策）
     */
    val systemState: Map<String, Any> = emptyMap(),
    
    /**
     * 执行约束（超时/重试等）
     */
    val constraints: Map<String, Any> = emptyMap()
)

/**
 * 内部动作执行结果
 */
data class InternalActionResult(
    val success: Boolean,
    val resultData: Map<String, Any>? = null,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val executionTimeMs: Long = 0,
    val systemImpact: SystemImpact? = null
)

/**
 * 系统影响评估
 * 
 * 记录内部动作对系统的影响，用于性能分析和优化
 */
data class SystemImpact(
    /**
     * CPU时间（纳秒）
     */
    val cpuTimeNs: Long = 0,
    
    /**
     * 内存占用（字节）
     */
    val memoryBytes: Long = 0,
    
    /**
     * 磁盘IO（字节）
     */
    val diskIoBytes: Long = 0,
    
    /**
     * 网络IO（字节）
     */
    val networkIoBytes: Long = 0,
    
    /**
     * 受影响的进程数
     */
    val affectedProcessCount: Int = 0,
    
    /**
     * 自定义指标
     */
    val customMetrics: Map<String, Number> = emptyMap()
)

/**
 * 内部动作注册表
 * 
 * 管理所有内部动作的定义和元数据
 */
object InternalActionRegistry {
    private val actions = mutableMapOf<String, InternalActionEntry>()
    
    /**
     * 注册内部动作
     */
    fun register(entry: InternalActionEntry) {
        if (actions.containsKey(entry.actionName)) {
            throw IllegalArgumentException("Internal action already registered: ${entry.actionName}")
        }
        actions[entry.actionName] = entry
    }
    
    /**
     * 获取内部动作
     */
    fun get(actionName: String): InternalActionEntry? {
        return actions[actionName]
    }
    
    /**
     * 检查是否为内部动作
     */
    fun isInternalAction(actionName: String): Boolean {
        return actions.containsKey(actionName)
    }
    
    /**
     * 列出所有内部动作
     */
    fun listAll(): List<InternalActionEntry> {
        return actions.values.toList()
    }
    
    /**
     * 按分类列出
     */
    fun listByCategory(category: InternalActionCategory): List<InternalActionEntry> {
        return actions.values.filter { it.category == category }
    }
    
    /**
     * 清空注册表（仅用于测试）
     */
    fun clearForTesting() {
        actions.clear()
    }
}
