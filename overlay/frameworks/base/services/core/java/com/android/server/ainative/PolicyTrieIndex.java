package com.android.server.ainative;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略规则 trie 索引（A7.1 规则 trie 编译）
 *
 * 结构为两级字典树：root → whenKey → token → List&lt;CompiledPolicyRule&gt;。
 * 每条规则按其 when 条件的 indexTokens 进桶；数值比较/布尔条件无精确 token，
 * 落入该键的 "*" 兜底桶。
 *
 * 求值流程：
 *  1. 遍历事实键，取 token=事实值 与 "*" 桶 → 候选规则（LinkedHashSet 去重，保持装载序）
 *  2. 候选按 (baseline 优先, priority 降序) 由调用方保证的装载序排列
 *  3. 对候选做 {@link CompiledPolicyRule#matches(Map)} 全条件精确求值，首个命中胜出
 *
 * trie 不可变：构造完成后只读，换入新规则集 = 整树重建后原子替换引用
 * （配合 PolicyRuleEvaluator 双缓冲实现失败回退）。
 */
public class PolicyTrieIndex {

    /** root[whenKey][token] → 规则列表（token "*" 为兜底桶） */
    private final Map<String, Map<String, List<CompiledPolicyRule>>> mRoot =
            new ConcurrentHashMap<>();

    /** 规则总数 */
    private final int mRuleCount;

    public PolicyTrieIndex(List<CompiledPolicyRule> rules) {
        int count = 0;
        // 装载序在进入前已按 (baseline 优先, priority 降序) 排好，
        // 桶内追加即保持该次序，保证同候选命中返回最高优先级规则。
        for (CompiledPolicyRule rule : rules) {
            insert(rule);
            count++;
        }
        mRuleCount = count;
    }

    private void insert(CompiledPolicyRule rule) {
        for (CompiledPolicyRule.Condition c : rule.conditions) {
            Set<String> tokens = c.indexTokens();
            if (tokens == null || tokens.isEmpty()) {
                continue;
            }
            Map<String, List<CompiledPolicyRule>> keyNode =
                    mRoot.computeIfAbsent(c.key(), k -> new ConcurrentHashMap<>());
            for (String token : tokens) {
                keyNode.computeIfAbsent(token, k -> new ArrayList<>()).add(rule);
            }
        }
    }

    /**
     * 依据事实收集候选规则（有序、去重）。
     *
     * 对每个事实键：取 [事实字符串值, "*"] 两个 token 的规则并入候选；
     * 布尔键事实 "true"/"false" 亦匹配其专用 token（BoolCondition 同时挂了
     * 精确值与 "*" 两个桶）。
     */
    public List<CompiledPolicyRule> candidates(Map<String, Object> facts) {
        Set<CompiledPolicyRule> out = new LinkedHashSet<>();
        if (facts == null || facts.isEmpty()) {
            return new ArrayList<>();
        }
        for (Map.Entry<String, Object> e : facts.entrySet()) {
            Map<String, List<CompiledPolicyRule>> keyNode = mRoot.get(e.getKey());
            if (keyNode == null) {
                continue;
            }
            Object v = e.getValue();
            collect(keyNode, tokenOf(v), out);
            collect(keyNode, "*", out);
        }
        return new ArrayList<>(out);
    }

    private static String tokenOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static void collect(Map<String, List<CompiledPolicyRule>> keyNode,
                                String token, Set<CompiledPolicyRule> out) {
        if (token == null) {
            return;
        }
        List<CompiledPolicyRule> bucket = keyNode.get(token);
        if (bucket != null) {
            out.addAll(bucket);
        }
    }

    public int getRuleCount() {
        return mRuleCount;
    }
}
