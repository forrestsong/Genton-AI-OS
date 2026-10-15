package com.android.server.ainative;

import android.util.Slog;

import com.ainative.policy.PolicyDecision;
import com.ainative.policy.PolicyRuleView;
import com.ainative.policy.TaintMapEntry;
import com.ainative.policy.IPolicyEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略规则求值器（Policy Rule Evaluator）
 * 
 * 核心职责：
 * 1. 加载 DSL 规则（baseline + custom）
 * 2. 两阶段求值（baseline 优先，不可覆盖）
 * 3. Taint 冲突检测（策略 argSources vs 实际参数）
 * 4. Deny 默认（无匹配规则 = DENY）
 * 
 * 求值逻辑：
 * 1. Baseline 规则匹配（deny > confirmation > allow）
 * 2. 如 baseline 有裁决 → 直接返回（不可覆盖）
 * 3. Custom 规则匹配（按 priority 降序）
 * 4. 无匹配规则 → DENY
 * 
 * 规则结构（简化 DSL 运行时）：
 * - ruleId: 唯一标识符
 * - baseline: true/false
 * - priority: 优先级（数值越大越优先）
 * - capability: 匹配的能力名（支持通配符）
 * - effect: grant/deny/require_confirmation/require_mitigation
 * - argSources: taint 约束（可选）
 * 
 * 性能优化：
 * - 规则索引：capability → List<Rule>
 * - 基线规则分离存储（优先查询）
 * - Taint 检查延迟到最后（避免无谓计算）
 * 
 * @see PolicyEngineService
 */
class PolicyRuleEvaluator {
    private static final String TAG = "PolicyRuleEvaluator";
    
    // 规则存储（capability → List<Rule>）
    private final ConcurrentHashMap<String, List<PolicyRule>> mBaselineRules;
    private final ConcurrentHashMap<String, List<PolicyRule>> mCustomRules;
    
    // 通配符规则（* 匹配全部能力）
    private final List<PolicyRule> mBaselineWildcards;
    private final List<PolicyRule> mCustomWildcards;
    
    // ============ 路由域规则集（A7.1 装载口子：双缓冲原子换入，失败保留旧档）============
    // 活跃档：domain → (规则列表, trie 索引, 策略版本)。换入新档 = 先构建新的列表+trie，
    // 再一次性替换引用；任一构建/校验失败不落档，服务进程继续使用旧档（fail-safe 回退）。
    private final Map<String, List<CompiledPolicyRule>> mActiveDomainRules = new ConcurrentHashMap<>();
    private final Map<String, PolicyTrieIndex> mActiveDomainTries = new ConcurrentHashMap<>();
    private final Map<String, String> mActiveDomainVersions = new ConcurrentHashMap<>();
    
    PolicyRuleEvaluator() {
        mBaselineRules = new ConcurrentHashMap<>();
        mCustomRules = new ConcurrentHashMap<>();
        mBaselineWildcards = new ArrayList<>();
        mCustomWildcards = new ArrayList<>();
    }
    
    // ==================== 路由域装载（A7.1） ====================
    
    /**
     * 原子换入一个域的策略集（双缓冲）。
     *
     * @param domain notification / call_gate
     * @param rules  解析器已按 (baseline 优先 → priority 降序) 排好的编译规则
     * @param version 策略版本
     * @return true=已落档；false=入参非法（空域/空列表），保持旧档
     */
    synchronized boolean loadDomainSet(String domain, List<CompiledPolicyRule> rules, String version) {
        if (domain == null || rules == null) {
            return false;
        }
        // 先构建新档（候选新 trie），成功后再切换引用
        List<CompiledPolicyRule> newRules = new ArrayList<>(rules);
        PolicyTrieIndex newTrie = new PolicyTrieIndex(newRules);
        
        if (newRules.isEmpty()) {
            mActiveDomainRules.remove(domain);
            mActiveDomainTries.remove(domain);
            mActiveDomainVersions.remove(domain);
            return true;
        }
        
        mActiveDomainRules.put(domain, newRules);
        mActiveDomainTries.put(domain, newTrie);
        mActiveDomainVersions.put(domain, version == null ? "" : version);
        Slog.i(TAG, "Domain set loaded: domain=" + domain + ", rules=" + newRules.size()
                + ", version=" + version);
        return true;
    }
    
    /**
     * 域匹配求值结果。
     */
    public static class DomainMatch {
        /** 命中的规则（null=无命中，需走域默认路由） */
        public CompiledPolicyRule rule;
        
        /** 该域当前是否有已装载的策略版本 */
        public boolean domainActive;
        
        DomainMatch() {
        }
    }
    
    /**
     * 路由域求值：trie 候选 → 全条件精确验证 → 首个命中胜出。
     * 命中规则会记录 hit/lastHit 供治理展示。
     */
    public DomainMatch matchDomain(String domain, Map<String, Object> facts) {
        DomainMatch out = new DomainMatch();
        PolicyTrieIndex trie = mActiveDomainTries.get(domain);
        if (trie == null) {
            return out;  // 未装载 = 域不活跃 → 走 fail-safe 默认路由
        }
        out.domainActive = true;
        
        List<CompiledPolicyRule> candidates = trie.candidates(facts);
        // 候选来自不同事实键桶，显式按优先级重排（trie 桶序不保证全局序）
        candidates.sort(RULE_ORDER);
        long nowNanos = System.nanoTime();
        for (CompiledPolicyRule rule : candidates) {
            if (rule.matches(facts)) {
                rule.recordHit(nowNanos);
                out.rule = rule;
                return out;
            }
        }
        return out;
    }
    
    /** 活跃域规则全量（治理/编辑回显用，返回快照副本）。 */
    public List<CompiledPolicyRule> getActiveRules(String domain) {
        List<CompiledPolicyRule> list = mActiveDomainRules.get(domain);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }
    
    /** 活跃域规则视图（AIDL listActiveRules 用）。 */
    public PolicyRuleView[] getActiveRuleViews(String domain) {
        List<CompiledPolicyRule> list = mActiveDomainRules.get(domain);
        if (list == null) {
            return new PolicyRuleView[0];
        }
        List<PolicyRuleView> views = new ArrayList<>();
        for (CompiledPolicyRule rule : list) {
            PolicyRuleView view = new PolicyRuleView();
            view.ruleId = rule.id;
            view.baseline = rule.baseline;
            view.capability = rule.domain;
            view.effect = rule.effect;
            view.hitCount = rule.getHitCount();
            view.lastHitAtRealtimeNanos = rule.getLastHitNanos();
            views.add(view);
        }
        return views.toArray(new PolicyRuleView[0]);
    }
    
    /** 域当前版本（null=未装载）。 */
    public String getActiveDomainVersion(String domain) {
        return mActiveDomainVersions.get(domain);
    }
    
    /** 已装载域集合。 */
    public List<String> getActiveDomains() {
        return new ArrayList<>(mActiveDomainRules.keySet());
    }
    
    /** 求值排序：baseline 优先 → priority 降序 → id 字典序（与装载一致）。 */
    private static final Comparator<CompiledPolicyRule> RULE_ORDER =
            Comparator.comparing(CompiledPolicyRule::isBaselineFlag, Comparator.reverseOrder())
                    .thenComparing(CompiledPolicyRule::getPriority, Comparator.reverseOrder())
                    .thenComparing(CompiledPolicyRule::getId);
    
    /**
     * 加载基线策略（从编译后的 DSL 或硬编码规则）
     */
    void loadBaseline() {
        // 硬编码基线规则（生产环境：从编译后的 DSL 加载）
        
        // 规则 1：支付动作强制确认
        addBaselineRule(new PolicyRule(
            "sys.action.baseline.payment-confirm",
            true,  // baseline
            1000,  // priority
            "agent.payment_action",
            PolicyRule.Effect.REQUIRE_CONFIRMATION,
            null,  // argSources
            "支付类动作强制用户确认，不可覆盖"
        ));
        
        // 规则 2：数据分享强制确认
        addBaselineRule(new PolicyRule(
            "sys.action.baseline.data-share-confirm",
            true,
            1000,
            "agent.share_data",
            PolicyRule.Effect.REQUIRE_CONFIRMATION,
            null,
            "数据分享强制用户确认"
        ));
        
        // 规则 3：应用安装强制确认
        addBaselineRule(new PolicyRule(
            "sys.action.baseline.app-install-confirm",
            true,
            1000,
            "agent.install_package",
            PolicyRule.Effect.REQUIRE_CONFIRMATION,
            null,
            "应用安装强制用户确认"
        ));
        
        // 规则 4：系统设置修改强制确认
        addBaselineRule(new PolicyRule(
            "sys.action.baseline.settings-modify-confirm",
            true,
            1000,
            "agent.modify_settings",
            PolicyRule.Effect.REQUIRE_CONFIRMATION,
            null,
            "系统设置修改强制用户确认"
        ));
        
        // 规则 5：低风险能力自动授权
        addBaselineRule(new PolicyRule(
            "sys.action.baseline.low-risk-allow",
            true,
            100,
            "agent.read_contacts",
            PolicyRule.Effect.GRANT,
            null,
            "读取联系人低风险自动授权"
        ));
        
        addBaselineRule(new PolicyRule(
            "sys.action.baseline.search-allow",
            true,
            100,
            "agent.search_content",
            PolicyRule.Effect.GRANT,
            null,
            "搜索内容低风险自动授权"
        ));
        
        Slog.i(TAG, "Baseline rules loaded: " + getBaselineRulesCount());
    }
    
    /**
     * 评估能力请求
     * 
     * @param agentId Agent ID
     * @param intentId Intent ID
     * @param capability 能力名
     * @param argTaints 参数 taint（可选）
     * @return 裁决结果
     */
    PolicyDecision evaluate(String agentId, String intentId, String capability, 
                           TaintMapEntry[] argTaints) {
        PolicyDecision decision = new PolicyDecision();
        
        try {
            // 阶段 1：Baseline 规则求值
            PolicyRule matchedBaselineRule = matchRule(capability, mBaselineRules, mBaselineWildcards);
            if (matchedBaselineRule != null) {
                // Baseline 规则命中 → 直接返回（不可覆盖）
                decision.decision = effectToDecision(matchedBaselineRule.effect);
                decision.reasons = new String[]{matchedBaselineRule.ruleId + ": " + matchedBaselineRule.description};
                
                // Taint 冲突检测（仅对 GRANT 规则）
                if (matchedBaselineRule.effect == PolicyRule.Effect.GRANT && argTaints != null) {
                    String taintConflict = checkTaintConflict(matchedBaselineRule, argTaints);
                    if (taintConflict != null) {
                        decision.decision = IPolicyEngine.DEC_REQUIRE_MITIGATION;
                        decision.requiredMitigations = new String[]{taintConflict};
                    }
                }
                
                return decision;
            }
            
            // 阶段 2：Custom 规则求值
            PolicyRule matchedCustomRule = matchRule(capability, mCustomRules, mCustomWildcards);
            if (matchedCustomRule != null) {
                decision.decision = effectToDecision(matchedCustomRule.effect);
                decision.reasons = new String[]{matchedCustomRule.ruleId + ": " + matchedCustomRule.description};
                
                // Taint 冲突检测
                if (matchedCustomRule.effect == PolicyRule.Effect.GRANT && argTaints != null) {
                    String taintConflict = checkTaintConflict(matchedCustomRule, argTaints);
                    if (taintConflict != null) {
                        decision.decision = IPolicyEngine.DEC_REQUIRE_MITIGATION;
                        decision.requiredMitigations = new String[]{taintConflict};
                    }
                }
                
                return decision;
            }
            
            // 阶段 3：Deny 默认（无匹配规则 = DENY）
            decision.decision = IPolicyEngine.DEC_DENY;
            decision.reasons = new String[]{"No matching rule found (deny by default)"};
            return decision;
            
        } catch (Exception e) {
            Slog.e(TAG, "Rule evaluation failed", e);
            decision.decision = IPolicyEngine.DEC_DENY;
            decision.reasons = new String[]{"Evaluation error: " + e.getMessage()};
            return decision;
        }
    }
    
    /**
     * 匹配规则（按 priority 降序）
     */
    private PolicyRule matchRule(String capability, 
                                ConcurrentHashMap<String, List<PolicyRule>> ruleMap,
                                List<PolicyRule> wildcardRules) {
        // 1. 精确匹配
        List<PolicyRule> exactMatches = ruleMap.get(capability);
        if (exactMatches != null && !exactMatches.isEmpty()) {
            return exactMatches.get(0); // 已按 priority 排序
        }
        
        // 2. 通配符匹配
        if (!wildcardRules.isEmpty()) {
            return wildcardRules.get(0); // 已按 priority 排序
        }
        
        return null;
    }
    
    /**
     * Taint 冲突检测
     * 
     * 检查实际参数 taint 是否符合策略声明的 argSources 约束。
     * 
     * @param rule 匹配的规则
     * @param argTaints 实际参数 taint
     * @return 冲突描述（null = 无冲突）
     */
    private String checkTaintConflict(PolicyRule rule, TaintMapEntry[] argTaints) {
        // 规则未声明 argSources = 不限制
        if (rule.argSources == null || rule.argSources.isEmpty()) {
            return null;
        }
        
        // 检查每个参数的 taint 级别
        for (TaintMapEntry taint : argTaints) {
            Integer allowedTrust = rule.argSources.get(taint.argKey);
            if (allowedTrust != null && taint.trust > allowedTrust) {
                return String.format("Taint conflict: arg '%s' trust=%d exceeds allowed=%d",
                    taint.argKey, taint.trust, allowedTrust);
            }
        }
        
        return null;
    }
    
    /**
     * 转换 Effect 到 Decision
     */
    private int effectToDecision(PolicyRule.Effect effect) {
        switch (effect) {
            case GRANT:
                return IPolicyEngine.DEC_GRANT;
            case DENY:
                return IPolicyEngine.DEC_DENY;
            case REQUIRE_CONFIRMATION:
                return IPolicyEngine.DEC_REQUIRE_CONFIRMATION;
            case REQUIRE_MITIGATION:
                return IPolicyEngine.DEC_REQUIRE_MITIGATION;
            default:
                return IPolicyEngine.DEC_DENY;
        }
    }
    
    /**
     * 添加基线规则
     */
    private void addBaselineRule(PolicyRule rule) {
        if ("*".equals(rule.capability)) {
            mBaselineWildcards.add(rule);
            // 按 priority 降序排序
            mBaselineWildcards.sort((a, b) -> Integer.compare(b.priority, a.priority));
        } else {
            mBaselineRules.computeIfAbsent(rule.capability, k -> new ArrayList<>()).add(rule);
            // 按 priority 降序排序
            mBaselineRules.get(rule.capability).sort((a, b) -> Integer.compare(b.priority, a.priority));
        }
    }
    
    /**
     * 获取适用的规则视图（治理界面）
     */
    PolicyRuleView[] getApplicableRules(String agentId, String bundleVersion) {
        List<PolicyRuleView> views = new ArrayList<>();
        
        // 遍历所有基线规则
        for (List<PolicyRule> rules : mBaselineRules.values()) {
            for (PolicyRule rule : rules) {
                views.add(ruleToView(rule, bundleVersion));
            }
        }
        
        for (PolicyRule rule : mBaselineWildcards) {
            views.add(ruleToView(rule, bundleVersion));
        }
        
        // 遍历所有自定义规则
        for (List<PolicyRule> rules : mCustomRules.values()) {
            for (PolicyRule rule : rules) {
                views.add(ruleToView(rule, bundleVersion));
            }
        }
        
        for (PolicyRule rule : mCustomWildcards) {
            views.add(ruleToView(rule, bundleVersion));
        }
        
        return views.toArray(new PolicyRuleView[0]);
    }
    
    /**
     * 规则转视图
     */
    private PolicyRuleView ruleToView(PolicyRule rule, String bundleVersion) {
        PolicyRuleView view = new PolicyRuleView();
        view.ruleId = rule.ruleId;
        view.summary = rule.description;
        view.priority = rule.priority;
        view.isBaseline = rule.baseline;
        view.namespace = rule.ruleId.startsWith("sys.") ? "sys." : "user.";
        view.affectsCapabilities = new String[]{rule.capability};
        view.bundleVersion = bundleVersion;
        view.effectiveSinceMs = System.currentTimeMillis();
        return view;
    }
    
    /**
     * 获取规则总数
     */
    int getTotalRulesCount() {
        return getBaselineRulesCount() + getCustomRulesCount();
    }
    
    /**
     * 获取基线规则数
     */
    int getBaselineRulesCount() {
        int count = mBaselineWildcards.size();
        for (List<PolicyRule> rules : mBaselineRules.values()) {
            count += rules.size();
        }
        return count;
    }
    
    /**
     * 获取自定义规则数
     */
    int getCustomRulesCount() {
        int count = mCustomWildcards.size();
        for (List<PolicyRule> rules : mCustomRules.values()) {
            count += rules.size();
        }
        return count;
    }
    
    // ========== 内部类 ==========
    
    /**
     * 策略规则（简化 DSL 运行时表示）
     */
    static class PolicyRule {
        enum Effect {
            GRANT,
            DENY,
            REQUIRE_CONFIRMATION,
            REQUIRE_MITIGATION
        }
        
        final String ruleId;
        final boolean baseline;
        final int priority;
        final String capability;           // 能力名（支持 * 通配符）
        final Effect effect;
        final Map<String, Integer> argSources; // argKey -> 允许的最大 trust 级别
        final String description;
        
        PolicyRule(String ruleId, boolean baseline, int priority, String capability,
                  Effect effect, Map<String, Integer> argSources, String description) {
            this.ruleId = ruleId;
            this.baseline = baseline;
            this.priority = priority;
            this.capability = capability;
            this.effect = effect;
            this.argSources = argSources;
            this.description = description;
        }
    }
}
