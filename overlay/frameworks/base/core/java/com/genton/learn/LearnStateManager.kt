package com.genton.learn

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Learn 状态管理器
 * 
 * 职责：
 * 1. 管理 learn 建议的状态转换（suggestion → active/discarded）
 * 2. 维护 7 天冷静期（激活后可撤回）
 * 3. 维护抑制窗（拒绝后 7 天不再建议同模板）
 * 4. 记录命中统计（active 规则被策略引擎命中）
 * 5. 触发状态变更事件（onSuggestionAdded/onStateChanged）
 * 
 * 使用场景：
 * - C2.1 IntentLearningService 生成新建议时调用 addSuggestion
 * - D4.2 UI 用户激活/拒绝时调用 activateSuggestion/rejectSuggestion
 * - A7.1 PolicyEngine 命中规则时调用 recordHit
 * - D15 表单提交 activate_suggestion 时调用 activateSuggestion
 */
class LearnStateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LearnStateManager"
        
        // 冷静期：7 天
        private const val COOL_DOWN_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L
        
        // 抑制窗：7 天
        private const val SUPPRESSION_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
        
        // 建议上限（防止无限积累）
        private const val MAX_PENDING_SUGGESTIONS = 20
    }
    
    private val database = LearnSuggestionDatabase.getInstance(context)
    
    // 状态变更监听器列表
    private val listeners = CopyOnWriteArrayList<LearnStateListener>()
    
    // ========== 监听器管理 ==========
    
    /**
     * 注册状态变更监听器
     */
    fun registerListener(listener: LearnStateListener) {
        listeners.add(listener)
        Log.d(TAG, "Registered listener: ${listener::class.simpleName}")
    }
    
    /**
     * 注销状态变更监听器
     */
    fun unregisterListener(listener: LearnStateListener) {
        listeners.remove(listener)
        Log.d(TAG, "Unregistered listener: ${listener::class.simpleName}")
    }
    
    // ========== 建议管理 ==========
    
    /**
     * 添加新建议
     * 
     * @param suggestion C2.1 生成的 LearnSuggestion
     * @return 是否成功添加
     */
    fun addSuggestion(suggestion: LearnSuggestion): Boolean {
        // 1. 检查是否已存在
        if (database.getSuggestionById(suggestion.suggestionId) != null) {
            Log.w(TAG, "Suggestion already exists: ${suggestion.suggestionId}")
            return false
        }
        
        // 2. 检查抑制窗（同模板 7 天内被拒绝过）
        if (isTemplateInSuppressionWindow(suggestion.templateId)) {
            Log.i(TAG, "Template ${suggestion.templateId} is in suppression window, skipping")
            return false
        }
        
        // 3. 检查待处理建议数量上限
        val pendingCount = database.getPendingSuggestions().size
        if (pendingCount >= MAX_PENDING_SUGGESTIONS) {
            Log.w(TAG, "Too many pending suggestions ($pendingCount), skipping")
            return false
        }
        
        // 4. 转换为数据库记录
        val record = LearnSuggestionRecord(
            suggestionId = suggestion.suggestionId,
            templateId = suggestion.templateId,
            domain = suggestion.rule.domain,
            state = LearnSuggestionState.SUGGESTION,
            ruleJson = ruleToJson(suggestion.rule),
            title = suggestion.title,
            description = suggestion.description,
            confidence = suggestion.confidence,
            evidenceCount = suggestion.evidenceCount,
            createdAtMs = suggestion.createdAtMs
        )
        
        // 5. 插入数据库
        if (!database.insertSuggestion(record)) {
            Log.e(TAG, "Failed to insert suggestion: ${suggestion.suggestionId}")
            return false
        }
        
        // 6. 触发事件
        notifyListeners { it.onSuggestionAdded(record) }
        
        Log.i(TAG, "Added suggestion: ${suggestion.suggestionId}, template=${suggestion.templateId}, confidence=${suggestion.confidence}")
        return true
    }
    
    /**
     * 激活建议
     * 
     * @param suggestionId 建议 ID
     * @param userRuleId 生成的 user. 规则 ID（由调用方提供）
     * @return 状态转换结果
     */
    fun activateSuggestion(suggestionId: String, userRuleId: String): LearnStateTransitionResult {
        val record = database.getSuggestionById(suggestionId)
            ?: return LearnStateTransitionResult.Failure.NotFound(suggestionId)
        
        // 检查当前状态
        if (record.state != LearnSuggestionState.SUGGESTION) {
            return LearnStateTransitionResult.Failure.InvalidState(
                record.state,
                LearnSuggestionState.SUGGESTION
            )
        }
        
        // 状态转换
        val nowMs = System.currentTimeMillis()
        val coolDownUntilMs = nowMs + COOL_DOWN_PERIOD_MS
        
        val updatedRecord = record.copy(
            state = LearnSuggestionState.ACTIVE,
            activatedAtMs = nowMs,
            coolDownUntilMs = coolDownUntilMs,
            userRuleId = userRuleId
        )
        
        if (!database.updateSuggestion(updatedRecord)) {
            return LearnStateTransitionResult.Failure.DatabaseError("Failed to update suggestion")
        }
        
        // 触发事件
        notifyListeners { it.onSuggestionActivated(updatedRecord) }
        
        Log.i(TAG, "Activated suggestion: $suggestionId → $userRuleId, coolDown until ${coolDownUntilMs}")
        return LearnStateTransitionResult.Success(updatedRecord)
    }
    
    /**
     * 拒绝建议（用户主动拒绝）
     * 
     * @param suggestionId 建议 ID
     * @param reason 拒绝原因（可选）
     * @return 状态转换结果
     */
    fun rejectSuggestion(suggestionId: String, reason: String? = null): LearnStateTransitionResult {
        val record = database.getSuggestionById(suggestionId)
            ?: return LearnStateTransitionResult.Failure.NotFound(suggestionId)
        
        // 检查当前状态
        if (record.state != LearnSuggestionState.SUGGESTION) {
            return LearnStateTransitionResult.Failure.InvalidState(
                record.state,
                LearnSuggestionState.SUGGESTION
            )
        }
        
        // 状态转换
        val nowMs = System.currentTimeMillis()
        val updatedRecord = record.copy(
            state = LearnSuggestionState.DISCARDED,
            discardedAtMs = nowMs,
            discardReason = reason
        )
        
        if (!database.updateSuggestion(updatedRecord)) {
            return LearnStateTransitionResult.Failure.DatabaseError("Failed to update suggestion")
        }
        
        // 触发事件
        notifyListeners { it.onSuggestionRejected(updatedRecord, reason) }
        
        Log.i(TAG, "Rejected suggestion: $suggestionId, reason=$reason")
        return LearnStateTransitionResult.Success(updatedRecord)
    }
    
    /**
     * 撤回激活（冷静期内）
     * 
     * @param suggestionId 建议 ID
     * @param reason 撤回原因（可选）
     * @return 状态转换结果
     */
    fun revokeSuggestion(suggestionId: String, reason: String? = null): LearnStateTransitionResult {
        val record = database.getSuggestionById(suggestionId)
            ?: return LearnStateTransitionResult.Failure.NotFound(suggestionId)
        
        // 检查当前状态
        if (record.state != LearnSuggestionState.ACTIVE) {
            return LearnStateTransitionResult.Failure.InvalidState(
                record.state,
                LearnSuggestionState.ACTIVE
            )
        }
        
        // 检查是否在冷静期内
        if (!record.isInCoolDownPeriod()) {
            return LearnStateTransitionResult.Failure.CoolDownExpired(suggestionId)
        }
        
        // 状态转换
        val nowMs = System.currentTimeMillis()
        val updatedRecord = record.copy(
            state = LearnSuggestionState.DISCARDED,
            discardedAtMs = nowMs,
            discardReason = reason
        )
        
        if (!database.updateSuggestion(updatedRecord)) {
            return LearnStateTransitionResult.Failure.DatabaseError("Failed to update suggestion")
        }
        
        // 触发事件
        notifyListeners { it.onSuggestionRevoked(updatedRecord, reason) }
        
        Log.i(TAG, "Revoked suggestion: $suggestionId, reason=$reason")
        return LearnStateTransitionResult.Success(updatedRecord)
    }
    
    /**
     * 记录规则命中（由 PolicyEngine 调用）
     * 
     * @param suggestionId 建议 ID
     * @return 是否成功记录
     */
    fun recordHit(suggestionId: String): Boolean {
        val record = database.getSuggestionById(suggestionId)
        
        if (record == null) {
            Log.w(TAG, "Cannot record hit: suggestion not found: $suggestionId")
            return false
        }
        
        // 只统计 ACTIVE 状态的命中
        if (record.state != LearnSuggestionState.ACTIVE) {
            Log.w(TAG, "Cannot record hit: suggestion not active: $suggestionId, state=${record.state}")
            return false
        }
        
        val nowMs = System.currentTimeMillis()
        val updatedRecord = record.copy(
            hitCount = record.hitCount + 1,
            lastHitAtMs = nowMs
        )
        
        if (!database.updateSuggestion(updatedRecord)) {
            Log.e(TAG, "Failed to record hit: $suggestionId")
            return false
        }
        
        // 触发事件（低优先级，不阻塞）
        notifyListeners { it.onSuggestionHit(updatedRecord) }
        
        Log.d(TAG, "Recorded hit: $suggestionId, hitCount=${updatedRecord.hitCount}")
        return true
    }
    
    // ========== 查询接口 ==========
    
    /**
     * 获取所有待处理建议
     */
    fun getPendingSuggestions(): List<LearnSuggestionRecord> {
        return database.getPendingSuggestions()
    }
    
    /**
     * 获取所有激活的建议
     */
    fun getActiveSuggestions(): List<LearnSuggestionRecord> {
        return database.getActiveSuggestions()
    }
    
    /**
     * 获取冷静期内的建议
     */
    fun getSuggestionsInCoolDown(): List<LearnSuggestionRecord> {
        return database.getSuggestionsInCoolDown()
    }
    
    /**
     * 根据 ID 获取建议
     */
    fun getSuggestionById(suggestionId: String): LearnSuggestionRecord? {
        return database.getSuggestionById(suggestionId)
    }
    
    /**
     * 获取状态统计
     */
    fun getStateStatistics(): Map<LearnSuggestionState, Int> {
        return database.getStateStatistics()
    }
    
    // ========== 抑制窗管理 ==========
    
    /**
     * 检查模板是否在抑制窗内
     * 
     * 逻辑：如果同模板在 7 天内被拒绝过，则返回 true
     */
    fun isTemplateInSuppressionWindow(templateId: String): Boolean {
        val lastDiscardedMs = database.getLastDiscardedTimeForTemplate(templateId)
            ?: return false
        
        val nowMs = System.currentTimeMillis()
        val isInWindow = (nowMs - lastDiscardedMs) < SUPPRESSION_WINDOW_MS
        
        if (isInWindow) {
            val remainingDays = ((SUPPRESSION_WINDOW_MS - (nowMs - lastDiscardedMs)) / (24 * 60 * 60 * 1000L)).toInt() + 1
            Log.d(TAG, "Template $templateId in suppression window, remaining $remainingDays days")
        }
        
        return isInWindow
    }
    
    // ========== 维护任务 ==========
    
    /**
     * 清理过期建议（定期任务）
     * 
     * 删除 DISCARDED 状态且超过 30 天的记录
     */
    fun cleanupExpiredSuggestions(retentionDays: Int = 30): Int {
        return database.cleanupExpiredSuggestions(retentionDays)
    }
    
    // ========== 私有辅助方法 ==========
    
    /**
     * 通知所有监听器
     */
    private fun notifyListeners(action: (LearnStateListener) -> Unit) {
        for (listener in listeners) {
            try {
                action(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener: ${listener::class.simpleName}", e)
            }
        }
    }
    
    /**
     * PolicyRule 转 JSON
     */
    private fun ruleToJson(rule: PolicyRule): String {
        val json = JSONObject()
        json.put("id", rule.id)
        json.put("namespace", rule.namespace)
        json.put("domain", rule.domain)
        json.put("effect", rule.effect)
        json.put("whenConditions", JSONObject(rule.whenConditions))
        json.put("thenActions", JSONObject(rule.thenActions))
        json.put("priority", rule.priority)
        rule.meta?.let { json.put("meta", JSONObject(it)) }
        return json.toString()
    }
}

/**
 * Learn 状态变更监听器
 * 
 * 实现方：
 * - D4.2 UI: 更新 UI 展示
 * - A7.1 PolicyEngine: 重新加载规则集
 * - IntentLearningService: 同步到候选池
 */
interface LearnStateListener {
    /**
     * 新建议添加
     */
    fun onSuggestionAdded(suggestion: LearnSuggestionRecord) {}
    
    /**
     * 建议激活
     */
    fun onSuggestionActivated(suggestion: LearnSuggestionRecord) {}
    
    /**
     * 建议拒绝
     */
    fun onSuggestionRejected(suggestion: LearnSuggestionRecord, reason: String?) {}
    
    /**
     * 建议撤回
     */
    fun onSuggestionRevoked(suggestion: LearnSuggestionRecord, reason: String?) {}
    
    /**
     * 规则命中（仅 ACTIVE 状态）
     */
    fun onSuggestionHit(suggestion: LearnSuggestionRecord) {}
}
