package com.ainative.action

/**
 * A3.4 Taint规则模型
 * 
 * Taint（污染）追踪是防止数据劫持的关键安全机制。
 * 核心原则：敏感操作的参数必须来自可信源，禁止使用可能被劫持的污染数据。
 * 
 * 三层来源分级：
 * 1. TRUSTED（可信源）：系统服务/大脑内部计算/策略引擎输出
 * 2. USER（用户源）：用户直接输入/用户明确确认的数据
 * 3. ANY（任意源）：外部应用/网络数据/不确定来源
 * 
 * Taint规则：
 * - 写操作（insert/update/reply/write）参数必须≥USER源
 * - 敏感操作（支付/转账/授权）参数必须=USER源
 * - 系统内部操作参数必须=TRUSTED源
 * - 读操作参数可以是ANY源
 * 
 * 示例场景：
 * 1. ✅ 允许：用户输入文本(USER) → 发送消息
 * 2. ❌ 拒绝：外部应用提供文本(ANY) → 自动回复通知（防劫持）
 * 3. ✅ 允许：大脑计算金额(TRUSTED) → 预填表单
 * 4. ❌ 拒绝：网络数据(ANY) → 转账金额（防劫持）
 */

/**
 * 参数来源枚举
 * 
 * 表示动作参数的数据来源，用于taint追踪和安全校验
 * 
 * 注意：与ActionRequest.taintedArgumentKeys配合使用
 * - taintedArgumentKeys包含的参数 = TAINTED源（来自APP_CONTENT/WEB_CONTENT）
 * - taintedArgumentKeys不包含的参数 = CLEAN源（来自Brain/User）
 */
enum class ArgumentSource(val level: Int, val description: String) {
    /**
     * 清洁源（高信任级别）
     * 
     * 包括：
     * - 大脑规划器计算
     * - 用户直接输入
     * - 系统服务内部数据
     * 
     * 对应：不在taintedArgumentKeys中的参数
     * 
     * 特点：
     * - 可信，可用于敏感操作
     */
    CLEAN(level = 2, description = "Brain/User/System trusted source"),
    
    /**
     * 污染源（低信任级别）
     * 
     * 包括：
     * - APP_CONTENT：从外部应用提取
     * - WEB_CONTENT：从网络数据提取
     * - 通知内容
     * - 不确定来源
     * 
     * 对应：在taintedArgumentKeys中的参数
     * 
     * 特点：
     * - 可能被劫持或伪造
     * - 禁止用于写操作和敏感操作
     */
    TAINTED(level = 1, description = "APP_CONTENT/WEB_CONTENT untrusted source");
    
    companion object {
        /**
         * 根据参数是否在taintedArgumentKeys中判断来源
         */
        fun fromTaintStatus(isTainted: Boolean): ArgumentSource {
            return if (isTainted) TAINTED else CLEAN
        }
    }
}

/**
 * Taint规则定义
 * 
 * 为每个动作参数指定最低要求的来源级别
 */
data class TaintRule(
    /**
     * 参数名
     */
    val parameterName: String,
    
    /**
     * 要求的最低来源级别
     */
    val requiredSource: ArgumentSource,
    
    /**
     * 规则说明（用于审计和错误提示）
     */
    val reason: String,
    
    /**
     * 是否强制校验（false时仅警告）
     */
    val enforce: Boolean = true
)

/**
 * Taint校验结果
 */
data class TaintValidationResult(
    /**
     * 是否通过校验
     */
    val passed: Boolean,
    
    /**
     * 违规的参数列表
     */
    val violations: List<TaintViolation> = emptyList(),
    
    /**
     * 警告列表（非强制规则）
     */
    val warnings: List<TaintViolation> = emptyList()
) {
    companion object {
        fun passed(): TaintValidationResult {
            return TaintValidationResult(passed = true)
        }
        
        fun failed(violations: List<TaintViolation>): TaintValidationResult {
            return TaintValidationResult(
                passed = false,
                violations = violations
            )
        }
    }
}

/**
 * Taint违规记录
 */
data class TaintViolation(
    /**
     * 参数名
     */
    val parameterName: String,
    
    /**
     * 实际来源
     */
    val actualSource: ArgumentSource,
    
    /**
     * 要求的最低来源
     */
    val requiredSource: ArgumentSource,
    
    /**
     * 违规原因
     */
    val reason: String,
    
    /**
     * 是否为警告（非强制规则）
     */
    val isWarning: Boolean = false
) {
    override fun toString(): String {
        val level = if (isWarning) "WARNING" else "VIOLATION"
        return "[$level] Parameter '$parameterName' requires $requiredSource but got $actualSource: $reason"
    }
}

/**
 * Taint规则集
 * 
 * 为每个动作定义参数来源要求
 */
object TaintRuleSet {
    private val rules = mutableMapOf<String, List<TaintRule>>()
    
    /**
     * 注册动作的taint规则
     */
    fun registerRules(actionName: String, taintRules: List<TaintRule>) {
        rules[actionName] = taintRules
    }
    
    /**
     * 获取动作的taint规则
     */
    fun getRules(actionName: String): List<TaintRule> {
        return rules[actionName] ?: emptyList()
    }
    
    /**
     * 检查动作是否有taint规则
     */
    fun hasRules(actionName: String): Boolean {
        return rules.containsKey(actionName)
    }
    
    /**
     * 初始化默认规则
     */
    fun initializeDefaultRules() {
        // 通知回复：reply_text必须USER源
        registerRules("notification.reply", listOf(
            TaintRule(
                parameterName = "reply_text",
                requiredSource = ArgumentSource.USER,
                reason = "Reply text must come from user input to prevent auto-reply hijacking",
                enforce = true
            )
        ))
        
        // 粘贴板写入：text必须USER源
        registerRules("clipboard.write", listOf(
            TaintRule(
                parameterName = "text",
                requiredSource = ArgumentSource.USER,
                reason = "Clipboard write must use user-sourced text to prevent data poisoning",
                enforce = true
            )
        ))
        
        // 内容插入：values必须USER源
        registerRules("content.insert", listOf(
            TaintRule(
                parameterName = "values",
                requiredSource = ArgumentSource.USER,
                reason = "Insert values must come from user input to prevent data injection",
                enforce = true
            )
        ))
        
        // 内容更新：values必须USER源
        registerRules("content.update", listOf(
            TaintRule(
                parameterName = "values",
                requiredSource = ArgumentSource.USER,
                reason = "Update values must come from user input to prevent data tampering",
                enforce = true
            )
        ))
        
        // 输入操作：一般允许TRUSTED源（大脑规划）
        registerRules("input.type", listOf(
            TaintRule(
                parameterName = "text",
                requiredSource = ArgumentSource.TRUSTED,
                reason = "Type text should come from trusted source (brain planner)",
                enforce = true
            )
        ))
        
        // Activity启动：允许TRUSTED源
        registerRules("activity.launch", listOf(
            TaintRule(
                parameterName = "package_name",
                requiredSource = ArgumentSource.TRUSTED,
                reason = "Package name must be from trusted source to prevent app hijacking",
                enforce = true
            )
        ))
        
        // 策略激活：需要USER确认
        registerRules("policy.activate_suggestion", listOf(
            TaintRule(
                parameterName = "user_approved",
                requiredSource = ArgumentSource.USER,
                reason = "Policy activation requires explicit user approval",
                enforce = true
            ),
            TaintRule(
                parameterName = "scope",
                requiredSource = ArgumentSource.USER,
                reason = "Activation scope must be user-selected",
                enforce = true
            )
        ))
        
        // 备忘录录音：需要USER确认
        registerRules("sys.audio.record_memo", listOf(
            TaintRule(
                parameterName = "user_confirmed",
                requiredSource = ArgumentSource.USER,
                reason = "Recording requires user confirmation within 3 seconds",
                enforce = true
            )
        ))
    }
    
    /**
     * 清空规则（仅测试用）
     */
    fun clearForTesting() {
        rules.clear()
    }
}

/**
 * 意图一致性检查器
 * 
 * 检查动作参数是否与用户原始意图一致，防止意图偏移攻击
 */
object IntentConsistencyChecker {
    /**
     * 检查参数是否与意图一致
     * 
     * @param actionName 动作名
     * @param arguments 参数
     * @param originalIntent 用户原始意图描述
     * @return 是否一致
     */
    fun checkConsistency(
        actionName: String,
        arguments: Map<String, Any>,
        originalIntent: String?
    ): ConsistencyCheckResult {
        // 如果没有原始意图，跳过检查
        if (originalIntent.isNullOrBlank()) {
            return ConsistencyCheckResult(consistent = true)
        }
        
        // TODO: 实现意图一致性检查逻辑
        // 1. 提取意图关键信息（实体/动作/目标）
        // 2. 与参数进行语义匹配
        // 3. 检测异常偏移（如：意图是"查询"但动作是"删除"）
        
        // 暂时返回一致（待实现）
        return ConsistencyCheckResult(consistent = true)
    }
}

/**
 * 一致性检查结果
 */
data class ConsistencyCheckResult(
    val consistent: Boolean,
    val reason: String? = null
)
