package com.genton.systemui.notificationstack

import com.genton.systemui.notificationstack.NotificationStackModels.AgentAct
import com.genton.systemui.notificationstack.NotificationStackModels.DigestCard
import com.genton.systemui.notificationstack.NotificationStackModels.Route
import com.genton.systemui.notificationstack.NotificationStackModels.RouteDecision
import com.genton.systemui.notificationstack.NotificationStackModels.StackChangeListener
import com.genton.systemui.notificationstack.NotificationStackModels.StackNotification
import com.genton.systemui.notificationstack.NotificationStackModels.Tier
import java.util.LinkedHashMap

/**
 * D6.1 通知可见性门（Gate）
 *
 * SystemUI 侧消费 A7.3 路由决策的呈现控制器：
 *  - P0 原生：通知保持系统原生呈现（穿透真实通知栈）
 *  - P1 简报：通知从原生栈隐藏，进入聚合简报卡（窗口内同规则聚合，免打扰延后）
 *  - P2 已处理：通知从原生栈隐藏，进入「已处理区」（Agent fold/dismiss，原文可回看）
 *
 * Fail-safe（对齐 A7.3）：无路由决策 / 未知路由 / 用户钉选 P0 → 一律原生可见，绝不误藏。
 *
 * 线程模型：Gate 内部状态使用 @Synchronized 保护，视图线程调用即可。
 */
class NotificationStackGate(
    private val listener: StackChangeListener? = null
) {
    // ==================== P0 原生区（穿透）====================
    private val p0Entries = LinkedHashMap<String, StackNotification>()

    // ==================== P1 简报区 ====================
    /** 聚合简报卡（cardId -> card），按创建顺序 */
    private val digestCards = LinkedHashMap<String, DigestCard>()

    /** P1 原文存储：供简报卡展开「原文回看」 */
    private val p1EntryStore = LinkedHashMap<String, StackNotification>()

    /** 免打扰延后的 P1（holdUntilDndEnd && DND 中） */
    private val deferredP1 = LinkedHashMap<String, StackNotification>()
    private val deferredDecision = LinkedHashMap<String, RouteDecision>()

    // ==================== P2 已处理区 ====================
    private val p2Handled = LinkedHashMap<String, StackNotification>()

    // ==================== 交互状态 ====================
    private val expandedKeys = HashSet<String>()
    private val pinnedP0Senders = HashSet<String>()
    private val cardByEntry = HashMap<String, String>()   // entryKey -> cardId
    private val p2Acts = HashMap<String, AgentAct>()      // entryKey -> Agent 动作

    // ==================== 环境状态 ====================
    private var dndActive = false
    private var dailyP1Count = 0
    private var dailyP2Count = 0
    private var lastResetDay = 0L

    companion object {
        const val RULE_P0_PINNED = "sys.notify.baseline.p0-pinned"

        /** 无决策时的安全默认（对齐 NotificationRoutingGate.createDefaultRoute） */
        fun defaultRoute(): RouteDecision =
            RouteDecision(
                route = Route.ROUTE_P0_NATIVE,
                ruleId = "default_fallback",
                policyVersion = "0",
                auditLevel = "summary"
            )

        /** 用户钉选 P0 的强制决策 */
        fun pinnedP0Route(): RouteDecision =
            RouteDecision(
                route = Route.ROUTE_P0_NATIVE,
                ruleId = RULE_P0_PINNED,
                policyVersion = "0",
                auditLevel = "full"
            )
    }

    // ==================== 消费入口 ====================

    /**
     * 消费一条通知 + 路由决策，进入对应档位。
     * @param notification 通知描述符（title/原文均已 PII 掩码）
     * @param decision 路由决策；null 时按 fail-safe 进入 P0 原生
     */
    @Synchronized
    fun consume(notification: StackNotification, decision: RouteDecision? = null) {
        // 先移除旧状态（支持决策更新）
        removeInternal(notification.key)

        // baseline deny：用户钉选 P0 或发送方被钉选 → 强制原生（策略不可覆盖）
        val forcedP0 = notification.userPinnedP0 || pinnedP0Senders.contains(notification.packageName)
        val finalDecision = when {
            forcedP0 -> pinnedP0Route()
            decision == null -> defaultRoute()
            else -> decision
        }

        when (finalDecision.tier) {
            Tier.P0_NATIVE -> p0Entries[notification.key] = notification
            Tier.P1_DIGEST -> handleP1(notification, finalDecision)
            Tier.P2_AGENT_HANDLED -> {
                p2Handled[notification.key] = notification
                p2Acts[notification.key] = finalDecision.act ?: AgentAct.FOLD
                dailyP2Count++
            }
        }
        notifyChanged()
    }

    /** 通知被移除（系统 onNotificationRemoved） */
    @Synchronized
    fun remove(key: String) {
        if (removeInternal(key)) notifyChanged()
    }

    @Synchronized
    fun clearAll() {
        p0Entries.clear(); digestCards.clear(); p1EntryStore.clear()
        deferredP1.clear(); deferredDecision.clear(); p2Handled.clear()
        expandedKeys.clear(); cardByEntry.clear()
        notifyChanged()
    }

    // ==================== P1 聚合 ====================

    private fun handleP1(notification: StackNotification, decision: RouteDecision) {
        dailyP1Count++
        // 免打扰延后：不进聚合，DND 结束后统一成卡
        if (decision.holdUntilDndEnd && !dndActive) {
            deferredP1[notification.key] = notification
            deferredDecision[notification.key] = decision
            return
        }
        placeIntoDigestCard(notification, decision)
    }

    /**
     * 将 P1 通知放入聚合卡：窗口内（card.windowEndMs 内、同规则）追加，否则新建卡片。
     */
    private fun placeIntoDigestCard(notification: StackNotification, decision: RouteDecision) {
        val now = notification.postTimeMs
        val openCard = digestCards.values.lastOrNull { card ->
            card.ruleId == decision.ruleId && now < card.windowEndMs
        }
        val card = openCard ?: newDigestCard(decision, now)
        card.entryKeys.add(notification.key)
        card.sourcePackages.add(notification.packageName)
        p1EntryStore[notification.key] = notification
        cardByEntry[notification.key] = card.cardId
    }

    private fun newDigestCard(decision: RouteDecision, now: Long): DigestCard {
        val window = if (decision.aggregateWindowMs > 0) decision.aggregateWindowMs
        else RouteDecision.DEFAULT_AGGREGATE_WINDOW_MS
        val cardId = "${decision.ruleId}#$now"
        val card = DigestCard(
            cardId = cardId,
            ruleId = decision.ruleId,
            summaryModel = decision.summaryModel ?: "local_tiny",
            windowStartMs = now,
            windowEndMs = now + window
        )
        digestCards[cardId] = card
        return card
    }

    /** DND 状态切换：结束后将延后的 P1 成卡 */
    @Synchronized
    fun setDndActive(active: Boolean) {
        if (dndActive == active) return
        dndActive = active
        if (!active && deferredP1.isNotEmpty()) {
            // DND 结束：合并延后项到各自规则卡
            val items = deferredP1.values.toList()
            val decisions = HashMap(deferredDecision)
            deferredP1.clear(); deferredDecision.clear()
            items.forEach { n ->
                placeIntoDigestCard(n, decisions[n.key] ?: defaultRoute())
            }
        }
        notifyChanged()
    }

    // ==================== 可见性门查询（SystemUI 呈现消费）====================

    /**
     * 该通知是否应在系统原生通知栈（Shade）可见。
     * P0 → true（原生穿透）；P1/P2 → false（已从原生栈隐藏，改由 Agent 栈呈现）。
     */
    @Synchronized
    fun shouldShowInNativeShade(key: String): Boolean {
        return p0Entries.containsKey(key)
    }

    /** 当前档位（含延后区） */
    @Synchronized
    fun tierOf(key: String): Tier = when {
        p0Entries.containsKey(key) -> Tier.P0_NATIVE
        p2Handled.containsKey(key) -> Tier.P2_AGENT_HANDLED
        p1EntryStore.containsKey(key) || cardByEntry.containsKey(key) -> Tier.P1_DIGEST
        deferredP1.containsKey(key) -> Tier.P1_DIGEST
        else -> Tier.P0_NATIVE  // 未知 → 按原生可见处理（不误藏）
    }

    @Synchronized
    fun getNotification(key: String): StackNotification? =
        p0Entries[key] ?: p2Handled[key] ?: p1EntryStore[key] ?: deferredP1[key]

    // ==================== P0 / P2 快照 ====================

    /** P0 原生通知（后进 = 更近） */
    @Synchronized
    fun getP0Notifications(): List<StackNotification> = p0Entries.values.reversed()

    /** P2 已处理通知（后进 = 更近） */
    @Synchronized
    fun getP2Notifications(): List<StackNotification> = p2Handled.values.reversed()

    // ==================== P1 快照 ====================

    @Synchronized
    fun getDigestCards(): List<DigestCard> = digestCards.values.toList()

    /** 简报卡内全部通知（按聚合顺序），供展开原文 */
    @Synchronized
    fun getDigestEntries(cardId: String): List<StackNotification> {
        val card = digestCards[cardId] ?: return emptyList()
        return card.entryKeys.mapNotNull { p1EntryStore[it] }
    }

    @Synchronized
    fun getDeferredCount(): Int = deferredP1.size

    // ==================== 交互动作 ====================

    /** 原文回看展开/收起（P1 简报与 P2 已处理均支持） */
    @Synchronized
    fun setExpanded(key: String, expanded: Boolean) {
        if (expanded) expandedKeys.add(key) else expandedKeys.remove(key)
        notifyChanged()
    }

    @Synchronized
    fun isExpanded(key: String): Boolean = expandedKeys.contains(key)

    @Synchronized
    fun toggleExpanded(key: String) {
        if (expandedKeys.contains(key)) expandedKeys.remove(key) else expandedKeys.add(key)
        notifyChanged()
    }

    /** 用户钉选发送方为 P0（强制原生，策略不可覆盖，对齐 A7.3 setPinnedP0） */
    @Synchronized
    fun setPinnedP0(packageName: String, pinned: Boolean) {
        if (pinned) pinnedP0Senders.add(packageName) else pinnedP0Senders.remove(packageName)
        notifyChanged()
    }

    @Synchronized
    fun isPinnedP0(packageName: String): Boolean = pinnedP0Senders.contains(packageName)

    @Synchronized
    fun getPinnedSenders(): Set<String> = pinnedP0Senders.toSet()

    /** 从 P2 已处理区移除（不再展示） */
    @Synchronized
    fun dismissEntry(key: String) {
        p2Handled.remove(key)
        p2Acts.remove(key)
        expandedKeys.remove(key)
        notifyChanged()
    }

    /** 查看 P2 条目的 Agent 代处理动作（fold/dismiss） */
    @Synchronized
    fun agentActOf(key: String): AgentAct? = p2Acts[key]

    // ==================== 统计 ====================

    @Synchronized
    fun getStats(): Map<String, Any> {
        resetDailyIfNeeded()
        return mapOf(
            "p0_count" to p0Entries.size,
            "digest_card_count" to digestCards.size,
            "p1_entry_count" to p1EntryStore.size,
            "deferred_p1_count" to deferredP1.size,
            "p2_count" to p2Handled.size,
            "daily_p1" to dailyP1Count,
            "daily_p2" to dailyP2Count,
            "pinned_senders" to pinnedP0Senders.size,
            "dnd_active" to dndActive
        )
    }

    // ==================== 内部 ====================

    /** 从所有结构中移除某 key，返回是否发生变更 */
    private fun removeInternal(key: String): Boolean {
        var changed = false
        changed = p0Entries.remove(key) != null || changed
        changed = p2Handled.remove(key) != null || changed
        changed = p1EntryStore.remove(key) != null || changed
        changed = deferredP1.remove(key) != null || changed
        deferredDecision.remove(key)
        p2Acts.remove(key)
        expandedKeys.remove(key)
        val cardId = cardByEntry.remove(key)
        if (cardId != null) {
            val card = digestCards[cardId]
            if (card != null) {
                card.entryKeys.remove(key)
                // 空卡删除
                if (card.entryKeys.isEmpty()) {
                    digestCards.remove(cardId)
                }
            }
            changed = true
        }
        return changed
    }

    private fun resetDailyIfNeeded() {
        val today = System.currentTimeMillis() / (24 * 3600 * 1000)
        if (today != lastResetDay) {
            lastResetDay = today
            dailyP1Count = 0
            dailyP2Count = 0
        }
    }

    private fun notifyChanged() {
        listener?.onStackChanged()
    }
}
