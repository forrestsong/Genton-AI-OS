package com.android.server.ainative;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PolicyPack 包解析与装载前校验（A7.1 装载口子核心）
 *
 * PolicyPack JSON 信封格式（与 C1.4 打包/灰度工具链对齐）：
 * <pre>
 * {
 *   "format": "ainative.policypack.v1",
 *   "version": "1.0.0",
 *   "source": "platform | user | learning",
 *   "signature": "HMAC-SHA256(base64)",   // platform 源必填（E2.2 TEE 密钥入位前不可用）
 *   "rules": [ {id, domain, effect, priority, when{}, then{}, baseline?} ]
 * }
 * </pre>
 *
 * 装载语义：
 *  - 校验分两道：PolicyDslCompiler（A7.2，C1–C8 白名单/冲突矩阵）→ 本解析器二次校验
 *    （值域合法值、命名空间↔源、优先级带、learn 激活态、规则条数上限）。
 *  - 输出按域分组、按 (baseline 优先 → priority 降序 → id) 排好的编译后规则列表，
 *    直接供 {@link PolicyRuleEvaluator} 建 trie 原子换入。
 *  - deny 默认由求值侧兜底，本解析器只做 fail-stop 装载。
 *
 * 签名（E2.2 前置）：platform 源强制要求验签；密钥基建入位前，平台 OTA 装载
 * fail-closed 拒绝（内置开机基线为代码内可信源，不经过 reloadPolicySet）。
 * user 源按 policy_pack_manager USER=不签名 约定允许空签名。
 */
public class PolicyFileParser {

    private static final String TAG = "PolicyFileParser";
    private static final String FORMAT = "ainative.policypack.v1";

    // ==================== 包/规则上限 ====================
    private static final int MAX_RULES_PER_PACK = 256;

    // ==================== 值域合法值（对齐 C1.2 value_domain_catalog_v1.yaml）====================
    private static final Set<String> NOTIFY_CATEGORIES = new HashSet<>(Arrays.asList(
            "alarm", "system_critical", "phone_ring_equivalent", "emergency",
            "otp", "payment_result", "message", "social_updates",
            "system_info", "service_notice", "news", "app_updates",
            "app_update_notice", "advertising", "marketing", "account_promo"));

    private static final Set<String> SENDER_TRUST_VALUES = new HashSet<>(Arrays.asList(
            "SYSTEM_APP", "TRUSTED_PARTNER", "VERIFIED_APP", "APP", "UNKNOWN"));

    private static final Set<String> CALLER_CLASS_VALUES = new HashSet<>(Arrays.asList(
            "emergency", "system", "carrier_service", "whitelist",
            "contact", "verified_business", "unknown"));

    private static final Set<String> CALLER_IS_VALUES = new HashSet<>(Arrays.asList(
            "emergency", "system", "carrier_service"));

    private static final Set<String> GATE_MODE_VALUES = new HashSet<>(Arrays.asList(
            "DISABLED", "L1_SILENT_AI", "L2_CONFIRM_DIGEST", "L3_RECORD_ONLY"));

    private static final Set<String> CALL_EVENT_VALUES = new HashSet<>(Arrays.asList(
            "incoming", "outgoing", "missed"));

    private static final Set<String> AUDIT_VALUES = new HashSet<>(Arrays.asList(
            "summary", "full", "none"));

    // 数值门限比较键（when 值必须是比较串）
    private static final Set<String> NUMERIC_THRESHOLD_KEYS = new HashSet<>(Arrays.asList(
            "spamScore", "classifierConfidence", "callRepeatedInWindowMs",
            "callerHasRecentOutgoingWithinDays", "dailyAiHandleUsed"));

    // ==================== when 键白名单（按域，context.* 前缀放行） ====================
    private static final Map<String, Set<String>> WHEN_WHITELIST = new HashMap<>();
    static {
        WHEN_WHITELIST.put("notification", new HashSet<>(Arrays.asList(
                "event", "senderDomain", "senderFingerprint", "senderTrust",
                "category", "classifierConfidence", "userPinnedP0")));
        WHEN_WHITELIST.put("call_gate", new HashSet<>(Arrays.asList(
                "call", "caller", "callerClass", "callerIs", "numberPrefix",
                "spamScore", "callRepeatedInWindowMs",
                "callerHasRecentOutgoingWithinDays", "dailyAiHandleUsed", "gateMode")));
    }

    // ==================== effect 白名单（按域） ====================
    private static final Map<String, Set<String>> EFFECT_WHITELIST = new HashMap<>();
    static {
        EFFECT_WHITELIST.put("notification", new HashSet<>(Arrays.asList(
                "deny", "route_p0_native", "route_p1_digest", "route_p2_agent_act")));
        EFFECT_WHITELIST.put("call_gate", new HashSet<>(Arrays.asList(
                "deny", "route_ring_native", "route_agent_handle", "route_record_only")));
    }

    private static final Pattern COMPARISON_PATTERN =
            Pattern.compile("^(>=|<=|>|<|==)\\s*(-?\\d+(\\.\\d+)?)$");

    // ==================== 装载结果 ====================

    public static class LoadResult {
        // [主线2 修复] 默认应为 true：本类从未把 success 置为 true，
        // 而 addError() 负责置 false。原默认 false 会使 parsePolicyPack()
        // 必然提前返回且无错误，导致 PolicyEngineService 开机 fail-stop。
        public boolean success = true;
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public String version;
        public String source;
        /** domain → 编译后规则（已按 baseline 优先/priority 降序排好） */
        public final Map<String, List<CompiledPolicyRule>> byDomain = new LinkedHashMap<>();
        public int ruleCount;

        public void addError(String msg) {
            errors.add(msg);
            success = false;
        }
    }

    // ==================== 入口 ====================

    /**
     * 解析并校验 PolicyPack。
     *
     * @param packJson PolicyPack JSON 文本
     * @param sourceType 期望来源（platform/user），null = 以包内 source 字段为准
     * @return 装载结果（失败时 byDomain 为空、errors 含全部原因）
     */
    public static LoadResult parsePolicyPack(String packJson, String sourceType) {
        LoadResult result = new LoadResult();
        try {
            JSONObject pack = new JSONObject(packJson);

            // 格式
            if (pack.has("format") && !FORMAT.equals(pack.optString("format"))) {
                result.addError("Unsupported pack format: " + pack.optString("format"));
                return result;
            }

            // 版本
            result.version = pack.optString("version", "").trim();
            if (result.version.isEmpty()) {
                result.addError("Missing required field: version");
            }

            // 来源（信封优先，调用方可强约束）
            result.source = pack.optString("source", "").trim();
            if (sourceType != null && !sourceType.isEmpty()) {
                if (!result.source.isEmpty() && !sourceType.equals(result.source)) {
                    result.addError("Source mismatch: expected=" + sourceType
                            + ", actual=" + result.source);
                    return result;
                }
                result.source = sourceType;
            }
            if (!"platform".equals(result.source) && !"user".equals(result.source)) {
                result.addError("Unsupported source: " + result.source
                        + " (loader v1 仅开放 platform/user; learning 走 C2.2 激活流)");
                return result;
            }

            // 规则
            if (!pack.has("rules")) {
                result.addError("Missing required field: rules");
                return result;
            }
            JSONArray rules = pack.getJSONArray("rules");
            if (rules.length() > MAX_RULES_PER_PACK) {
                result.addError("Too many rules in pack: " + rules.length()
                        + " (max " + MAX_RULES_PER_PACK + ")");
            }
            if (!result.success) {
                return result;
            }

            // 第一道：A7.2 编译/静态检查（C1–C8）
            PolicyDslCompiler.CompilationResult ci =
                    new PolicyDslCompiler().compile(packJson, result.source);
            if (!ci.success) {
                for (String e : ci.errors) {
                    result.addError("DSL compile: " + e);
                }
                return result;
            }
            for (String w : ci.warnings) {
                result.warnings.add("DSL warn: " + w);
            }

            // 第二道：值域/命名空间/优先级带 + 编译为 CompiledPolicyRule
            Map<String, List<CompiledPolicyRule>> byDomain = new HashMap<>();
            Set<String> ruleIds = new HashSet<>();
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.getJSONObject(i);
                validateAndCompile(rule, result, byDomain, ruleIds);
                if (!result.success) {
                    return result;  // fail-stop：首个错误即回退整包
                }
            }

            // 按域排序并装配
            for (Map.Entry<String, List<CompiledPolicyRule>> e : byDomain.entrySet()) {
                List<CompiledPolicyRule> sorted = new ArrayList<>(e.getValue());
                sorted.sort(RULE_ORDER);
                result.byDomain.put(e.getKey(), sorted);
                result.ruleCount += sorted.size();
            }
            return result;

        } catch (JSONException e) {
            result.addError("JSON parse error: " + e.getMessage());
            return result;
        } catch (Exception e) {
            result.addError("Loader internal error: " + e.getMessage());
            return result;
        }
    }

    /** 单条规则校验（值域/命名空间/优先级带）并编译。任一错误 → 整包失败（fail-stop）。 */
    private static void validateAndCompile(JSONObject rule, LoadResult result,
                                           Map<String, List<CompiledPolicyRule>> byDomain,
                                           Set<String> ruleIds) {
        try {
            String id = rule.getString("id");
            String domain = rule.optString("domain", "action");
            String effect = rule.getString("effect");
            boolean baseline = rule.optBoolean("baseline", false);
            int priority = rule.getInt("priority");
            JSONObject when = rule.getJSONObject("when");

            // id 唯一（编译器已查，双保险）
            if (!ruleIds.add(id)) {
                result.addError("[" + id + "] Duplicate rule id");
                return;
            }

            // 命名空间 ↔ 来源
            String ns = id.startsWith("sys.") ? "sys" : id.startsWith("learn.") ? "learn" : "user";
            if ("sys".equals(ns) && !"platform".equals(result.source)) {
                result.addError("[" + id + "] sys.* rules can only come from platform source");
                return;
            }
            if ("learn".equals(ns)) {
                result.addError("[" + id + "] learn.* 规则须经 C2.2 激活流（suggestion→active）后以 user.* 副本装载");
                return;
            }
            if ("user".equals(ns) && "platform".equals(result.source)) {
                result.addError("[" + id + "] platform source must not contain user.* rules");
                return;
            }

            // 域限定：装载口子 v1 只管路由轴（notification/call_gate）
            if (!"notification".equals(domain) && !"call_gate".equals(domain)) {
                result.addError("[" + id + "] domain '" + domain
                        + "' 不走装载口子 v1（仅 notification/call_gate；action 域由授权轴管理）");
                return;
            }

            // baseline 规则仅 sys 且来自 platform
            if (baseline && !("sys".equals(ns) && "platform".equals(result.source))) {
                result.addError("[" + id + "] baseline:true 仅允许 sys.* + platform source");
                return;
            }

            // 优先级带（对齐 C1.1：sys.baseline 600-1000；user 不得越过 500 触碰 sys 非基线带）
            if (baseline && (priority < 600 || priority > 1000)) {
                result.addError("[" + id + "] baseline rule priority must be in [600,1000], got " + priority);
                return;
            }
            if ("user".equals(ns) && (priority < 0 || priority > 500)) {
                result.addError("[" + id + "] user.* rule priority must be in [0,500], got " + priority);
                return;
            }

            // effect 白名单
            Set<String> allowedEffects = EFFECT_WHITELIST.get(domain);
            if (allowedEffects == null || !allowedEffects.contains(effect)) {
                result.addError("[" + id + "] effect '" + effect + "' not allowed for domain '" + domain + "'");
                return;
            }

            // when 键白名单（context.* 放行）与值校验
            validateWhen(id, domain, when, result);

            // audit 值
            JSONObject then = rule.optJSONObject("then");
            if (then != null && then.has("audit")) {
                String audit = then.optString("audit", "");
                if (!AUDIT_VALUES.contains(audit)) {
                    result.addError("[" + id + "] then.audit '" + audit + "' not allowed (summary/full/none)");
                    return;
                }
            }

            // then.route 与 effect 同族（编译器已查，此处兜底以保 routeFor 一致性）
            if (then != null && then.has("route")) {
                int expected = CompiledPolicyRule.routeFor(domain, effect);
                String routeName = routeNameOf(expected);
                if (expected >= 0 && !routeName.equals(then.optString("route", ""))) {
                    result.addError("[" + id + "] route mismatch: effect '" + effect
                            + "' requires route '" + routeName + "', got '" + then.optString("route") + "'");
                    return;
                }
            }

            // 编译
            CompiledPolicyRule compiled = new CompiledPolicyRule(
                    id, domain, effect, result.source, baseline, priority,
                    CompiledPolicyRule.compileWhen(when, id), then);
            byDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(compiled);

        } catch (JSONException e) {
            result.addError("Rule parse error: " + e.getMessage());
        }
    }

    /** when 键白名单 + 值枚举/比较串校验。 */
    private static void validateWhen(String id, String domain, JSONObject when, LoadResult result) {
        Set<String> allowed = WHEN_WHITELIST.get(domain);
        if (allowed == null) {
            result.addError("[" + id + "] Unknown domain: " + domain);
            return;
        }
        for (java.util.Iterator<String> it = when.keys(); it.hasNext(); ) {
            String key = it.next();
            boolean allowedKey = allowed.contains(key) || key.startsWith("context.");
            if (!allowedKey) {
                result.addError("[" + id + "] when key '" + key + "' not in whitelist for domain '" + domain + "'");
                return;
            }
            Object value;
            try {
                value = when.get(key);
            } catch (JSONException e) {
                result.addError("[" + id + "] when key '" + key + "' read error");
                return;
            }
            checkValue(id, domain, key, value, result);
            if (!result.success) {
                return;
            }
        }
    }

    private static void checkValue(String id, String domain, String key, Object value, LoadResult result) {
        List<String> values = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                values.add(String.valueOf(arr.opt(i)));
            }
        } else {
            values.add(String.valueOf(value));
        }

        if ("notification".equals(domain)) {
            if ("category".equals(key)) {
                checkAllIn(id, key, values, NOTIFY_CATEGORIES, result);
            } else if ("senderTrust".equals(key)) {
                checkAllIn(id, key, values, SENDER_TRUST_VALUES, result);
            }
        } else if ("call_gate".equals(domain)) {
            if ("callerClass".equals(key)) {
                checkAllIn(id, key, values, CALLER_CLASS_VALUES, result);
            } else if ("callerIs".equals(key)) {
                checkAllIn(id, key, values, CALLER_IS_VALUES, result);
            } else if ("gateMode".equals(key)) {
                checkAllIn(id, key, values, GATE_MODE_VALUES, result);
            } else if ("call".equals(key) && !CALL_EVENT_VALUES.contains(values.get(0))) {
                result.addError("[" + id + "] call '" + values.get(0)
                        + "' not allowed (incoming/outgoing/missed)");
            }
        }

        if (NUMERIC_THRESHOLD_KEYS.contains(key)) {
            for (String v : values) {
                if (!COMPARISON_PATTERN.matcher(v).matches()) {
                    result.addError("[" + id + "] key '" + key
                            + "' 需数值比较串(>=|<=|>|<|==)…，got '" + v + "'");
                }
            }
        }
    }

    private static void checkAllIn(String id, String key, List<String> values,
                                   Set<String> allowed, LoadResult result) {
        for (String v : values) {
            if (!allowed.contains(v)) {
                result.addError("[" + id + "] " + key + " value '" + v + "' not in value domain");
                return;
            }
        }
    }

    private static String routeNameOf(int route) {
        switch (route) {
            case CompiledPolicyRule.ROUTE_P0_NATIVE: return "P0_NATIVE";
            case CompiledPolicyRule.ROUTE_P1_DIGEST: return "P1_AGENT_SUMMARY";
            case CompiledPolicyRule.ROUTE_P2_AGENT_ACT: return "P2_AGENT_HANDLE";
            case CompiledPolicyRule.ROUTE_RING_NATIVE: return "RING_NATIVE";
            case CompiledPolicyRule.ROUTE_AGENT_HANDLE: return "AGENT_HANDLE";
            case CompiledPolicyRule.ROUTE_RECORD_ONLY: return "RECORD_ONLY";
            default: return "";
        }
    }

    /** 装载序：baseline 优先 → priority 降序 → id 字典序（确定性）。 */
    private static final Comparator<CompiledPolicyRule> RULE_ORDER =
            Comparator.comparing(CompiledPolicyRule::isBaselineFlag, Comparator.reverseOrder())
                    .thenComparing(CompiledPolicyRule::getPriority, Comparator.reverseOrder())
                    .thenComparing(CompiledPolicyRule::getId);

    // ==================== 签名验签（E2.2 前置桩） ====================

    /**
     * 验签策略（fail-closed）：
     *  - user 源：policy_pack_manager USER=不签名，允许空签名；
     *  - platform 源：必须携带有效 HMAC-SHA256 签名；
     *    TEE/平台密钥基建（E2.2）入位前返回 false，reloadPolicySet 一律拒绝平台 OTA 装载。
     * 开机内置平台基线是代码内可信源，不经本通道装载。
     */
    public static boolean verifyPackSignature(String packJson, String source) {
        if ("user".equals(source)) {
            return true;   // 不签名（内容校验已由值域/白名单 fail-stop 承担）
        }
        // platform：密钥入 TEE 前不可用
        return false;
    }

    /** E2.2 密钥基建是否已就绪（当前 false）。 */
    public static boolean isSignatureVerifierReady() {
        return false;
    }
}
