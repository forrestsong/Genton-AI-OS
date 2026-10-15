package com.ainative.learn

import android.content.Context
import android.util.Log
import com.ainative.audit.AuditEvent
import com.ainative.audit.IAuditService
import com.ainative.policy.PolicyRule
import com.ainative.semantics.Entity
import com.ainative.semantics.IEntityQueryService
import java.util.concurrent.ConcurrentHashMap

/**
 * C2.1 意图学习生成器
 * 
 * 职责：
 * 1. 从用户行为模式生成 learn. 候选规则（notification/call_gate 域）
 * 2. 模板槽位生成（禁自由组合 when/effect）
 * 3. 置信度门限过滤（>= 0.7 才生成建议）
 * 4. 抑制窗管理（同源 7 天内不重复建议）
 * 
 * 设计原则（D17 §3、D10 learn 层）：
 * - learn. 规则从"模板槽位"生成（禁自由组合 when/effect）
 * - 生成的规则 state=suggestion（不参与裁决）
 * - 需用户表单确认（needsUserApproval=true）
 * - 激活后复制为 user.<src>.learned-<hash>
 * 
 * 模板清单（D17 §7.1/§7.2）：
 * - learn.notify.sender-frequent-p1: 高频非联系人发送方 → P1
 * - learn.notify.category-digest: 特定类别 → P1 摘要
 * - learn.callgate.spam-l3: 高骚扰评分 → L3 记录
 * - learn.callgate.service-prefix-l3: 特定号段 → L3 记录
 * 
 * 依赖关系：
 * - 前置：C1.1 DSL 规则目录 v1、C4.1 意图分类模型
 * - 消费：C2.2 learn 状态机、D4.2 治理 UI
 * 
 * 版本：v1（A5 期）
 * 
 * @since A5
 */
class IntentLearningGenerator(
    private val context: Context,
    private val entityService: IEntityQueryService?,
    private val auditService: IAuditService?
) {
    
    companion object {
        private const val TAG = "IntentLearningGenerator"
        
        // 置信度门限
        private const val MIN_CONFIDENCE_THRESHOLD = 0.7f  // 最低置信度（< 0.7 不生成建议）
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.85f // 高置信度（≥ 0.85 优先推送）
        
        // 抑制窗（7 天）
        private const val SUPPRESSION_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
        
        // 候选建议上限
        private const val MAX_PENDING_SUGGESTIONS = 10  // 最多 10 个待审批建议
        
        // 学习样本最小数量
        private const val MIN_SAMPLE_COUNT = 3  // 至少 3 次相似行为才生成建议
    }
    
    // 抑制窗记录（模板 ID → 上次建议时间）
    private val suppressionMap = ConcurrentHashMap<String, Long>()
    
    /**
     * 从通知交互历史生成 learn 候选规则
     * 
     * @param history 用户通知交互历史（最近 30 天）
     * @return 生成的候选规则列表
     */
    fun generateNotificationSuggestions(history: List<NotificationInteraction>): List<LearnSuggestion> {
        val suggestions = mutableListOf<LearnSuggestion>()
        
        // 模板 1: 高频非联系人发送方 → P1 摘要
        val senderFrequentSuggestion = generateSenderFrequentP1(history)
        if (senderFrequentSuggestion != null) {
            suggestions.add(senderFrequentSuggestion)
        }
        
        // 模板 2: 特定类别批量 → P1 摘要
        val categoryDigestSuggestion = generateCategoryDigest(history)
        if (categoryDigestSuggestion != null) {
            suggestions.add(categoryDigestSuggestion)
        }
        
        // 模板 3: 营销/广告批量 → P2 折叠
        val adFoldSuggestion = generateAdFold(history)
        if (adFoldSuggestion != null) {
            suggestions.add(adFoldSuggestion)
        }
        
        Log.i(TAG, "Generated ${suggestions.size} notification suggestions from ${history.size} interactions")
        return suggestions
    }
    
    /**
     * 从来电交互历史生成 learn 候选规则
     * 
     * @param history 用户来电交互历史（最近 30 天）
     * @return 生成的候选规则列表
     */
    fun generateCallGateSuggestions(history: List<CallInteraction>): List<LearnSuggestion> {
        val suggestions = mutableListOf<LearnSuggestion>()
        
        // 模板 1: 高骚扰评分号码 → L3 记录
        val spamL3Suggestion = generateSpamL3(history)
        if (spamL3Suggestion != null) {
            suggestions.add(spamL3Suggestion)
        }
        
        // 模板 2: 特定号段（95xxx）→ L3 记录
        val servicePrefixSuggestion = generateServicePrefixL3(history)
        if (servicePrefixSuggestion != null) {
            suggestions.add(servicePrefixSuggestion)
        }
        
        Log.i(TAG, "Generated ${suggestions.size} call gate suggestions from ${history.size} interactions")
        return suggestions
    }
    
    /**
     * 检查候选规则是否应该被抑制
     * 
     * @param templateId 模板 ID
     * @return true 如果在抑制窗内（应该抑制）
     */
    fun isSuppressed(templateId: String): Boolean {
        val lastSuggestionTime = suppressionMap[templateId] ?: return false
        val now = System.currentTimeMillis()
        val elapsed = now - lastSuggestionTime
        
        return elapsed < SUPPRESSION_WINDOW_MS
    }
    
    /**
     * 记录建议生成（更新抑制窗）
     * 
     * @param templateId 模板 ID
     */
    fun recordSuggestion(templateId: String) {
        suppressionMap[templateId] = System.currentTimeMillis()
    }
    
    // ========== 通知域模板 ==========
    
    /**
     * 模板 1: 高频非联系人发送方 → P1 摘要
     * 
     * 触发条件：
     * - 同一发送方（非联系人）发送 ≥ 5 条通知
     * - 用户从未打开过该通知
     * - 近 30 天内持续发送
     * 
     * 生成规则：
     * ```yaml
     * policy:
     *   id: learn.notify.sender-frequent-p1-<hash>
     *   domain: notification
     *   effect: route_p1_digest
     *   when:
     *     event: notification.posted
     *     senderDomain: <domain>
     *     senderTrust: APP
     *   then:
     *     route: P1_AGENT_SUMMARY
     *     summaryModel: local_tiny
     *   _meta:
     *     state: suggestion
     *     source: intent_learning
     *     needsUserApproval: true
     *     proposedPriority: 100
     * ```
     */
    private fun generateSenderFrequentP1(history: List<NotificationInteraction>): LearnSuggestion? {
        val templateId = "sender-frequent-p1"
        
        // 检查抑制窗
        if (isSuppressed(templateId)) {
            Log.d(TAG, "Template $templateId suppressed (within 7-day window)")
            return null
        }
        
        // 统计发送方频次（非联系人）
        val senderStats = mutableMapOf<String, SenderStat>()
        
        for (interaction in history) {
            if (interaction.senderTrust == "CONTACT") {
                continue  // 跳过联系人
            }
            
            val domain = interaction.senderDomain
            val stat = senderStats.getOrPut(domain) {
                SenderStat(domain, 0, 0)
            }
            
            stat.totalCount++
            if (!interaction.userOpened) {
                stat.neverOpenedCount++
            }
        }
        
        // 查找高频且从未打开的发送方
        val candidates = senderStats.values.filter { stat ->
            stat.totalCount >= MIN_SAMPLE_COUNT &&  // 至少 3 次
            stat.neverOpenedCount == stat.totalCount  // 从未打开
        }.sortedByDescending { it.totalCount }
        
        if (candidates.isEmpty()) {
            return null
        }
        
        val topSender = candidates.first()
        val confidence = calculateConfidence(
            sampleCount = topSender.totalCount,
            consistency = 1.0f  // 100% 从未打开
        )
        
        if (confidence < MIN_CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Confidence $confidence < $MIN_CONFIDENCE_THRESHOLD, skipping suggestion")
            return null
        }
        
        // 生成建议
        val suggestionId = "learn.notify.sender-frequent-p1-${topSender.domain.hashCode().toString(16)}"
        val ruleTitle = "高频通知自动摘要"
        val ruleDescription = "来自 ${topSender.domain} 的通知（共 ${topSender.totalCount} 条）自动折叠到简报卡，您从未打开过这些通知。"
        
        val rule = PolicyRule(
            id = suggestionId,
            namespace = "learn",
            domain = "notification",
            effect = "route_p1_digest",
            whenConditions = mapOf(
                "event" to "notification.posted",
                "senderDomain" to topSender.domain,
                "senderTrust" to "APP"
            ),
            thenActions = mapOf(
                "route" to "P1_AGENT_SUMMARY",
                "summaryModel" to "local_tiny"
            ),
            priority = 100,
            meta = mapOf(
                "state" to "suggestion",
                "source" to "intent_learning",
                "needsUserApproval" to true,
                "proposedPriority" to 100
            )
        )
        
        recordSuggestion(templateId)
        
        return LearnSuggestion(
            suggestionId = suggestionId,
            templateId = templateId,
            title = ruleTitle,
            description = ruleDescription,
            rule = rule,
            confidence = confidence,
            evidenceCount = topSender.totalCount,
            createdAtMs = System.currentTimeMillis()
        )
    }
    
    /**
     * 模板 2: 特定类别批量 → P1 摘要
     * 
     * 触发条件：
     * - 同一类别（NEWS/SOCIAL_UPDATES）通知 ≥ 10 条/天
     * - 用户平均打开率 < 20%
     * 
     * 生成规则：
     * ```yaml
     * policy:
     *   id: learn.notify.category-digest-<category>
     *   domain: notification
     *   effect: route_p1_digest
     *   when:
     *     event: notification.posted
     *     category: <category>
     *     classifierConfidence: ">= 0.6"
     *   then:
     *     route: P1_AGENT_SUMMARY
     *     aggregateWindowMs: 1800000  # 30 分钟
     * ```
     */
    private fun generateCategoryDigest(history: List<NotificationInteraction>): LearnSuggestion? {
        val templateId = "category-digest"
        
        if (isSuppressed(templateId)) {
            return null
        }
        
        // 统计类别频次
        val categoryStats = mutableMapOf<String, CategoryStat>()
        
        for (interaction in history) {
            val category = interaction.category ?: continue
            
            // 只处理资讯/社交类（高频类别）
            if (category !in listOf("NEWS", "SOCIAL_UPDATES", "APP_UPDATES")) {
                continue
            }
            
            val stat = categoryStats.getOrPut(category) {
                CategoryStat(category, 0, 0)
            }
            
            stat.totalCount++
            if (interaction.userOpened) {
                stat.openedCount++
            }
        }
        
        // 查找高频且低打开率的类别
        val candidates = categoryStats.values.filter { stat ->
            stat.totalCount >= 10 &&  // 至少 10 条
            stat.openRate < 0.2f      // 打开率 < 20%
        }.sortedByDescending { it.totalCount }
        
        if (candidates.isEmpty()) {
            return null
        }
        
        val topCategory = candidates.first()
        val confidence = calculateConfidence(
            sampleCount = topCategory.totalCount,
            consistency = 1.0f - topCategory.openRate  // 未打开率
        )
        
        if (confidence < MIN_CONFIDENCE_THRESHOLD) {
            return null
        }
        
        val suggestionId = "learn.notify.category-digest-${topCategory.category.lowercase()}"
        val categoryName = when (topCategory.category) {
            "NEWS" -> "资讯"
            "SOCIAL_UPDATES" -> "社交动态"
            "APP_UPDATES" -> "应用更新"
            else -> topCategory.category
        }
        
        val rule = PolicyRule(
            id = suggestionId,
            namespace = "learn",
            domain = "notification",
            effect = "route_p1_digest",
            whenConditions = mapOf(
                "event" to "notification.posted",
                "category" to topCategory.category,
                "classifierConfidence" to ">= 0.6"
            ),
            thenActions = mapOf(
                "route" to "P1_AGENT_SUMMARY",
                "aggregateWindowMs" to "1800000"  // 30 分钟聚合
            ),
            priority = 100,
            meta = mapOf(
                "state" to "suggestion",
                "source" to "intent_learning",
                "needsUserApproval" to true,
                "proposedPriority" to 100
            )
        )
        
        recordSuggestion(templateId)
        
        return LearnSuggestion(
            suggestionId = suggestionId,
            templateId = templateId,
            title = "${categoryName}通知自动摘要",
            description = "近期收到 ${topCategory.totalCount} 条${categoryName}通知，打开率仅 ${(topCategory.openRate * 100).toInt()}%，建议自动折叠到简报卡。",
            rule = rule,
            confidence = confidence,
            evidenceCount = topCategory.totalCount,
            createdAtMs = System.currentTimeMillis()
        )
    }
    
    /**
     * 模板 3: 营销/广告批量 → P2 折叠
     * 
     * 触发条件：
     * - MARKETING/ADVERTISING 类别通知 ≥ 5 条
     * - 用户打开率 < 10%
     * 
     * 生成规则：
     * ```yaml
     * policy:
     *   id: learn.notify.ad-fold-<hash>
     *   domain: notification
     *   effect: route_p2_agent_act
     *   when:
     *     event: notification.posted
     *     category: [MARKETING, ADVERTISING]
     *     classifierConfidence: ">= 0.7"
     *   then:
     *     route: P2_AGENT_HANDLE
     *     agentAct: fold
     *     suppressBadge: true
     *     dailyHandleCap: 20
     * ```
     */
    private fun generateAdFold(history: List<NotificationInteraction>): LearnSuggestion? {
        val templateId = "ad-fold"
        
        if (isSuppressed(templateId)) {
            return null
        }
        
        // 统计营销/广告通知
        var totalAdCount = 0
        var openedAdCount = 0
        
        for (interaction in history) {
            val category = interaction.category ?: continue
            
            if (category in listOf("MARKETING", "ADVERTISING")) {
                totalAdCount++
                if (interaction.userOpened) {
                    openedAdCount++
                }
            }
        }
        
        if (totalAdCount < MIN_SAMPLE_COUNT) {
            return null
        }
        
        val openRate = if (totalAdCount > 0) openedAdCount.toFloat() / totalAdCount else 0f
        
        if (openRate >= 0.1f) {
            // 打开率 >= 10%，说明用户还是关注部分营销内容
            return null
        }
        
        val confidence = calculateConfidence(
            sampleCount = totalAdCount,
            consistency = 1.0f - openRate
        )
        
        if (confidence < MIN_CONFIDENCE_THRESHOLD) {
            return null
        }
        
        val suggestionId = "learn.notify.ad-fold-${System.currentTimeMillis()}"
        
        val rule = PolicyRule(
            id = suggestionId,
            namespace = "learn",
            domain = "notification",
            effect = "route_p2_agent_act",
            whenConditions = mapOf(
                "event" to "notification.posted",
                "category" to listOf("MARKETING", "ADVERTISING"),
                "classifierConfidence" to ">= 0.7"
            ),
            thenActions = mapOf(
                "route" to "P2_AGENT_HANDLE",
                "agentAct" to "fold",
                "suppressBadge" to "true",
                "dailyHandleCap" to "20"
            ),
            priority = 90,
            meta = mapOf(
                "state" to "suggestion",
                "source" to "intent_learning",
                "needsUserApproval" to true,
                "proposedPriority" to 90
            )
        )
        
        recordSuggestion(templateId)
        
        return LearnSuggestion(
            suggestionId = suggestionId,
            templateId = templateId,
            title = "营销通知自动折叠",
            description = "近期收到 $totalAdCount 条营销/广告通知，打开率仅 ${(openRate * 100).toInt()}%，建议自动折叠。",
            rule = rule,
            confidence = confidence,
            evidenceCount = totalAdCount,
            createdAtMs = System.currentTimeMillis()
        )
    }
    
    // ========== 来电域模板 ==========
    
    /**
     * 模板 1: 高骚扰评分号码 → L3 记录
     * 
     * 触发条件：
     * - spamScore >= 0.85 的来电 ≥ 3 次
     * - 用户从未接听
     * 
     * 生成规则：
     * ```yaml
     * policy:
     *   id: learn.callgate.spam-l3-<hash>
     *   domain: call_gate
     *   effect: route_record_only
     *   when:
     *     call: incoming
     *     spamScore: ">= 0.85"
     *     callerClass: unknown
     *   then:
     *     route: RECORD_ONLY
     *     digestSuggestion: true
     * ```
     */
    private fun generateSpamL3(history: List<CallInteraction>): LearnSuggestion? {
        val templateId = "spam-l3"
        
        if (isSuppressed(templateId)) {
            return null
        }
        
        // 统计高骚扰评分来电
        var highSpamCount = 0
        var answeredCount = 0
        
        for (interaction in history) {
            if (interaction.spamScore >= 0.85f && interaction.callerClass == "unknown") {
                highSpamCount++
                if (interaction.userAnswered) {
                    answeredCount++
                }
            }
        }
        
        if (highSpamCount < MIN_SAMPLE_COUNT) {
            return null
        }
        
        val answerRate = if (highSpamCount > 0) answeredCount.toFloat() / highSpamCount else 0f
        
        if (answerRate > 0.1f) {
            // 接听率 > 10%，说明部分"骚扰"来电用户会接
            return null
        }
        
        val confidence = calculateConfidence(
            sampleCount = highSpamCount,
            consistency = 1.0f - answerRate
        )
        
        if (confidence < MIN_CONFIDENCE_THRESHOLD) {
            return null
        }
        
        val suggestionId = "learn.callgate.spam-l3-${System.currentTimeMillis()}"
        
        val rule = PolicyRule(
            id = suggestionId,
            namespace = "learn",
            domain = "call_gate",
            effect = "route_record_only",
            whenConditions = mapOf(
                "call" to "incoming",
                "spamScore" to ">= 0.85",
                "callerClass" to "unknown"
            ),
            thenActions = mapOf(
                "route" to "RECORD_ONLY",
                "digestSuggestion" to "true"
            ),
            priority = 120,
            meta = mapOf(
                "state" to "suggestion",
                "source" to "intent_learning",
                "needsUserApproval" to true,
                "proposedPriority" to 120
            )
        )
        
        recordSuggestion(templateId)
        
        return LearnSuggestion(
            suggestionId = suggestionId,
            templateId = templateId,
            title = "骚扰来电静默记录",
            description = "近期收到 $highSpamCount 个高骚扰评分来电，您从未接听，建议自动静默并记录。",
            rule = rule,
            confidence = confidence,
            evidenceCount = highSpamCount,
            createdAtMs = System.currentTimeMillis()
        )
    }
    
    /**
     * 模板 2: 特定号段（95xxx）→ L3 记录
     * 
     * 触发条件：
     * - 95xxx 号段来电 ≥ 3 次
     * - 用户接听率 < 20%
     * 
     * 生成规则：
     * ```yaml
     * policy:
     *   id: learn.callgate.service-prefix-l3-95
     *   domain: call_gate
     *   effect: route_record_only
     *   when:
     *     call: incoming
     *     numberPrefix: "95"
     *     callerClass: unknown
     *   then:
     *     route: RECORD_ONLY
     *     suggestVoicemail: true
     * ```
     */
    private fun generateServicePrefixL3(history: List<CallInteraction>): LearnSuggestion? {
        val templateId = "service-prefix-l3"
        
        if (isSuppressed(templateId)) {
            return null
        }
        
        // 统计 95xxx 号段来电
        var servicePrefixCount = 0
        var answeredCount = 0
        
        for (interaction in history) {
            if (interaction.numberPrefix == "95" && interaction.callerClass == "unknown") {
                servicePrefixCount++
                if (interaction.userAnswered) {
                    answeredCount++
                }
            }
        }
        
        if (servicePrefixCount < MIN_SAMPLE_COUNT) {
            return null
        }
        
        val answerRate = if (servicePrefixCount > 0) answeredCount.toFloat() / servicePrefixCount else 0f
        
        if (answerRate >= 0.2f) {
            // 接听率 >= 20%，说明用户会接部分服务号
            return null
        }
        
        val confidence = calculateConfidence(
            sampleCount = servicePrefixCount,
            consistency = 1.0f - answerRate
        )
        
        if (confidence < MIN_CONFIDENCE_THRESHOLD) {
            return null
        }
        
        val suggestionId = "learn.callgate.service-prefix-l3-95"
        
        val rule = PolicyRule(
            id = suggestionId,
            namespace = "learn",
            domain = "call_gate",
            effect = "route_record_only",
            whenConditions = mapOf(
                "call" to "incoming",
                "numberPrefix" to "95",
                "callerClass" to "unknown"
            ),
            thenActions = mapOf(
                "route" to "RECORD_ONLY",
                "suggestVoicemail" to "true"
            ),
            priority = 130,
            meta = mapOf(
                "state" to "suggestion",
                "source" to "intent_learning",
                "needsUserApproval" to true,
                "proposedPriority" to 130
            )
        )
        
        recordSuggestion(templateId)
        
        return LearnSuggestion(
            suggestionId = suggestionId,
            templateId = templateId,
            title = "服务号码静默记录",
            description = "近期收到 $servicePrefixCount 个 95 开头的服务号来电，接听率仅 ${(answerRate * 100).toInt()}%，建议自动静默并记录。",
            rule = rule,
            confidence = confidence,
            evidenceCount = servicePrefixCount,
            createdAtMs = System.currentTimeMillis()
        )
    }
    
    // ========== 工具方法 ==========
    
    /**
     * 计算置信度
     * 
     * 公式：confidence = min(1.0, (sampleCount / 10) * 0.3 + consistency * 0.7)
     * 
     * @param sampleCount 样本数量（越多越可信）
     * @param consistency 一致性（0-1，越高越可信）
     * @return 置信度 (0-1)
     */
    private fun calculateConfidence(sampleCount: Int, consistency: Float): Float {
        val sampleWeight = minOf(1.0f, sampleCount / 10.0f) * 0.3f
        val consistencyWeight = consistency * 0.7f
        return sampleWeight + consistencyWeight
    }
    
    // ========== 数据结构 ==========
    
    /**
     * 发送方统计
     */
    private data class SenderStat(
        val domain: String,
        var totalCount: Int,
        var neverOpenedCount: Int
    )
    
    /**
     * 类别统计
     */
    private data class CategoryStat(
        val category: String,
        var totalCount: Int,
        var openedCount: Int
    ) {
        val openRate: Float
            get() = if (totalCount > 0) openedCount.toFloat() / totalCount else 0f
    }
}

/**
 * 通知交互记录
 */
data class NotificationInteraction(
    val notificationId: String,
    val senderDomain: String,
    val senderTrust: String,        // SYSTEM_APP/TRUSTED_PARTNER/APP/CONTACT/UNKNOWN
    val category: String?,           // OTP/PAYMENT_RESULT/EMERGENCY/NEWS/MARKETING/etc.
    val classifierConfidence: Float,
    val userOpened: Boolean,         // 用户是否打开
    val timestamp: Long
)

/**
 * 来电交互记录
 */
data class CallInteraction(
    val callId: String,
    val callerNumber: String,
    val callerClass: String,        // whitelist/contact/unknown
    val numberPrefix: String,       // 号码前缀（95/10086/etc.）
    val spamScore: Float,           // 骚扰评分 (0-1)
    val userAnswered: Boolean,      // 用户是否接听
    val timestamp: Long
)

/**
 * Learn 建议
 */
data class LearnSuggestion(
    val suggestionId: String,       // learn.notify.<template>-<hash>
    val templateId: String,         // 模板 ID（sender-frequent-p1/category-digest/etc.）
    val title: String,              // 建议标题（用户可见）
    val description: String,        // 建议描述（用户可见）
    val rule: PolicyRule,           // 生成的规则
    val confidence: Float,          // 置信度 (0-1)
    val evidenceCount: Int,         // 证据数量（样本数）
    val createdAtMs: Long           // 创建时间
)

/**
 * 策略规则（简化版）
 */
data class PolicyRule(
    val id: String,
    val namespace: String,          // sys/user/learn
    val domain: String,             // notification/call_gate/action
    val effect: String,             // allow/deny/route_p0_native/route_p1_digest/etc.
    val whenConditions: Map<String, Any>,
    val thenActions: Map<String, Any>,
    val priority: Int,
    val meta: Map<String, Any>?     // learn. 规则必填 _meta
)
