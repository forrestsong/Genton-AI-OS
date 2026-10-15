package com.genton.systemui.notificationstack

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.genton.systemui.notificationstack.NotificationStackModels.Route
import com.genton.systemui.notificationstack.NotificationStackModels.RouteDecision
import com.genton.systemui.notificationstack.NotificationStackModels.StackNotification
import com.genton.systemui.notificationstack.NotificationStackModels.Tier

/**
 * D6.1 通知栈控制器
 *
 * 职责：
 *  - 创建 Gate + View，监听 Gate 变更并驱动 View 在主线程重绘
 *  - 作为「可见性门」的 SystemUI 侧消费入口：
 *      routeNotification(描述符, 路由决策) / onNotificationRemoved(key)
 *  - 提供 P1/P2 决策构建器与演示数据（集成自测用）
 *
 * 上游数据流（待 A7.1 策略引擎 + 传输通道接通后生效）：
 *   A2.3 AgentNotificationService（onNotificationPosted → NotificationEnvelope，已掩码）
 *     → C4.2 NotificationClassifier.classify（category + confidence）
 *     → A7.3 NotificationRoutingGate.routeNotification（→ RoutingResult）
 *     → 本控制器.routeNotification(StackNotification, RouteDecision)   [传输层 TODO]
 */
class NotificationStackViewController(
    context: Context,
    private val initialView: NotificationStackView? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 可见性门（三层状态机） */
    val gate: NotificationStackGate = NotificationStackGate(listener = { onStackChanged() })

    /** 通知栈视图 */
    val view: NotificationStackView = initialView ?: NotificationStackView(context)

    private val logTag = "NotificationStackVC"

    companion object {
        // ==================== P0 决策构建（供上游或测试使用）====================
        fun p0Decision(ruleId: String = "sys.notify.p0-default"): RouteDecision =
            RouteDecision(Route.ROUTE_P0_NATIVE, ruleId, "v1", auditLevel = "full")

        // ==================== P1 决策构建 ====================
        fun p1Decision(
            ruleId: String = "sys.notify.marketing_to_p1",
            aggregateWindowMs: Long = RouteDecision.DEFAULT_AGGREGATE_WINDOW_MS,
            holdUntilDndEnd: Boolean = false,
            summaryModel: String = "local_tiny"
        ): RouteDecision = RouteDecision(
            route = Route.ROUTE_P1_DIGEST,
            ruleId = ruleId,
            policyVersion = "v1",
            summaryModel = summaryModel,
            aggregateWindowMs = aggregateWindowMs,
            holdUntilDndEnd = holdUntilDndEnd,
            suppressBadge = true,
            auditLevel = "summary"
        )

        // ==================== P2 决策构建 ====================
        fun p2Decision(
            ruleId: String = "sys.notify.advertising_to_p2",
            agentAct: String = "dismiss"
        ): RouteDecision = RouteDecision(
            route = Route.ROUTE_P2_AGENT_ACT,
            ruleId = ruleId,
            policyVersion = "v1",
            agentAct = agentAct,
            suppressBadge = true,
            auditLevel = "full"
        )
    }

    // ==================== 生命周期 ====================

    /** 绑定到父容器（把 View 挂载上去并初始渲染） */
    fun attachTo(container: android.view.ViewGroup) {
        if (view.parent != container) {
            view.parent?.let { (it as? android.view.ViewGroup)?.removeView(view) }
            container.addView(
                view,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        view.bind(gate)
    }

    fun detach() {
        (view.parent as? android.view.ViewGroup)?.removeView(view)
    }

    // ==================== 消费入口（SystemUI 可见性门） ====================

    /**
     * 通知进栈：消费路由决策，决定呈现档位。
     * @param notification 通知描述符（title/原文已 PII 掩码）
     * @param decision 路由决策；null → fail-safe P0 原生（对齐 A7.3）
     */
    fun routeNotification(notification: StackNotification, decision: RouteDecision? = null) {
        gate.consume(notification, decision)
        android.util.Log.i(
            logTag,
            "route: key=${notification.key} cat=${notification.category} " +
                "tier=${(decision ?: NotificationStackGate.defaultRoute()).tier}"
        )
    }

    /** 通知移除（onNotificationRemoved） */
    fun onNotificationRemoved(key: String) {
        gate.remove(key)
        android.util.Log.i(logTag, "removed: key=$key")
    }

    fun setDndActive(active: Boolean) {
        gate.setDndActive(active)
    }

    fun getTier(key: String): Tier = gate.tierOf(key)

    // ==================== 交互转发（View 侧调用） ====================

    fun toggleOriginal(key: String) = gate.toggleExpanded(key)

    fun dismissHandled(key: String) = gate.dismissEntry(key)

    fun pinSender(packageName: String, pinned: Boolean) = gate.setPinnedP0(packageName, pinned)

    // ==================== 统计 ====================

    fun getStats(): Map<String, Any> = gate.getStats()

    // ==================== 演示数据（集成自测/视觉验证） ====================

    /**
     * 灌入一组演示通知，覆盖 P0/P1（含同规则聚合）/P2 三档，
     * 用于无上游服务时快速目测三区渲染与原文回看展开。
     */
    fun loadDemoStack() {
        gate.clearAll()
        val now = System.currentTimeMillis()

        // P0：银行 OTP（高重要，原生）
        routeNotification(
            StackNotification(
                key = "demo-otp-1",
                packageName = "com.icbc",
                appLabel = "工商银行",
                senderDomain = "finance",
                category = "otp",
                classifierConfidence = 0.97f,
                title = "您的验证码 88****，5 分钟内有效",
                originalText = "您的验证码 88****，5 分钟内有效。请勿向任何人透露，包括银行客服。",
                postTimeMs = now - 2 * 60 * 1000L
            ),
            p0Decision("user.icbc.otp-p0")
        )

        // P0：微信消息（联系人消息，原生）
        routeNotification(
            StackNotification(
                key = "demo-chat-1",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                senderDomain = "im",
                category = "message",
                classifierConfidence = 0.91f,
                title = "张伟：明早十点的会议记得参加",
                originalText = "张伟：明早十点的会议记得参加",
                postTimeMs = now - 60 * 1000L
            ),
            p0Decision("sys.notify.message-p0")
        )

        // P1：营销通知 × 2（同规则 30 分钟窗口内 → 聚合一张简报卡）
        routeNotification(
            StackNotification(
                key = "demo-mkt-taobao",
                packageName = "com.taobao.taobao",
                appLabel = "淘宝",
                senderDomain = "shopping",
                category = "marketing",
                classifierConfidence = 0.88f,
                title = "你的购物车有 3 件商品降价了",
                originalText = "你的购物车有 3 件商品降价了，叠加跨店满减最高省 120 元。",
                postTimeMs = now - 5 * 60 * 1000L
            ),
            p1Decision("sys.notify.marketing_to_p1")
        )
        routeNotification(
            StackNotification(
                key = "demo-mkt-meituan",
                packageName = "com.sankuai.meituan",
                appLabel = "美团",
                senderDomain = "life_service",
                category = "account_promo",
                classifierConfidence = 0.84f,
                title = "今日奶茶券已到账，限时 3 小时",
                originalText = "今日奶茶券已到账，限时 3 小时，下单立减 8 元。",
                postTimeMs = now - 3 * 60 * 1000L
            ),
            p1Decision("sys.notify.marketing_to_p1")
        )

        // P1（另一规则）：新闻推送（独立简报卡）
        routeNotification(
            StackNotification(
                key = "demo-news-1",
                packageName = "com.ss.android.article.news",
                appLabel = "今日头条",
                senderDomain = "news",
                category = "news",
                classifierConfidence = 0.79f,
                title = "AI 原生操作系统正式发布",
                originalText = "AI 原生操作系统正式发布：端侧模型常驻，隐私数据不出设备。",
                postTimeMs = now - 4 * 60 * 1000L
            ),
            p1Decision("sys.notify.news_to_p1", aggregateWindowMs = 15 * 60 * 1000L)
        )

        // P2：广告（Agent 静默处理，原文可回看）
        routeNotification(
            StackNotification(
                key = "demo-ad-1",
                packageName = "com.pinduoduo",
                appLabel = "拼多多",
                senderDomain = "shopping",
                category = "advertising",
                classifierConfidence = 0.93f,
                title = "0.9 元抢 iPhone 17，点击领取",
                originalText = "0.9 元抢 iPhone 17，点击领取，仅限今日，先到先得。",
                postTimeMs = now - 90 * 1000L
            ),
            p2Decision("sys.notify.advertising_to_p2", agentAct = "dismiss")
        )

        // P2：营销电话相关通知（Agent 折叠，原文可回看）
        routeNotification(
            StackNotification(
                key = "demo-marketing-fold",
                packageName = "com.ebay",
                appLabel = "跨境购",
                senderDomain = "shopping",
                category = "app_updates",
                classifierConfidence = 0.71f,
                title = "你的待领取优惠券即将过期",
                originalText = "3 张优惠券将于今晚 24 点过期，共价值 45 元。",
                postTimeMs = now - 120 * 1000L
            ),
            p2Decision("sys.notify.promo_fold_to_p2", agentAct = "fold")
        )
    }

    // ==================== Gate 变更 → 主线程重绘 ====================

    private fun onStackChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.render()
        } else {
            mainHandler.post { view.render() }
        }
    }
}
