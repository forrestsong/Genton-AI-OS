package com.android.server.ainative;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路由域编译后策略规则（A7.1 装载产物）
 *
 * 由 {@link PolicyFileParser} 从 PolicyPack JSON（catalog/用户包）编译而来，
 * when 子句被拆成结构化 {@link Condition} 列表，供 {@link PolicyTrieIndex}
 * 索引进桶与 {@link #matches(Map)} 精确求值。
 *
 * effect → route 常量映射对齐 A7.3 PolicyDecision：
 *  - notification: route_p0_native=0 / route_p1_digest=1 / route_p2_agent_act=2
 *  - call_gate:    route_ring_native=10 / route_agent_handle=11 / route_record_only=12
 *  - effect=deny 在路由域语义为 deny-protect（拒绝降级）：
 *    notification → P0_NATIVE（保持原生可见）、call_gate → RING_NATIVE（保持响铃）
 */
public class CompiledPolicyRule {

    // ==================== 路由常量（对齐 com.android.server.ainative.PolicyDecision）====================
    public static final int ROUTE_P0_NATIVE = 0;
    public static final int ROUTE_P1_DIGEST = 1;
    public static final int ROUTE_P2_AGENT_ACT = 2;
    public static final int ROUTE_RING_NATIVE = 10;
    public static final int ROUTE_AGENT_HANDLE = 11;
    public static final int ROUTE_RECORD_ONLY = 12;

    // ==================== 条件编译 ====================
    private static final Pattern COMPARISON_PATTERN =
            Pattern.compile("^(>=|<=|>|<|==)\\s*(-?\\d+(\\.\\d+)?)$");

    public final String id;
    public final String domain;       // notification / call_gate / action
    public final String effect;       // route_* / deny / ...
    public final String source;       // platform / user / learn
    public final boolean baseline;
    public final int priority;
    public final List<Condition> conditions;
    public final JSONObject then;

    private volatile int hitCount;
    private volatile long lastHitNanos;

    public String getId() {
        return id;
    }

    public boolean isBaselineFlag() {
        return baseline;
    }

    public int getPriority() {
        return priority;
    }

    public CompiledPolicyRule(String id, String domain, String effect, String source,
                              boolean baseline, int priority,
                              List<Condition> conditions, JSONObject then) {
        this.id = id;
        this.domain = domain;
        this.effect = effect;
        this.source = source;
        this.baseline = baseline;
        this.priority = priority;
        this.conditions = conditions;
        this.then = then;
    }

    // ==================== effect → route 常量 ====================

    /** 返回该规则 effect 对应的路由常量；非路由规则返回 -1（无路由决策）。 */
    public int routeConstant() {
        return routeFor(domain, effect);
    }

    /** 域/效果 → 路由常量映射（null 安全）。 */
    public static int routeFor(String domain, String effect) {
        if (effect == null) {
            return -1;
        }
        if ("notification".equals(domain)) {
            switch (effect) {
                case "route_p0_native": return ROUTE_P0_NATIVE;
                case "route_p1_digest": return ROUTE_P1_DIGEST;
                case "route_p2_agent_act": return ROUTE_P2_AGENT_ACT;
                // deny-protect：拒绝降级 = 保持原生
                case "deny": return ROUTE_P0_NATIVE;
                default: return -1;
            }
        }
        if ("call_gate".equals(domain)) {
            switch (effect) {
                case "route_ring_native": return ROUTE_RING_NATIVE;
                case "route_agent_handle": return ROUTE_AGENT_HANDLE;
                case "route_record_only": return ROUTE_RECORD_ONLY;
                // deny-protect：拒绝接管 = 保持响铃
                case "deny": return ROUTE_RING_NATIVE;
                default: return -1;
            }
        }
        return -1;
    }

    /** 无规则命中时的域默认路由（fail-safe：原生可见/响铃，不误藏）。 */
    public static int defaultRouteFor(String domain) {
        if ("notification".equals(domain)) {
            return ROUTE_P0_NATIVE;
        }
        if ("call_gate".equals(domain)) {
            return ROUTE_RING_NATIVE;
        }
        return -1;
    }

    // ==================== 精确求值 ====================

    /**
     * 全条件求值：when 中每个键都需在事实中出现且满足。
     * 事实键允许 "context.dndActive" 这类带点键；值可为 String/Boolean/Number/List。
     */
    public boolean matches(Map<String, Object> facts) {
        for (Condition c : conditions) {
            if (!c.satisfies(facts)) {
                return false;
            }
        }
        return true;
    }

    /** 命中统计（仅匹配命中时递增，供 D4.3/D6.2 透明展示）。 */
    void recordHit(long nowNanos) {
        hitCount++;
        lastHitNanos = nowNanos;
    }

    public int getHitCount() {
        return hitCount;
    }

    public long getLastHitNanos() {
        return lastHitNanos;
    }

    // ==================== 路由参数导出 ====================

    /**
     * 从 then 子句导出路由参数（剔除 route/audit——route 已编码进 route 常量，
     * audit 单独承载），格式为 JSON 文本，对齐 NotificationRoutingGate.parseRouteParams。
     */
    public String routeParamsJson() {
        if (then == null) {
            return null;
        }
        JSONObject copy = new JSONObject();
        for (java.util.Iterator<String> it = then.keys(); it.hasNext(); ) {
            String key = it.next();
            if ("route".equals(key) || "audit".equals(key)) {
                continue;
            }
            try {
                copy.put(key, then.get(key));
            } catch (Exception ignored) {
                // 单个键失败不影响整体
            }
        }
        return copy.length() == 0 ? null : copy.toString();
    }

    /** then.audit 审计级别（无 then 时默认 summary）。 */
    public String auditLevel() {
        if (then == null) {
            return "summary";
        }
        return then.optString("audit", "summary");
    }

    /** then.reason 规则说明（决策 reason 用）。 */
    public String reasonText() {
        if (then == null) {
            return null;
        }
        return then.optString("reason", null);
    }

    // ==================== when 条件编译 ====================

    /**
     * 把 when JSONObject 编译成条件列表（决策用，非匹配索引——索引由 PolicyTrieIndex 负责）。
     * JSON 值形态 → 条件：
     *  - JSONArray    → inSet（事实值 ∈ 集合）
     *  - Boolean      → boolEq
     *  - String       → 比较串(>=/<=/>/</==) → numOp；否则字符串相等
     *  - Number       → 数值相等
     */
    public static List<Condition> compileWhen(JSONObject when, String ruleId) {
        List<Condition> out = new ArrayList<>();
        if (when == null) {
            return out;
        }
        for (java.util.Iterator<String> it = when.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value;
            try {
                value = when.get(key);
            } catch (Exception e) {
                continue;
            }
            Condition c = compileCondition(key, value, ruleId);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static Condition compileCondition(String key, Object value, String ruleId) {
        if (value instanceof Boolean) {
            return new BoolCondition(key, (Boolean) value);
        }
        if (value instanceof Number) {
            return new NumCondition(key, "==", ((Number) value).doubleValue());
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) value;
            Set<String> members = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item != null) {
                    members.add(String.valueOf(item));
                }
            }
            return new InSetCondition(key, members);
        }
        if (value instanceof String) {
            String s = (String) value;
            Matcher m = COMPARISON_PATTERN.matcher(s.trim());
            if (m.matches()) {
                return new NumCondition(key, m.group(1), Double.parseDouble(m.group(2)));
            }
            return new EqCondition(key, s);
        }
        return null;
    }

    // ==================== 条件类型 ====================

    public interface Condition {
        /** 该条件涉及的 when 键 */
        String key();

        /** 用于 trie 索引的候选 token（相等/枚举集合 → 值本身；比较/布尔 → "*" 兜底桶） */
        Set<String> indexTokens();

        /** 事实是否满足该条件（缺键 = 不满足） */
        boolean satisfies(Map<String, Object> facts);
    }

    private static Object factOf(Map<String, Object> facts, String key) {
        return facts == null ? null : facts.get(key);
    }

    /** 字符串相等（event/枚举/numberPrefix/senderDomain…） */
    public static class EqCondition implements Condition {
        private final String mKey;
        private final String mExpected;

        EqCondition(String key, String expected) {
            mKey = key;
            mExpected = expected;
        }

        @Override public String key() { return mKey; }

        @Override public Set<String> indexTokens() {
            Set<String> t = new HashSet<>();
            t.add(mExpected);
            return t;
        }

        @Override public boolean satisfies(Map<String, Object> facts) {
            Object v = factOf(facts, mKey);
            if (v == null) {
                return false;
            }
            if (v instanceof List) {
                for (Object item : (List<?>) v) {
                    if (mExpected.equals(String.valueOf(item))) {
                        return true;
                    }
                }
                return false;
            }
            return mExpected.equals(String.valueOf(v));
        }
    }

    /** 枚举集合成员（category/callerIs/senderTrust 数组） */
    public static class InSetCondition implements Condition {
        private final String mKey;
        private final Set<String> mMembers;

        InSetCondition(String key, Set<String> members) {
            mKey = key;
            mMembers = members;
        }

        @Override public String key() { return mKey; }

        @Override public Set<String> indexTokens() {
            return new HashSet<>(mMembers);
        }

        @Override public boolean satisfies(Map<String, Object> facts) {
            Object v = factOf(facts, mKey);
            if (v == null) {
                return false;
            }
            if (v instanceof List) {
                for (Object item : (List<?>) v) {
                    if (mMembers.contains(String.valueOf(item))) {
                        return true;
                    }
                }
                return false;
            }
            return mMembers.contains(String.valueOf(v));
        }
    }

    /** 布尔相等（context.dndActive/userPinnedP0…） */
    public static class BoolCondition implements Condition {
        private final String mKey;
        private final boolean mExpected;

        BoolCondition(String key, boolean expected) {
            mKey = key;
            mExpected = expected;
        }

        @Override public String key() { return mKey; }

        @Override public Set<String> indexTokens() {
            Set<String> t = new HashSet<>();
            t.add(String.valueOf(mExpected));
            t.add("*");
            return t;
        }

        @Override public boolean satisfies(Map<String, Object> facts) {
            Object v = factOf(facts, mKey);
            if (v == null) {
                return false;
            }
            if (v instanceof Boolean) {
                return ((Boolean) v) == mExpected;
            }
            return mExpected == Boolean.parseBoolean(String.valueOf(v));
        }
    }

    /** 数值门限比较（spamScore/classifierConfidence/callRepeatedInWindowMs…） */
    public static class NumCondition implements Condition {
        private final String mKey;
        private final String mOp;    // >= <= > < ==
        private final double mThreshold;

        NumCondition(String key, String op, double threshold) {
            mKey = key;
            mOp = op;
            mThreshold = threshold;
        }

        @Override public String key() { return mKey; }

        @Override public Set<String> indexTokens() {
            Set<String> t = new HashSet<>();
            t.add("*");   // 比较条件无法精确预判 token，走兜底桶
            return t;
        }

        @Override public boolean satisfies(Map<String, Object> facts) {
            Object v = factOf(facts, mKey);
            if (v == null) {
                return false;
            }
            double actual;
            if (v instanceof Number) {
                actual = ((Number) v).doubleValue();
            } else {
                try {
                    actual = Double.parseDouble(String.valueOf(v).trim());
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            switch (mOp) {
                case ">=": return actual >= mThreshold;
                case "<=": return actual <= mThreshold;
                case ">": return actual > mThreshold;
                case "<": return actual < mThreshold;
                case "==": return Math.abs(actual - mThreshold) < 1e-9;
                default: return false;
            }
        }
    }

    @Override
    public String toString() {
        return "CompiledPolicyRule{" + id + ", domain=" + domain
                + ", effect=" + effect + ", priority=" + priority
                + ", baseline=" + baseline + ", source=" + source + '}';
    }
}
