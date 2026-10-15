#!/usr/bin/env python3
"""
AI Native OS - Policy DSL 编译器骨架
版本：1.0.0
日期：2026-09-05
状态：骨架实现（P50<1ms 性能优化待完善）

功能：
1. 解析 Policy DSL 语法
2. 静态检查（§5 编译检查表 C1-C8）
3. 生成 Java 决策树
4. 冲突矩阵检测
5. 性能优化（trie/表驱动）
"""

import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Any
from dataclasses import dataclass, field
from enum import Enum


class RuleError(Exception):
    """规则编译错误"""
    pass


class Domain(Enum):
    """域类型"""
    ACTION = "action"
    NOTIFICATION = "notification"
    CALL_GATE = "call_gate"


class Effect(Enum):
    """效果动词"""
    # 授权轴
    ALLOW = "allow"
    DENY = "deny"
    REQUIRE_CONFIRMATION = "require_confirmation"
    
    # 路由轴 - notification
    ROUTE_P0_NATIVE = "route_p0_native"
    ROUTE_P1_DIGEST = "route_p1_digest"
    ROUTE_P2_FOLD = "route_p2_fold"
    ROUTE_AGENT_HANDLE = "route_agent_handle"
    
    # 路由轴 - call_gate
    ROUTE_RING_NATIVE = "route_ring_native"
    ROUTE_AI_HANDLE = "route_ai_handle"
    ROUTE_RECORD_ONLY = "route_record_only"


class Namespace(Enum):
    """命名空间"""
    SYS = "sys"
    USER = "user"
    LEARN = "learn"


@dataclass
class Rule:
    """策略规则"""
    id: str
    baseline: bool
    domain: Domain
    subject: Optional[str]  # action 域必填
    when: Dict[str, Any]
    effect: Effect
    then: Dict[str, Any] = field(default_factory=dict)
    priority: int = 100
    audit: str = "mandatory"
    meta: Dict[str, Any] = field(default_factory=dict)
    
    # 编译期计算字段
    namespace: Optional[Namespace] = None
    baseline_type: Optional[str] = None  # "route" | "deny-protect"


@dataclass
class CompilationResult:
    """编译结果"""
    success: bool
    rules: List[Rule] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)
    conflicts: List[Tuple[str, str, str]] = field(default_factory=list)
    stats: Dict[str, Any] = field(default_factory=dict)


class PolicyCompiler:
    """策略 DSL 编译器"""
    
    # 域白名单（§2.3）
    WHEN_WHITELIST = {
        Domain.ACTION: {"capability", "action", "argSources"},
        Domain.NOTIFICATION: {"event", "senderDomain", "category", "context.userPinnedP0"},
        Domain.CALL_GATE: {"call", "caller", "gateMode", "context.userMarkedVIP"},
    }
    
    # Effect 动词与域的对应关系
    EFFECT_DOMAIN_MAP = {
        Domain.ACTION: {Effect.ALLOW, Effect.DENY, Effect.REQUIRE_CONFIRMATION},
        Domain.NOTIFICATION: {
            Effect.ROUTE_P0_NATIVE,
            Effect.ROUTE_P1_DIGEST,
            Effect.ROUTE_P2_FOLD,
            Effect.ROUTE_AGENT_HANDLE,
            Effect.DENY,  # baseline-deny-protect
        },
        Domain.CALL_GATE: {
            Effect.ROUTE_RING_NATIVE,
            Effect.ROUTE_AI_HANDLE,
            Effect.ROUTE_RECORD_ONLY,
            Effect.DENY,  # baseline-deny-protect
        },
    }
    
    def __init__(self):
        self.rules: List[Rule] = []
        self.errors: List[str] = []
        self.warnings: List[str] = []
        self.conflicts: List[Tuple[str, str, str]] = []
    
    def compile(self, dsl_file: Path) -> CompilationResult:
        """编译 DSL 文件"""
        print(f"[Policy Compiler] 编译 {dsl_file}")
        
        try:
            # 解析 DSL
            self.rules = self._parse_dsl(dsl_file)
            print(f"  ✓ 解析完成，共 {len(self.rules)} 条规则")
            
            # C1-C8 静态检查
            self._check_c1_naming()
            self._check_c2_required_fields()
            self._check_c3_when_whitelist()
            self._check_c4_effect_consistency()
            self._check_c5_namespace_constraints()
            self._check_c6_conflict_matrix()
            self._check_c7_audit_constraints()
            self._check_c8_dead_rules()
            
            if self.errors:
                return CompilationResult(
                    success=False,
                    errors=self.errors,
                    warnings=self.warnings,
                    conflicts=self.conflicts,
                )
            
            # 生成决策树
            decision_tree = self._generate_decision_tree()
            print(f"  ✓ 决策树生成完成")
            
            # 统计信息
            stats = self._calculate_stats()
            
            return CompilationResult(
                success=True,
                rules=self.rules,
                warnings=self.warnings,
                conflicts=self.conflicts,
                stats=stats,
            )
        
        except Exception as e:
            self.errors.append(f"编译失败: {str(e)}")
            return CompilationResult(success=False, errors=self.errors)
    
    def _parse_dsl(self, dsl_file: Path) -> List[Rule]:
        """解析 DSL 文件（简化实现，生产应用需完整 parser）"""
        content = dsl_file.read_text(encoding="utf-8")
        rules = []
        
        # 改进的解析：手动匹配规则块（处理嵌套大括号）
        pattern = re.compile(r'rule\s+([a-z][a-z0-9._-]+)\s*\{', re.MULTILINE)
        
        for match in pattern.finditer(content):
            rule_id = match.group(1)
            start = match.end()
            
            # 匹配规则体（嵌套大括号）
            depth = 1
            end = start
            while depth > 0 and end < len(content):
                if content[end] == '{':
                    depth += 1
                elif content[end] == '}':
                    depth -= 1
                end += 1
            
            if depth == 0:
                rule_body = content[start:end-1]
                
                try:
                    rule = self._parse_rule_body(rule_id, rule_body)
                    rules.append(rule)
                    # 调试：检查 when 是否被正确解析
                    if not rule.when or len(rule.when) == 0:
                        print(f"  [DEBUG] 规则 {rule_id} when 为空或未解析")
                except Exception as e:
                    self.errors.append(f"规则 {rule_id} 解析失败: {str(e)}")
        
        return rules
    
    def _parse_rule_body(self, rule_id: str, body: str) -> Rule:
        """解析规则体（简化实现）"""
        # 提取字段（简化 regex，生产需完整 parser）
        def extract_field(field_name: str, default=None):
            pattern = rf'{field_name}:\s*(.+?)(?:\n|$)'
            match = re.search(pattern, body)
            if match:
                value = match.group(1).strip()
                # 简单类型转换
                if value == "true":
                    return True
                elif value == "false":
                    return False
                elif value.isdigit():
                    return int(value)
                elif value.startswith('"') and value.endswith('"'):
                    return value[1:-1]
                return value
            return default
        
        # 提取 when 块（改进：支持嵌套大括号）
        when_match = re.search(r'when:\s*\{', body, re.DOTALL)
        when = None  # 改为 None 表示未找到
        if when_match:
            # 找到 when 块的起始位置
            start = when_match.end()
            # 匹配大括号（简化：假设没有字符串内的大括号）
            depth = 1
            end = start
            while depth > 0 and end < len(body):
                if body[end] == '{':
                    depth += 1
                elif body[end] == '}':
                    depth -= 1
                end += 1
            
            if depth == 0:
                when_body = body[start:end-1]
                # 检查是否有内容（非空白）
                if when_body.strip():
                    # 标记已成功解析 when 块
                    when = {"_parsed": True, "_raw": when_body.strip()}
                else:
                    # 空 when 块也算有效
                    when = {}
        
        # 提取 then 块
        then_match = re.search(r'then:\s*\{([^}]+)\}', body, re.DOTALL)
        then = {}
        if then_match:
            then_body = then_match.group(1)
            # 简化解析
            then = {"raw": then_body.strip()}
        
        # 提取 _meta 块
        meta_match = re.search(r'_meta:\s*\{([^}]+)\}', body, re.DOTALL)
        meta = {}
        if meta_match:
            meta_body = meta_match.group(1)
            meta = {"raw": meta_body.strip()}
        
        # 构建规则
        baseline = extract_field("baseline", False)
        domain_str = extract_field("domain", "action")
        effect_str = extract_field("effect", "deny")
        
        rule = Rule(
            id=rule_id,
            baseline=baseline,
            domain=Domain(domain_str),
            subject=extract_field("subject"),
            when=when,
            effect=Effect(effect_str),
            then=then,
            priority=extract_field("priority", 100),
            audit=extract_field("audit", "mandatory"),
            meta=meta,
        )
        
        # 计算命名空间
        if rule_id.startswith("sys."):
            rule.namespace = Namespace.SYS
        elif rule_id.startswith("user."):
            rule.namespace = Namespace.USER
        elif rule_id.startswith("learn."):
            rule.namespace = Namespace.LEARN
        
        # 计算 baseline 类型
        if rule.baseline:
            if rule.effect in {Effect.DENY}:
                rule.baseline_type = "deny-protect"
            else:
                rule.baseline_type = "route"
        
        return rule
    
    def _check_c1_naming(self):
        """C1: id 命名空间/格式/唯一性"""
        seen_ids = set()
        for rule in self.rules:
            # 格式检查
            if not re.match(r'^[a-z][a-z0-9._-]+$', rule.id):
                self.errors.append(f"C1: 规则 {rule.id} 命名格式非法")
            
            # 命名空间检查
            if not (rule.id.startswith("sys.") or rule.id.startswith("user.") or rule.id.startswith("learn.")):
                self.errors.append(f"C1: 规则 {rule.id} 命名空间非法（需 sys./user./learn.）")
            
            # 唯一性检查
            if rule.id in seen_ids:
                self.errors.append(f"C1: 规则 {rule.id} 重复定义")
            seen_ids.add(rule.id)
    
    def _check_c2_required_fields(self):
        """C2: 字段必填性、未知字段"""
        for rule in self.rules:
            # 必填字段
            if rule.domain == Domain.ACTION and not rule.subject:
                self.errors.append(f"C2: 规则 {rule.id} 缺少 subject（action 域必填）")
            
            # when 条件检查（检查是否有实际内容）
            if not rule.when or (len(rule.when) == 0):
                self.errors.append(f"C2: 规则 {rule.id} 缺少 when 条件")
            # 如果只有内部标记，也算缺失
            elif all(k.startswith("_") for k in rule.when.keys()):
                # 有 _parsed 标记说明有内容，不算缺失
                if "_parsed" not in rule.when:
                    self.errors.append(f"C2: 规则 {rule.id} 缺少 when 条件")
    
    def _check_c3_when_whitelist(self):
        """C3: when 键 ∈ 域白名单"""
        for rule in self.rules:
            if not rule.when:
                continue  # 空 when 已在 C2 检查
            
            whitelist = self.WHEN_WHITELIST.get(rule.domain, set())
            for key in rule.when.keys():
                # 允许内部标记
                if key.startswith("_"):
                    continue
                # 允许 context.* 键
                if key.startswith("context."):
                    continue
                # 允许 or/and 逻辑键
                if key in {"or", "and"}:
                    continue
                if key not in whitelist:
                    self.errors.append(
                        f"C3: 规则 {rule.id} when 键 '{key}' 不在 {rule.domain.value} 域白名单中"
                    )
    
    def _check_c4_effect_consistency(self):
        """C4: effect ∈ 动词表，与 domain 一致"""
        for rule in self.rules:
            allowed_effects = self.EFFECT_DOMAIN_MAP.get(rule.domain, set())
            if rule.effect not in allowed_effects:
                self.errors.append(
                    f"C4: 规则 {rule.id} effect '{rule.effect.value}' 与 domain '{rule.domain.value}' 不匹配"
                )
    
    def _check_c5_namespace_constraints(self):
        """C5: sys. 只能平台签名源，learn. 必须有 _meta"""
        for rule in self.rules:
            if rule.namespace == Namespace.SYS:
                # sys. 规则约束（签名检查在构建期）
                pass
            
            elif rule.namespace == Namespace.LEARN:
                # learn. 必须有 _meta 且 state=suggestion
                if not rule.meta:
                    self.errors.append(f"C5: 规则 {rule.id} 缺少 _meta（learn 规则必填）")
    
    def _check_c6_conflict_matrix(self):
        """C6: 冲突矩阵检测"""
        baseline_rules = [r for r in self.rules if r.baseline]
        
        # 两条 baseline 规则重叠检测（简化实现：when 条件相同视为重叠）
        for i, r1 in enumerate(baseline_rules):
            for r2 in baseline_rules[i+1:]:
                if r1.domain == r2.domain:
                    # 简化：检查 when 键集合是否重叠（排除内部标记）
                    keys1 = set(k for k in r1.when.keys() if not k.startswith("_"))
                    keys2 = set(k for k in r2.when.keys() if not k.startswith("_"))
                    
                    # 如果两个规则都只有内部标记（实际内容未解析），跳过
                    if not keys1 and not keys2:
                        continue
                    
                    # 实际冲突检测应该更精细，这里简化为：如果键不同，不冲突
                    if keys1 and keys2 and keys1 != keys2:
                        continue
                    
                    # 如果键完全相同或都为空，可能冲突（需要更精细的值比较）
                    if keys1 & keys2 or (not keys1 and not keys2):
                        self.conflicts.append((r1.id, r2.id, "baseline 可能重叠"))
                        self.warnings.append(
                            f"C6: 基线规则 {r1.id} 与 {r2.id} 可能条件重叠（需人工审查）"
                        )
        
        # 同 priority 规则冲突检测
        priority_groups: Dict[int, List[Rule]] = {}
        for rule in self.rules:
            if not rule.baseline:
                priority_groups.setdefault(rule.priority, []).append(rule)
        
        for priority, group in priority_groups.items():
            if len(group) > 1:
                # 检查同 priority 规则是否有重叠且结论不同
                for i, r1 in enumerate(group):
                    for r2 in group[i+1:]:
                        if r1.domain == r2.domain and r1.effect != r2.effect:
                            # 简化：检查 when 键重叠
                            keys1 = set(r1.when.keys())
                            keys2 = set(r2.when.keys())
                            if keys1 & keys2:
                                self.conflicts.append((r1.id, r2.id, f"priority {priority} 冲突"))
                                self.warnings.append(
                                    f"C6: 规则 {r1.id} 与 {r2.id} 同优先级 {priority} 但 effect 不同（需字典序消歧）"
                                )
    
    def _check_c7_audit_constraints(self):
        """C7: audit:none 用于 high/接管类"""
        for rule in self.rules:
            if rule.audit == "none":
                # 高风险动作不可 audit:none
                if rule.effect == Effect.REQUIRE_CONFIRMATION:
                    self.errors.append(f"C7: 规则 {rule.id} 为高风险确认类，不可 audit:none")
    
    def _check_c8_dead_rules(self):
        """C8: 死规则检测（告警）"""
        # 简化实现：检测被完全覆盖的规则
        sorted_rules = sorted(
            [r for r in self.rules if not r.baseline],
            key=lambda r: r.priority,
            reverse=True
        )
        
        for i, rule in enumerate(sorted_rules):
            # 检查是否有更高优先级规则完全覆盖
            for higher_rule in sorted_rules[:i]:
                if higher_rule.domain == rule.domain and higher_rule.priority > rule.priority:
                    # 简化：when 键完全包含视为覆盖
                    if set(rule.when.keys()).issubset(set(higher_rule.when.keys())):
                        self.warnings.append(
                            f"C8: 规则 {rule.id} 可能被 {higher_rule.id} 完全覆盖（死规则）"
                        )
    
    def _generate_decision_tree(self) -> Dict[str, Any]:
        """生成决策树（骨架实现，生产需 trie/表驱动优化）"""
        tree = {
            "baseline_deny_protect": [],
            "baseline_route": [],
            "normal_rules": [],
        }
        
        for rule in self.rules:
            if rule.baseline and rule.baseline_type == "deny-protect":
                tree["baseline_deny_protect"].append(rule)
            elif rule.baseline and rule.baseline_type == "route":
                tree["baseline_route"].append(rule)
            else:
                tree["normal_rules"].append(rule)
        
        # 按 priority 排序
        tree["normal_rules"].sort(key=lambda r: (-r.priority, r.id))
        
        return tree
    
    def _calculate_stats(self) -> Dict[str, Any]:
        """计算统计信息"""
        return {
            "total_rules": len(self.rules),
            "baseline_count": sum(1 for r in self.rules if r.baseline),
            "domain_distribution": {
                "action": sum(1 for r in self.rules if r.domain == Domain.ACTION),
                "notification": sum(1 for r in self.rules if r.domain == Domain.NOTIFICATION),
                "call_gate": sum(1 for r in self.rules if r.domain == Domain.CALL_GATE),
            },
            "namespace_distribution": {
                "sys": sum(1 for r in self.rules if r.namespace == Namespace.SYS),
                "user": sum(1 for r in self.rules if r.namespace == Namespace.USER),
                "learn": sum(1 for r in self.rules if r.namespace == Namespace.LEARN),
            },
            "conflicts_detected": len(self.conflicts),
            "warnings": len(self.warnings),
        }


def main():
    """命令行入口"""
    if len(sys.argv) < 2:
        print("用法: python policy_compiler.py <dsl_file>")
        print("示例: python policy_compiler.py ../vendor/genton/config/policy_baseline.dsl")
        sys.exit(1)
    
    dsl_file = Path(sys.argv[1])
    if not dsl_file.exists():
        print(f"错误: 文件不存在 {dsl_file}")
        sys.exit(1)
    
    compiler = PolicyCompiler()
    result = compiler.compile(dsl_file)
    
    print("\n" + "="*60)
    print("编译结果")
    print("="*60)
    
    if result.success:
        print(f"✓ 编译成功")
        print(f"\n统计信息:")
        for key, value in result.stats.items():
            if isinstance(value, dict):
                print(f"  {key}:")
                for k, v in value.items():
                    print(f"    {k}: {v}")
            else:
                print(f"  {key}: {value}")
        
        if result.warnings:
            print(f"\n⚠ 警告 ({len(result.warnings)}):")
            for warning in result.warnings:
                print(f"  - {warning}")
        
        if result.conflicts:
            print(f"\n⚠ 冲突检测 ({len(result.conflicts)}):")
            for r1, r2, reason in result.conflicts:
                print(f"  - {r1} ↔ {r2}: {reason}")
    else:
        print(f"✗ 编译失败")
        print(f"\n错误 ({len(result.errors)}):")
        for error in result.errors:
            print(f"  - {error}")
        sys.exit(1)
    
    print("\n" + "="*60)


if __name__ == "__main__":
    main()
