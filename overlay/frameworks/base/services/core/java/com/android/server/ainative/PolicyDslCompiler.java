package com.android.server.ainative;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSL 编译器与静态检查器（A7.2）
 * 
 * 实现策略 DSL 的编译期检查（C1-C8），包括：
 * - C1: id 命名空间/格式/唯一性
 * - C2: 字段必填性、未知字段/键
 * - C3: when 键白名单、值枚举/比较串
 * - C4: effect 动词表、route 一致性
 * - C5: 命名空间授权校验
 * - C6: 冲突矩阵（baseline 重叠、同 priority 冲突）
 * - C7: audit:none 用于 high/接管类检查
 * - C8: 死规则告警
 * 
 * 输入: PolicyPack JSON（rules 数组）
 * 输出: CompilationResult（成功/失败 + 错误/告警列表）
 */
public class PolicyDslCompiler {
    private static final String TAG = "PolicyDslCompiler";

    // ==================== C1: id 命名空间与格式 ====================
    private static final Pattern ID_PATTERN = Pattern.compile("^(sys|user|learn)\\.[a-z0-9_.-]{1,64}$");
    private static final String[] NAMESPACES = {"sys", "user", "learn"};

    // ==================== C2: 字段定义 ====================
    private static final Set<String> REQUIRED_FIELDS = new HashSet<>(Arrays.asList(
        "id", "effect", "when", "then", "priority"
    ));
    
    private static final Set<String> OPTIONAL_FIELDS = new HashSet<>(Arrays.asList(
        "baseline", "domain", "subject", "_meta"
    ));

    // ==================== C3: when 键白名单（按域） ====================
    private static final Map<String, Set<String>> WHEN_WHITELIST = new HashMap<>();
    static {
        // action 域（授权轴）
        WHEN_WHITELIST.put("action", new HashSet<>(Arrays.asList(
            "capability", "action", "argSources", "context.network", 
            "context.appForeground", "context.timeWindow"
        )));
        
        // notification 域（路由轴）
        WHEN_WHITELIST.put("notification", new HashSet<>(Arrays.asList(
            "event", "senderDomain", "senderFingerprint", "senderTrust", 
            "category", "classifierConfidence", "userPinnedP0", "context.dndActive"
        )));
        
        // call_gate 域（路由轴）
        WHEN_WHITELIST.put("call_gate", new HashSet<>(Arrays.asList(
            "call", "caller", "callerClass", "callerIs", "numberPrefix", 
            "spamScore", "callRepeatedInWindowMs", "callerHasRecentOutgoingWithinDays", 
            "dailyAiHandleUsed", "gateMode", "context.dndActive"
        )));
    }

    // ==================== C3: 值枚举白名单 ====================
    private static final Set<String> SENDER_TRUST_VALUES = new HashSet<>(Arrays.asList(
        "SYSTEM_APP", "TRUSTED_PARTNER", "APP", "UNKNOWN"
    ));
    
    private static final Set<String> CALLER_CLASS_VALUES = new HashSet<>(Arrays.asList(
        "whitelist", "contact", "unknown"
    ));
    
    private static final Set<String> CALLER_IS_VALUES = new HashSet<>(Arrays.asList(
        "emergency", "system", "carrier_service"
    ));
    
    private static final Set<String> GATE_MODE_VALUES = new HashSet<>(Arrays.asList(
        "L1_SILENT_AI", "L2_AUDIO_DIALOG", "L3_RECORD_ONLY"
    ));

    // 比较串模式
    private static final Pattern COMPARISON_PATTERN = Pattern.compile("^(>=|<=|>|<|==)\\s*(-?\\d+(\\.\\d+)?)$");

    // ==================== C4: effect 动词表 ====================
    private static final Map<String, Set<String>> EFFECT_WHITELIST = new HashMap<>();
    static {
        EFFECT_WHITELIST.put("action", new HashSet<>(Arrays.asList(
            "allow", "deny", "require_confirmation", "require_mitigation"
        )));
        
        EFFECT_WHITELIST.put("notification", new HashSet<>(Arrays.asList(
            "deny", "route_p0_native", "route_p1_digest", "route_p2_agent_act"
        )));
        
        EFFECT_WHITELIST.put("call_gate", new HashSet<>(Arrays.asList(
            "deny", "route_ring_native", "route_agent_handle", "route_record_only"
        )));
    }

    // effect 与 route 同族映射
    private static final Map<String, String> EFFECT_ROUTE_MAPPING = new HashMap<>();
    static {
        EFFECT_ROUTE_MAPPING.put("route_p0_native", "P0_NATIVE");
        EFFECT_ROUTE_MAPPING.put("route_p1_digest", "P1_AGENT_SUMMARY");
        EFFECT_ROUTE_MAPPING.put("route_p2_agent_act", "P2_AGENT_HANDLE");
        EFFECT_ROUTE_MAPPING.put("route_ring_native", "RING_NATIVE");
        EFFECT_ROUTE_MAPPING.put("route_agent_handle", "AGENT_HANDLE");
        EFFECT_ROUTE_MAPPING.put("route_record_only", "RECORD_ONLY");
    }

    // ==================== C5: 命名空间源校验 ====================
    // 由外部调用者提供 sourceType（platform/user/learning）

    // ==================== C7: 高风险 effect（禁用 audit:none） ====================
    private static final Set<String> HIGH_RISK_EFFECTS = new HashSet<>(Arrays.asList(
        "route_p2_agent_act", "route_agent_handle"
    ));

    /**
     * 编译结果
     */
    public static class CompilationResult {
        public boolean success;
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<ConflictPair> conflicts = new ArrayList<>();
        
        public CompilationResult(boolean success) {
            this.success = success;
        }
    }

    /**
     * 冲突对（C6）
     */
    public static class ConflictPair {
        public String ruleId1;
        public String ruleId2;
        public String reason;
        
        public ConflictPair(String ruleId1, String ruleId2, String reason) {
            this.ruleId1 = ruleId1;
            this.ruleId2 = ruleId2;
            this.reason = reason;
        }
        
        @Override
        public String toString() {
            return String.format("Conflict: %s <-> %s (%s)", ruleId1, ruleId2, reason);
        }
    }

    /**
     * 解析后的规则
     */
    private static class ParsedRule {
        String id;
        String domain;
        String effect;
        boolean baseline;
        int priority;
        JSONObject when;
        JSONObject then;
        JSONObject subject;
        JSONObject meta;
        String sourceType;  // platform/user/learning
    }

    /**
     * 编译 PolicyPack
     * 
     * @param policyPackJson PolicyPack JSON 字符串
     * @param sourceType 来源类型（platform/user/learning）
     * @return 编译结果
     */
    public CompilationResult compile(String policyPackJson, String sourceType) {
        CompilationResult result = new CompilationResult(true);
        
        try {
            JSONObject policyPack = new JSONObject(policyPackJson);
            
            // 解析规则数组
            if (!policyPack.has("rules")) {
                result.success = false;
                result.errors.add("Missing 'rules' array in PolicyPack");
                return result;
            }
            
            JSONArray rulesArray = policyPack.getJSONArray("rules");
            List<ParsedRule> rules = new ArrayList<>();
            Set<String> ruleIds = new HashSet<>();
            
            // 逐条解析并校验
            for (int i = 0; i < rulesArray.length(); i++) {
                JSONObject ruleJson = rulesArray.getJSONObject(i);
                ParsedRule rule = parseRule(ruleJson, sourceType, result);
                
                if (rule == null) {
                    result.success = false;
                    continue;  // 解析失败，跳过该规则
                }
                
                // C1: id 唯一性检查
                if (ruleIds.contains(rule.id)) {
                    result.success = false;
                    result.errors.add(String.format("Duplicate rule id: %s", rule.id));
                } else {
                    ruleIds.add(rule.id);
                }
                
                rules.add(rule);
            }
            
            if (!result.success) {
                return result;  // 有解析错误，提前返回
            }
            
            // C6: 冲突矩阵检查
            checkConflicts(rules, result);
            
            // C8: 死规则检查（告警）
            checkDeadRules(rules, result);
            
        } catch (JSONException e) {
            result.success = false;
            result.errors.add("JSON parse error: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 解析单条规则
     */
    private ParsedRule parseRule(JSONObject ruleJson, String sourceType, CompilationResult result) {
        ParsedRule rule = new ParsedRule();
        rule.sourceType = sourceType;
        
        try {
            // ==================== C1: id 格式与命名空间检查 ====================
            if (!ruleJson.has("id")) {
                result.errors.add("Missing required field: id");
                return null;
            }
            
            rule.id = ruleJson.getString("id");
            
            // 检查 id 格式
            if (!ID_PATTERN.matcher(rule.id).matches()) {
                result.errors.add(String.format("Invalid id format: %s (must match ^(sys|user|learn)\\.[a-z0-9_.-]{1,64}$)", rule.id));
                return null;
            }
            
            // 提取命名空间
            String namespace = rule.id.substring(0, rule.id.indexOf('.'));
            
            // ==================== C2: 字段必填性检查 ====================
            for (String field : REQUIRED_FIELDS) {
                if (!ruleJson.has(field)) {
                    result.errors.add(String.format("[%s] Missing required field: %s", rule.id, field));
                    return null;
                }
            }
            
            // 检查未知字段
            Iterator<String> keys = ruleJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!REQUIRED_FIELDS.contains(key) && !OPTIONAL_FIELDS.contains(key)) {
                    result.errors.add(String.format("[%s] Unknown field: %s", rule.id, key));
                    return null;
                }
            }
            
            // 解析字段
            rule.domain = ruleJson.optString("domain", "action");
            rule.effect = ruleJson.getString("effect");
            rule.baseline = ruleJson.optBoolean("baseline", false);
            rule.priority = ruleJson.getInt("priority");
            rule.when = ruleJson.getJSONObject("when");
            rule.then = ruleJson.getJSONObject("then");
            
            if (ruleJson.has("subject")) {
                rule.subject = ruleJson.getJSONObject("subject");
            }
            
            if (ruleJson.has("_meta")) {
                rule.meta = ruleJson.getJSONObject("_meta");
            }
            
            // ==================== C5: 命名空间授权校验 ====================
            if (namespace.equals("sys") && !sourceType.equals("platform")) {
                result.errors.add(String.format("[%s] sys.* rules can only come from platform source", rule.id));
                return null;
            }
            
            if (namespace.equals("learn")) {
                // learn.* 必须有 _meta 且 state=suggestion
                if (rule.meta == null) {
                    result.errors.add(String.format("[%s] learn.* rules must have _meta field", rule.id));
                    return null;
                }
                
                String state = rule.meta.optString("state", "");
                if (!state.equals("suggestion")) {
                    result.errors.add(String.format("[%s] learn.* rules must have _meta.state=suggestion", rule.id));
                    return null;
                }
                
                if (!rule.meta.has("needsUserApproval") || !rule.meta.getBoolean("needsUserApproval")) {
                    result.errors.add(String.format("[%s] learn.* rules must have _meta.needsUserApproval=true", rule.id));
                    return null;
                }
            }
            
            // ==================== subject 检查（授权轴必填，路由轴禁止） ====================
            if (rule.domain.equals("action")) {
                if (rule.subject == null) {
                    result.errors.add(String.format("[%s] action domain requires 'subject' field", rule.id));
                    return null;
                }
            } else {
                // 路由轴禁止 subject
                if (rule.subject != null) {
                    result.errors.add(String.format("[%s] %s domain must not have 'subject' field", rule.id, rule.domain));
                    return null;
                }
            }
            
            // ==================== C3: when 键白名单检查 ====================
            if (!checkWhenClause(rule, result)) {
                return null;
            }
            
            // ==================== C4: effect 动词检查 ====================
            if (!checkEffect(rule, result)) {
                return null;
            }
            
            // ==================== C7: audit:none 高风险检查 ====================
            if (rule.then.has("audit") && rule.then.getString("audit").equals("none")) {
                if (HIGH_RISK_EFFECTS.contains(rule.effect)) {
                    result.errors.add(String.format("[%s] audit:none cannot be used with high-risk effect: %s", rule.id, rule.effect));
                    return null;
                }
            }
            
            return rule;
            
        } catch (JSONException e) {
            result.errors.add(String.format("Parse error for rule: %s", e.getMessage()));
            return null;
        }
    }

    /**
     * C3: when 子句白名单检查
     */
    private boolean checkWhenClause(ParsedRule rule, CompilationResult result) {
        Set<String> allowedKeys = WHEN_WHITELIST.get(rule.domain);
        if (allowedKeys == null) {
            result.errors.add(String.format("[%s] Unknown domain: %s", rule.id, rule.domain));
            return false;
        }
        
        try {
            Iterator<String> keys = rule.when.keys();
            Set<String> seenKeys = new HashSet<>();
            
            while (keys.hasNext()) {
                String key = keys.next();
                
                // 检查键重复
                if (seenKeys.contains(key)) {
                    result.errors.add(String.format("[%s] Duplicate when key: %s", rule.id, key));
                    return false;
                }
                seenKeys.add(key);
                
                // 检查键白名单（支持 context.* 前缀匹配）
                boolean keyAllowed = false;
                if (allowedKeys.contains(key)) {
                    keyAllowed = true;
                } else if (key.startsWith("context.")) {
                    // context.* 通配检查
                    for (String allowed : allowedKeys) {
                        if (allowed.startsWith("context.") && allowed.equals(key)) {
                            keyAllowed = true;
                            break;
                        }
                    }
                }
                
                if (!keyAllowed) {
                    result.errors.add(String.format("[%s] when key '%s' not in whitelist for domain '%s'", rule.id, key, rule.domain));
                    return false;
                }
                
                // 检查值枚举
                if (!checkWhenValue(rule, key, rule.when.get(key), result)) {
                    return false;
                }
            }
            
        } catch (JSONException e) {
            result.errors.add(String.format("[%s] when clause error: %s", rule.id, e.getMessage()));
            return false;
        }
        
        return true;
    }

    /**
     * 检查 when 值的合法性（枚举/比较串）
     */
    private boolean checkWhenValue(ParsedRule rule, String key, Object value, CompilationResult result) {
        // senderTrust 枚举检查
        if (key.equals("senderTrust")) {
            if (value instanceof String) {
                if (!SENDER_TRUST_VALUES.contains(value)) {
                    result.errors.add(String.format("[%s] Invalid senderTrust value: %s", rule.id, value));
                    return false;
                }
            } else if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.optString(i);
                    if (!SENDER_TRUST_VALUES.contains(v)) {
                        result.errors.add(String.format("[%s] Invalid senderTrust value in array: %s", rule.id, v));
                        return false;
                    }
                }
            }
        }
        
        // callerClass 枚举检查
        if (key.equals("callerClass") && value instanceof String) {
            if (!CALLER_CLASS_VALUES.contains(value)) {
                result.errors.add(String.format("[%s] Invalid callerClass value: %s", rule.id, value));
                return false;
            }
        }
        
        // callerIs 枚举检查
        if (key.equals("callerIs") && value instanceof String) {
            if (!CALLER_IS_VALUES.contains(value)) {
                result.errors.add(String.format("[%s] Invalid callerIs value: %s", rule.id, value));
                return false;
            }
        }
        
        // gateMode 枚举检查
        if (key.equals("gateMode") && value instanceof String) {
            if (!GATE_MODE_VALUES.contains(value)) {
                result.errors.add(String.format("[%s] Invalid gateMode value: %s", rule.id, value));
                return false;
            }
        }
        
        // 比较串检查
        if (key.equals("classifierConfidence") || key.equals("spamScore") || 
            key.equals("callRepeatedInWindowMs") || key.equals("dailyAiHandleUsed")) {
            if (value instanceof String) {
                String str = (String) value;
                if (!COMPARISON_PATTERN.matcher(str).matches()) {
                    result.errors.add(String.format("[%s] Invalid comparison string for '%s': %s", rule.id, key, str));
                    return false;
                }
            }
        }
        
        return true;
    }

    /**
     * C4: effect 动词与 route 一致性检查
     */
    private boolean checkEffect(ParsedRule rule, CompilationResult result) {
        Set<String> allowedEffects = EFFECT_WHITELIST.get(rule.domain);
        if (allowedEffects == null || !allowedEffects.contains(rule.effect)) {
            result.errors.add(String.format("[%s] effect '%s' not allowed for domain '%s'", rule.id, rule.effect, rule.domain));
            return false;
        }
        
        // 检查 route 与 effect 同族一致性
        if (EFFECT_ROUTE_MAPPING.containsKey(rule.effect)) {
            String expectedRoute = EFFECT_ROUTE_MAPPING.get(rule.effect);
            try {
                if (rule.then.has("route")) {
                    String actualRoute = rule.then.getString("route");
                    if (!actualRoute.equals(expectedRoute)) {
                        result.errors.add(String.format("[%s] route mismatch: effect '%s' requires route '%s', got '%s'", 
                                                         rule.id, rule.effect, expectedRoute, actualRoute));
                        return false;
                    }
                }
            } catch (JSONException e) {
                result.errors.add(String.format("[%s] then.route parse error: %s", rule.id, e.getMessage()));
                return false;
            }
        }
        
        return true;
    }

    /**
     * C6: 冲突矩阵检查
     */
    private void checkConflicts(List<ParsedRule> rules, CompilationResult result) {
        // 按域分组
        Map<String, List<ParsedRule>> rulesByDomain = new HashMap<>();
        for (ParsedRule rule : rules) {
            rulesByDomain.computeIfAbsent(rule.domain, k -> new ArrayList<>()).add(rule);
        }
        
        // 逐域检查冲突
        for (Map.Entry<String, List<ParsedRule>> entry : rulesByDomain.entrySet()) {
            List<ParsedRule> domainRules = entry.getValue();
            
            // 检查 baseline 规则之间的冲突
            List<ParsedRule> baselineRules = new ArrayList<>();
            for (ParsedRule rule : domainRules) {
                if (rule.baseline) {
                    baselineRules.add(rule);
                }
            }
            
            for (int i = 0; i < baselineRules.size(); i++) {
                for (int j = i + 1; j < baselineRules.size(); j++) {
                    ParsedRule r1 = baselineRules.get(i);
                    ParsedRule r2 = baselineRules.get(j);
                    
                    if (whenOverlaps(r1.when, r2.when)) {
                        result.success = false;
                        ConflictPair conflict = new ConflictPair(r1.id, r2.id, "Baseline rules overlap");
                        result.conflicts.add(conflict);
                        result.errors.add(conflict.toString());
                    }
                }
            }
            
            // 检查同 priority 非 baseline 规则冲突
            Map<Integer, List<ParsedRule>> rulesByPriority = new HashMap<>();
            for (ParsedRule rule : domainRules) {
                if (!rule.baseline) {
                    rulesByPriority.computeIfAbsent(rule.priority, k -> new ArrayList<>()).add(rule);
                }
            }
            
            for (Map.Entry<Integer, List<ParsedRule>> priorityEntry : rulesByPriority.entrySet()) {
                List<ParsedRule> samePriorityRules = priorityEntry.getValue();
                
                if (samePriorityRules.size() > 1) {
                    for (int i = 0; i < samePriorityRules.size(); i++) {
                        for (int j = i + 1; j < samePriorityRules.size(); j++) {
                            ParsedRule r1 = samePriorityRules.get(i);
                            ParsedRule r2 = samePriorityRules.get(j);
                            
                            if (whenOverlaps(r1.when, r2.when) && !r1.effect.equals(r2.effect)) {
                                result.success = false;
                                ConflictPair conflict = new ConflictPair(r1.id, r2.id, 
                                    String.format("Same priority (%d), overlapping when, different effects (%s vs %s)", 
                                                  priorityEntry.getKey(), r1.effect, r2.effect));
                                result.conflicts.add(conflict);
                                result.errors.add(conflict.toString());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 检查两个 when 子句是否重叠（简化版，检查是否有共同键且值相交）
     */
    private boolean whenOverlaps(JSONObject when1, JSONObject when2) {
        try {
            Iterator<String> keys1 = when1.keys();
            while (keys1.hasNext()) {
                String key = keys1.next();
                if (when2.has(key)) {
                    Object v1 = when1.get(key);
                    Object v2 = when2.get(key);
                    
                    // 简化判断：如果值相等或都包含数组且有交集，视为重叠
                    if (v1.equals(v2)) {
                        return true;
                    }
                    
                    // 数组交集检查
                    if (v1 instanceof JSONArray && v2 instanceof JSONArray) {
                        JSONArray arr1 = (JSONArray) v1;
                        JSONArray arr2 = (JSONArray) v2;
                        for (int i = 0; i < arr1.length(); i++) {
                            for (int j = 0; j < arr2.length(); j++) {
                                if (arr1.opt(i).equals(arr2.opt(j))) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Error checking when overlap", e);
        }
        
        return false;
    }

    /**
     * C8: 死规则检查（告警）
     * 
     * 死规则定义：存在更高优先级规则完全覆盖且结论互斥
     */
    private void checkDeadRules(List<ParsedRule> rules, CompilationResult result) {
        // 按域分组
        Map<String, List<ParsedRule>> rulesByDomain = new HashMap<>();
        for (ParsedRule rule : rules) {
            rulesByDomain.computeIfAbsent(rule.domain, k -> new ArrayList<>()).add(rule);
        }
        
        for (Map.Entry<String, List<ParsedRule>> entry : rulesByDomain.entrySet()) {
            List<ParsedRule> domainRules = new ArrayList<>(entry.getValue());
            
            // 按优先级降序排序
            domainRules.sort((r1, r2) -> Integer.compare(r2.priority, r1.priority));
            
            for (int i = 0; i < domainRules.size(); i++) {
                ParsedRule currentRule = domainRules.get(i);
                
                // 检查是否被更高优先级规则覆盖
                for (int j = 0; j < i; j++) {
                    ParsedRule higherRule = domainRules.get(j);
                    
                    if (whenSubsumes(higherRule.when, currentRule.when) && 
                        !higherRule.effect.equals(currentRule.effect)) {
                        result.warnings.add(String.format("Dead rule: %s is subsumed by higher-priority rule %s with different effect", 
                                                          currentRule.id, higherRule.id));
                    }
                }
            }
        }
    }

    /**
     * 检查 when1 是否包含 when2（简化版：when1 的所有键在 when2 中且值相同）
     */
    private boolean whenSubsumes(JSONObject when1, JSONObject when2) {
        try {
            Iterator<String> keys2 = when2.keys();
            while (keys2.hasNext()) {
                String key = keys2.next();
                if (!when1.has(key)) {
                    return false;  // when1 缺少 when2 的键
                }
                
                Object v1 = when1.get(key);
                Object v2 = when2.get(key);
                
                if (!v1.equals(v2)) {
                    return false;  // 值不同
                }
            }
            
            return true;  // when1 包含 when2 的所有条件
            
        } catch (JSONException e) {
            Log.w(TAG, "Error checking when subsumption", e);
            return false;
        }
    }
}
