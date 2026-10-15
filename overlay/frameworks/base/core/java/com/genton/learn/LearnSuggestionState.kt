package com.genton.learn

/**
 * Learn 建议状态枚举
 * 
 * 状态转换：
 * - suggestion → active (用户激活)
 * - suggestion → discarded (用户拒绝)
 * - active → discarded (冷静期内撤回，或7天后手动停用)
 * 
 * 注意：
 * - suggestion: 不参与裁决，仅作为候选展示
 * - active: 已激活，参与策略裁决
 * - discarded: 已丢弃，不再展示和裁决
 */
enum class LearnSuggestionState {
    /**
     * 建议态：待用户决策
     * - 展示在 UI 中供用户激活/拒绝
     * - 不参与策略裁决
     * - 7天抑制窗内不重复生成
     */
    SUGGESTION,
    
    /**
     * 激活态：用户已激活
     * - 参与策略裁决（作为 user. 规则）
     * - 7天冷静期内可撤回
     * - 记录命中统计
     */
    ACTIVE,
    
    /**
     * 丢弃态：用户拒绝或撤回
     * - 不参与裁决
     * - 触发抑制窗（7天内不再建议同模板）
     * - 保留审计记录
     */
    DISCARDED
}

/**
 * Learn 建议持久化数据
 * 
 * 字段说明：
 * - suggestionId: 建议唯一标识（learn.notify.<template>-<hash>）
 * - templateId: 模板 ID（sender-frequent-p1/category-digest/ad-fold/spam-l3/service-prefix-l3）
 * - state: 当前状态
 * - rule: 完整规则内容（JSON 序列化的 PolicyRule）
 * - confidence: 置信度 (0-1)
 * - evidenceCount: 证据样本数
 * - createdAtMs: 建议生成时间
 * - activatedAtMs: 激活时间（仅 active 状态）
 * - discardedAtMs: 丢弃时间（仅 discarded 状态）
 * - coolDownUntilMs: 冷静期截止时间（激活后7天）
 * - hitCount: 命中次数统计（仅 active 状态）
 * - lastHitAtMs: 最后命中时间
 * - userRuleId: 激活后对应的 user. 规则 ID（user.<src>.learned-<hash>）
 * - discardReason: 丢弃原因（用户反馈）
 */
data class LearnSuggestionRecord(
    val suggestionId: String,
    val templateId: String,
    val domain: String,                  // notification / call_gate
    val state: LearnSuggestionState,
    val ruleJson: String,                // PolicyRule 的 JSON 序列化
    val title: String,                   // UI 展示标题
    val description: String,             // UI 展示说明
    val confidence: Float,
    val evidenceCount: Int,
    val createdAtMs: Long,
    val activatedAtMs: Long? = null,
    val discardedAtMs: Long? = null,
    val coolDownUntilMs: Long? = null,   // 激活后 7 天
    val hitCount: Int = 0,
    val lastHitAtMs: Long? = null,
    val userRuleId: String? = null,      // 激活后生成的 user. 规则 ID
    val discardReason: String? = null
) {
    /**
     * 是否在冷静期内（激活后7天）
     */
    fun isInCoolDownPeriod(): Boolean {
        if (state != LearnSuggestionState.ACTIVE) return false
        val coolDown = coolDownUntilMs ?: return false
        return System.currentTimeMillis() < coolDown
    }
    
    /**
     * 冷静期剩余天数
     */
    fun coolDownRemainingDays(): Int {
        if (!isInCoolDownPeriod()) return 0
        val remainingMs = coolDownUntilMs!! - System.currentTimeMillis()
        return (remainingMs / (24 * 60 * 60 * 1000L)).toInt() + 1
    }
}

/**
 * 状态转换请求
 */
sealed class LearnStateTransition {
    /**
     * 激活建议
     * 
     * @param suggestionId 建议 ID
     * @param userRuleId 生成的 user. 规则 ID（由 PolicyEngine 生成）
     * @param activatedAtMs 激活时间
     */
    data class Activate(
        val suggestionId: String,
        val userRuleId: String,
        val activatedAtMs: Long = System.currentTimeMillis()
    ) : LearnStateTransition()
    
    /**
     * 拒绝建议
     * 
     * @param suggestionId 建议 ID
     * @param reason 拒绝原因（可选）
     * @param discardedAtMs 拒绝时间
     */
    data class Reject(
        val suggestionId: String,
        val reason: String? = null,
        val discardedAtMs: Long = System.currentTimeMillis()
    ) : LearnStateTransition()
    
    /**
     * 撤回激活（冷静期内）
     * 
     * @param suggestionId 建议 ID
     * @param reason 撤回原因（可选）
     * @param discardedAtMs 撤回时间
     */
    data class Revoke(
        val suggestionId: String,
        val reason: String? = null,
        val discardedAtMs: Long = System.currentTimeMillis()
    ) : LearnStateTransition()
    
    /**
     * 记录命中
     * 
     * @param suggestionId 建议 ID
     * @param hitAtMs 命中时间
     */
    data class RecordHit(
        val suggestionId: String,
        val hitAtMs: Long = System.currentTimeMillis()
    ) : LearnStateTransition()
}

/**
 * 状态转换结果
 */
sealed class LearnStateTransitionResult {
    data class Success(val record: LearnSuggestionRecord) : LearnStateTransitionResult()
    
    sealed class Failure : LearnStateTransitionResult() {
        data class NotFound(val suggestionId: String) : Failure()
        data class InvalidState(val currentState: LearnSuggestionState, val expectedState: LearnSuggestionState) : Failure()
        data class CoolDownExpired(val suggestionId: String) : Failure()
        data class DatabaseError(val message: String) : Failure()
    }
}
