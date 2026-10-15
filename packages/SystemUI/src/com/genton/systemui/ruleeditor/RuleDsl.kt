package com.genton.systemui.ruleeditor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * D6.2 规则编辑器 - 策略 DSL 值域与规则模型
 *
 * 值域事实源：vendor/genton/config/value_domain_catalog_v1.yaml（随 C6.4 版本 OTA）
 * DSL 事实源：tools/policy_dsl_compiler.py WHEN_WHITELIST + C1.1 policy_catalog_v1.yaml
 *
 * 用户规则命名空间约定：user.<...>（user. 只能来自用户表单编辑或 learn 激活复制；sys. 不可覆盖）
 */
object RuleDsl {

    const val ID_PATTERN = "^user\\.[a-z0-9_.-]{1,64}$"

    const val DOMAIN_NOTIFICATION = "notification"
    const val DOMAIN_CALL_GATE = "call_gate"

    /** 编译比对操作符（对齐 DSL 比较串 `^(>=|<=|>|<|==)\s*-?\d+(\.\d+)?$`） */
    val COMPARISON_OPS = listOf(">=", ">", "==", "<", "<=")

    /** notification 域分类（C1.2 值域 notification.category 16 值） */
    val NOTIFICATION_CATEGORIES = listOf(
        "alarm", "system_critical", "phone_ring_equivalent", "emergency",
        "otp", "payment_result", "message", "social_updates",
        "system_info", "service_notice", "news", "app_updates",
        "app_update_notice", "advertising", "marketing", "account_promo"
    )

    /** call_gate 域主叫类别（C1.2 值域 callerClass） */
    val CALLER_CLASSES = listOf(
        "emergency", "system", "carrier_service", "whitelist",
        "contact", "verified_business", "unknown"
    )

    /** notification 域 effect（DSL effect 白名单） */
    val NOTIFICATION_EFFECTS = listOf(
        "route_p0_native", "route_p1_digest", "route_p2_agent_act", "deny"
    )

    /** call_gate 域 effect（DSL effect 白名单） */
    val CALL_GATE_EFFECTS = listOf(
        "route_ring_native", "route_agent_handle", "route_record_only", "deny"
    )

    val AGENT_ACTS = listOf("fold", "dismiss")
    val AUDIT_LEVELS = listOf("summary", "full")

    /** effect → then.route 映射（对齐 DSL EFFECT_ROUTE_MAP） */
    fun routeOf(domain: String, effect: String): String? = when (domain) {
        DOMAIN_NOTIFICATION -> when (effect) {
            "route_p0_native" -> "P0_NATIVE"
            "route_p1_digest" -> "P1_AGENT_SUMMARY"
            "route_p2_agent_act" -> "P2_AGENT_HANDLE"
            else -> null
        }
        DOMAIN_CALL_GATE -> when (effect) {
            "route_ring_native" -> "RING_NATIVE"
            "route_agent_handle" -> "AGENT_HANDLE"
            "route_record_only" -> "RECORD_ONLY"
            else -> null
        }
        else -> null
    }

    /** effect 用户可读标签（列表卡片展示用） */
    fun effectLabel(effect: String): String = when (effect) {
        "route_p0_native" -> "原生展示 P0"
        "route_p1_digest" -> "聚合为简报 P1"
        "route_p2_agent_act" -> "Agent 代处理 P2"
        "route_ring_native" -> "直接来电（响铃）"
        "route_agent_handle" -> "Agent 应答"
        "route_record_only" -> "仅录音记录"
        "deny" -> "拒绝（静默拦截）"
        else -> effect
    }
}

/**
 * 用户自定义规则（schema 表单 → DSL 规则 的中间模型）
 *
 * 按域拆分 when/then 语义：
 *  - notification：categories/senderDomain/conf*（classifierConfidence）/dndOnly
 *  - call_gate：callerIs/spam*（spamScore）/numberPrefix
 */
data class UserRule(
    val id: String,
    val domain: String,
    val effect: String,

    // notification 条件
    val categories: List<String> = emptyList(),
    val senderDomain: String = "",
    val confOp: String = ">=",
    val confValue: Double? = null,

    // notification then（P1/P2 相关）
    val dndOnly: Boolean = false,          // when.context.dndActive
    val holdUntilDndEnd: Boolean = false,
    val aggregateWindowMs: Long = 30 * 60 * 1000L,   // P1 默认 30 分钟
    val suppressBadge: Boolean = true,
    val agentAct: String = "fold",         // P2

    // call_gate 条件
    val callerIs: List<String> = emptyList(),
    val spamOp: String = ">=",
    val spamValue: Double? = null,
    val numberPrefix: String = "",

    val audit: String = "summary",
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun blank(domain: String): UserRule =
            UserRule(id = "user.${if (domain == RuleDsl.DOMAIN_NOTIFICATION) "notify" else "callgate"}.my-rule",
                domain = domain, effect = if (domain == RuleDsl.DOMAIN_NOTIFICATION)
                    "route_p1_digest" else "route_record_only")

        fun fromValues(domain: String, v: Map<String, String>): UserRule {
            val categories = (v["categories"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val callerIs = (v["caller_is"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            return UserRule(
                id = (v["rule_id"] ?: "").trim(),
                domain = domain,
                effect = (v["effect"] ?: "").trim(),
                categories = categories,
                senderDomain = (v["sender_domain"] ?: "").trim(),
                confOp = (v["conf_op"] ?: ">=").trim(),
                confValue = (v["conf_value"] ?: "").trim().toDoubleOrNull(),
                dndOnly = (v["dnd_only"] ?: "false").toBoolean(),
                holdUntilDndEnd = (v["hold_dnd"] ?: "false").toBoolean(),
                aggregateWindowMs = ((v["agg_min"] ?: "30").trim().toLongOrNull() ?: 30L) * 60 * 1000L,
                suppressBadge = (v["suppress_badge"] ?: "true").toBoolean(),
                agentAct = (v["agent_act"] ?: "fold").trim(),
                callerIs = callerIs,
                spamOp = (v["spam_op"] ?: ">=").trim(),
                spamValue = (v["spam_value"] ?: "").trim().toDoubleOrNull(),
                numberPrefix = (v["number_prefix"] ?: "").trim(),
                audit = (v["audit"] ?: "summary").trim()
            )
        }
    }

    /** 编译为策略 DSL YAML 文本（对齐 policy_catalog_v1.yaml 规则结构） */
    fun toYaml(): String {
        val sb = StringBuilder()
        sb.append("- id: ").append(id).append('\n')
        sb.append("  domain: ").append(domain).append('\n')
        sb.append("  effect: ").append(effect).append('\n')
        sb.append("  priority: 150\n")
        sb.append("  when:\n")
        when (domain) {
            RuleDsl.DOMAIN_NOTIFICATION -> {
                sb.append("    event: notification.posted\n")
                if (categories.isNotEmpty()) sb.append("    category: [").append(categories.joinToString(", ")).append("]\n")
                if (senderDomain.isNotBlank()) sb.append("    senderDomain: \"").append(senderDomain).append("\"\n")
                confValue?.let { sb.append("    classifierConfidence: \"").append(confOp).append(" ").append(it).append("\"\n") }
                if (dndOnly) sb.append("    context.dndActive: true\n")
            }
            RuleDsl.DOMAIN_CALL_GATE -> {
                sb.append("    call: incoming\n")
                if (callerIs.isNotEmpty()) sb.append("    callerIs: [").append(callerIs.joinToString(", ")).append("]\n")
                spamValue?.let { sb.append("    spamScore: \"").append(spamOp).append(" ").append(it).append("\"\n") }
                if (numberPrefix.isNotBlank()) sb.append("    numberPrefix: \"").append(numberPrefix).append("\"\n")
            }
        }
        sb.append("  then:\n")
        RuleDsl.routeOf(domain, effect)?.let { sb.append("    route: ").append(it).append('\n') }
        if (effect == "route_p1_digest") {
            sb.append("    aggregateWindowMs: ").append(aggregateWindowMs).append('\n')
            if (holdUntilDndEnd) sb.append("    holdUntilDndEnd: true\n")
            if (suppressBadge) sb.append("    suppressBadge: true\n")
            sb.append("    summaryModel: local_tiny\n")
        }
        if (effect == "route_p2_agent_act") {
            sb.append("    agentAct: ").append(agentAct).append('\n')
        }
        sb.append("    audit: ").append(audit).append('\n')
        return sb.toString().trimEnd()
    }
}

/** 规则本地存储（Data/system 侧持久化统一由策略引擎接管前的过渡实现） */
interface UserRuleStore {
    fun load(): List<UserRule>
    fun save(rule: UserRule): Boolean   // false = id 重复/非法
    fun update(rule: UserRule): Boolean // 以同 id 覆盖（false = 不存在）
    fun delete(id: String): Boolean
}

/**
 * JSON 文件存储（默认实现）。
 * 路径：<filesDir>/user_rules_v1.json
 */
class JsonUserRuleStore(context: android.content.Context) : UserRuleStore {

    private val file = File(context.filesDir, "user_rules_v1.json")

    override fun load(): List<UserRule> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> parse(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    override fun save(rule: UserRule): Boolean {
        if (!rule.id.matches(Regex(RuleDsl.ID_PATTERN))) return false
        val existing = load()
        if (existing.any { it.id == rule.id }) return false
        val arr = JSONArray()
        existing.forEach { arr.put(toJson(it)) }
        arr.put(toJson(rule))
        file.writeText(arr.toString())
        return true
    }

    override fun update(rule: UserRule): Boolean {
        if (!rule.id.matches(Regex(RuleDsl.ID_PATTERN))) return false
        val existing = load()
        if (existing.none { it.id == rule.id }) return false
        val arr = JSONArray()
        existing.forEach { arr.put(if (it.id == rule.id) toJson(rule) else toJson(it)) }
        file.writeText(arr.toString())
        return true
    }

    override fun delete(id: String): Boolean {
        val existing = load()
        val filtered = existing.filter { it.id != id }
        if (filtered.size == existing.size) return false
        val arr = JSONArray()
        filtered.forEach { arr.put(toJson(it)) }
        file.writeText(arr.toString())
        return true
    }

    private fun toJson(r: UserRule): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("domain", r.domain)
        put("effect", r.effect)
        put("categories", JSONArray(r.categories))
        put("senderDomain", r.senderDomain)
        put("confOp", r.confOp)
        r.confValue?.let { put("confValue", it) }
        put("dndOnly", r.dndOnly)
        put("holdUntilDndEnd", r.holdUntilDndEnd)
        put("aggregateWindowMs", r.aggregateWindowMs)
        put("suppressBadge", r.suppressBadge)
        put("agentAct", r.agentAct)
        put("callerIs", JSONArray(r.callerIs))
        put("spamOp", r.spamOp)
        r.spamValue?.let { put("spamValue", it) }
        put("numberPrefix", r.numberPrefix)
        put("audit", r.audit)
        put("updatedAtMs", r.updatedAtMs)
    }

    private fun parse(o: JSONObject): UserRule? = runCatching {
        UserRule(
            id = o.getString("id"),
            domain = o.getString("domain"),
            effect = o.getString("effect"),
            categories = jsonList(o.optJSONArray("categories")),
            senderDomain = o.optString("senderDomain", ""),
            confOp = o.optString("confOp", ">="),
            confValue = if (o.has("confValue")) o.getDouble("confValue") else null,
            dndOnly = o.optBoolean("dndOnly", false),
            holdUntilDndEnd = o.optBoolean("holdUntilDndEnd", false),
            aggregateWindowMs = o.optLong("aggregateWindowMs", 30 * 60 * 1000L),
            suppressBadge = o.optBoolean("suppressBadge", true),
            agentAct = o.optString("agentAct", "fold"),
            callerIs = jsonList(o.optJSONArray("callerIs")),
            spamOp = o.optString("spamOp", ">="),
            spamValue = if (o.has("spamValue")) o.getDouble("spamValue") else null,
            numberPrefix = o.optString("numberPrefix", ""),
            audit = o.optString("audit", "summary"),
            updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis())
        )
    }.getOrNull()

    private fun jsonList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

/**
 * 规则语义校验（在 C6.1 schema 字段校验之上，执行 DSL 层校验）
 * 返回 (字段名, 错误消息)
 */
object RuleDslValidator {

    fun validate(rule: UserRule): List<Pair<String, String>> {
        val errors = mutableListOf<Pair<String, String>>()

        // 1. 规则 id 命名空间
        if (!rule.id.matches(Regex(RuleDsl.ID_PATTERN))) {
            errors.add("rule_id" to "规则 id 必须以 user. 开头，且仅含小写字母/数字/_/./-（≤64 字符）")
        }

        // 2. 至少一个命中条件（避免全量兜底误伤）
        when (rule.domain) {
            RuleDsl.DOMAIN_NOTIFICATION -> {
                if (rule.categories.isEmpty() && rule.senderDomain.isBlank() && rule.confValue == null) {
                    errors.add("categories" to "通知规则至少需要一个条件（分类/来源域/置信度）")
                }
                rule.categories.filter { it !in RuleDsl.NOTIFICATION_CATEGORIES }.forEach {
                    errors.add("categories" to "非法分类值：$it（须属于值域 catalog notification.category）")
                }
                rule.confValue?.let {
                    if (it < 0.0 || it > 1.0) errors.add("conf_value" to "分类器置信度须在 0.0~1.0")
                }
                if (rule.effect !in RuleDsl.NOTIFICATION_EFFECTS) {
                    errors.add("effect" to "通知域 effect 非法：${rule.effect}")
                }
            }
            RuleDsl.DOMAIN_CALL_GATE -> {
                if (rule.callerIs.isEmpty() && rule.spamValue == null && rule.numberPrefix.isBlank()) {
                    errors.add("caller_is" to "通话规则至少需要一个条件（主叫类别/骚扰分/号段）")
                }
                rule.callerIs.filter { it !in RuleDsl.CALLER_CLASSES }.forEach {
                    errors.add("caller_is" to "非法主叫类别：$it")
                }
                rule.spamValue?.let {
                    if (it < 0.0 || it > 1.0) errors.add("spam_value" to "骚扰分须在 0.0~1.0")
                }
                if (rule.effect !in RuleDsl.CALL_GATE_EFFECTS) {
                    errors.add("effect" to "通话域 effect 非法：${rule.effect}")
                }
            }
        }
        return errors
    }
}

/**
 * A7.1 reloadPolicySet 装载源导出器
 *
 * 把本机 user.* 规则（JsonUserRuleStore 持久化结果）序列化为 PolicyFileParser
 * 消费的 PolicyPack JSON（source=user、无签名——policy_pack_manager USER=不签名 约定；
 * user 源验签豁免，装载侧仍做值域/白名单 fail-stop 内容校验）。
 *
 * 产生结构：
 * {format, version, source:"user", rules:[{id, domain, effect, priority, when, then}]}
 *
 * 运输（binder/文件装载点）由 SystemUI → 策略引擎通道负责（装载点③
 * /data/misc/genton/user_policy_pack.json），本函数只保证"编辑器保存 → 可装载格式"。
 */
object UserPolicyPack {

    private const val FORMAT = "genton.policypack.v1"

    /**
     * 构建 user PolicyPack。全部规则未通过 RuleDslValidator 时返回 null（不做部分装载，
     * 避免把半成品推到引擎——引擎侧为原子换入，整包失败保留旧档）。
     */
    fun build(rules: List<UserRule>): JSONObject? {
        val valid = rules.filter { RuleDslValidator.validate(it).isEmpty() }
        if (valid.isEmpty()) return null

        val rulesArr = JSONArray()
        valid.forEach { rulesArr.put(toPackRule(it)) }

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", versionOf(valid))
            put("source", "user")
            put("rules", rulesArr)
        }
    }

    /** 版本 = 最新 updatedAtMs 派生的可读版本号（引擎热更新通知/决策 ruleVersion 展示）。 */
    private fun versionOf(rules: List<UserRule>): String {
        val latest = rules.maxOf { it.updatedAtMs }
        return "user." + java.text.SimpleDateFormat("yyyyMMdd.HHmmss", java.util.Locale.US)
            .format(java.util.Date(latest))
    }

    /** UserRule → PolicyPack 规则 JSON（when/then 对齐 C1.1 catalog 结构，key 全量可被装载校验）。 */
    private fun toPackRule(r: UserRule): JSONObject {
        val whenJson = JSONObject()
        val thenJson = JSONObject()

        when (r.domain) {
            RuleDsl.DOMAIN_NOTIFICATION -> {
                whenJson.put("event", "notification.posted")
                if (r.categories.isNotEmpty()) whenJson.put("category", JSONArray(r.categories))
                if (r.senderDomain.isNotBlank()) whenJson.put("senderDomain", r.senderDomain)
                r.confValue?.let { whenJson.put("classifierConfidence", "${r.confOp} $it") }
                if (r.dndOnly) whenJson.put("context.dndActive", true)
            }
            RuleDsl.DOMAIN_CALL_GATE -> {
                whenJson.put("call", "incoming")
                // callerIs 值域仅身份三类（emergency/system/carrier_service，value_domain callerIs）；
                // 通用分类（whitelist/contact/verified_business/unknown）落到 callerClass 键，
                // 与 policy_catalog 用户规则键语义一致，避免装载值域校验拒包。
                if (r.callerIs.isNotEmpty()) {
                    val identity = setOf("emergency", "system", "carrier_service")
                    if (r.callerIs.all { it in identity }) {
                        whenJson.put("callerIs", JSONArray(r.callerIs))
                    } else {
                        whenJson.put("callerClass", JSONArray(r.callerIs))
                    }
                }
                r.spamValue?.let { whenJson.put("spamScore", "${r.spamOp} $it") }
                if (r.numberPrefix.isNotBlank()) whenJson.put("numberPrefix", r.numberPrefix)
            }
        }

        RuleDsl.routeOf(r.domain, r.effect)?.let { thenJson.put("route", it) }
        when {
            r.effect == "route_p1_digest" -> {
                thenJson.put("aggregateWindowMs", r.aggregateWindowMs)
                if (r.holdUntilDndEnd) thenJson.put("holdUntilDndEnd", true)
                if (r.suppressBadge) thenJson.put("suppressBadge", true)
                thenJson.put("summaryModel", "local_tiny")
            }
            r.effect == "route_p2_agent_act" -> thenJson.put("agentAct", r.agentAct)
        }
        thenJson.put("audit", r.audit)

        return JSONObject().apply {
            put("id", r.id)
            put("domain", r.domain)
            put("effect", r.effect)
            put("priority", priorityBand(r))
            put("when", whenJson)
            put("then", thenJson)
        }
    }

    /**
     * effect 优先级分带（对齐 C1.1：user 正向 300-450 / user DND·聚合 150-250 /
     * user 摘要·折叠 90-140）。同效果同优先级的用户规则若条件重叠且结论互斥会在
     * 装载 C6 冲突矩阵被拒——按带区分避免 DND 延后与 P0 原生等合法并存误拒。
     */
    private fun priorityBand(r: UserRule): Int = when (r.domain) {
        RuleDsl.DOMAIN_NOTIFICATION -> when (r.effect) {
            "route_p0_native", "deny" -> 200
            "route_p1_digest" -> 150
            "route_p2_agent_act" -> 95
            else -> 150
        }
        RuleDsl.DOMAIN_CALL_GATE -> when (r.effect) {
            "route_ring_native", "deny" -> 300
            "route_agent_handle" -> 100
            "route_record_only" -> 140
            else -> 140
        }
        else -> 150
    }
}
