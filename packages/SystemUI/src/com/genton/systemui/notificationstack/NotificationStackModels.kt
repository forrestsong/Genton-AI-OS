package com.genton.systemui.notificationstack

/**
 * D6.1 通知栈呈现 - 数据模型
 *
 * 任务：SystemUI 可见性门消费路由决策（P0 原生 / P1 简报卡 / P2 已处理区 + 原文回看展开）
 * 依赖：
 *  - A7.3 NotificationRoutingGate.RoutingResult / PolicyDecision 路由常量
 *  - C4.2 NotificationClassifier 16 category 输出
 *  - A2.3 NotificationEnvelope（title/text 已 PII 掩码）
 */
object NotificationStackModels {

    /** 路由常量镜像（对齐 A7.3 PolicyDecision 通知域常量） */
    object Route {
        const val ROUTE_P0_NATIVE = 0
        const val ROUTE_P1_DIGEST = 1
        const val ROUTE_P2_AGENT_ACT = 2
    }

    /** 三层呈现档位 */
    enum class Tier(val label: String) {
        P0_NATIVE("P0 原生"),
        P1_DIGEST("P1 简报卡"),
        P2_AGENT_HANDLED("P2 已处理");

        companion object {
            fun fromRoute(route: Int): Tier = when (route) {
                Route.ROUTE_P0_NATIVE -> P0_NATIVE
                Route.ROUTE_P1_DIGEST -> P1_DIGEST
                Route.ROUTE_P2_AGENT_ACT -> P2_AGENT_HANDLED
                else -> P0_NATIVE  // fail-safe：未知/无路由一律原生可见
            }
        }
    }

    /** Agent 代处理动作（P2，对齐 RoutingResult.agentAct） */
    enum class AgentAct(val value: String, val label: String) {
        FOLD("fold", "Agent 已折叠"),
        DISMISS("dismiss", "Agent 已静默处理");

        companion object {
            fun fromString(s: String?): AgentAct? =
                entries.firstOrNull { it.value.equals(s, ignoreCase = true) }
        }
    }

    /**
     * SystemUI 侧路由决策镜像。
     * 对应 A7.3 NotificationRoutingGate.RoutingResult（route/ruleId/policyVersion/
     * summaryModel/aggregateWindowMs/holdUntilDndEnd/suppressBadge/agentAct/auditLevel）。
     */
    data class RouteDecision(
        val route: Int,
        val ruleId: String,
        val policyVersion: String,
        val summaryModel: String? = null,
        val aggregateWindowMs: Long = DEFAULT_AGGREGATE_WINDOW_MS,
        val holdUntilDndEnd: Boolean = false,
        val suppressBadge: Boolean = false,
        val agentAct: String? = null,   // fold / dismiss
        val auditLevel: String = "summary",
        val decidedAtMs: Long = System.currentTimeMillis()
    ) {
        val tier: Tier get() = Tier.fromRoute(route)
        val act: AgentAct? get() = AgentAct.fromString(agentAct)

        companion object {
            /** P1 默认聚合窗口：30 分钟 */
            const val DEFAULT_AGGREGATE_WINDOW_MS = 30 * 60 * 1000L
        }
    }

    /**
     * 进入可见性门的通知描述符。
     * 字段对齐 A7.3 NotificationDescriptor + A2.3 NotificationEnvelope；
     * title/originalText 一律为已 PII 掩码后的内容（掩码由 A2.3 AgentNotificationService 完成）。
     */
    data class StackNotification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val senderDomain: String = "",
        val category: String = CATEGORY_UNKNOWN,
        val classifierConfidence: Float = 0f,
        val title: String? = null,          // 掩码标题
        val originalText: String? = null,   // 掩码原文（原文回看展开内容）
        val postTimeMs: Long = System.currentTimeMillis(),
        val userPinnedP0: Boolean = false
    ) {
        companion object {
            /** C4.2 fallback 类别 */
            const val CATEGORY_UNKNOWN = "unknown"
        }
    }

    /**
     * P1 简报聚合卡：同一规则/窗口内的多条 P1 通知聚合为一卡，减少打扰。
     */
    data class DigestCard(
        val cardId: String,
        val ruleId: String,
        val summaryModel: String,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val entryKeys: MutableList<String> = mutableListOf(),
        val sourcePackages: MutableList<String> = mutableListOf()
    ) {
        /** 去重后的来源摘要文本，如「淘宝、拼多多等 3 个应用」 */
        val sourceSummary: String
            get() {
                val distinct = sourcePackages.distinct()
                return when {
                    distinct.isEmpty() -> "（空）"
                    distinct.size == 1 -> distinct[0]
                    else -> "${distinct.joinToString("、")} 等 ${distinct.size} 个应用"
                }
            }

        /** 参与聚合的通知条数 */
        val entryCount: Int get() = entryKeys.size
    }

    /** 栈变更监听：Gate 任何结构变化（进栈/聚合/展开/钉选/动作）都触发重绘 */
    fun interface StackChangeListener {
        fun onStackChanged()
    }
}
