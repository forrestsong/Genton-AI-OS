#!/usr/bin/env python3
"""
策略 DSL 编译器 (Policy DSL Compiler)
实现 D17 规范的 C1-C8 静态检查
"""

import yaml
import re
import sys
from typing import Dict, List, Any, Optional, Set, Tuple
from dataclasses import dataclass, field
from enum import Enum


class Domain(Enum):
    """域类型"""
    ACTION = "action"
    NOTIFICATION = "notification"
    CALL_GATE = "call_gate"


@dataclass
class PolicyRule:
    """策略规则对象"""
    id: str
    domain: Domain
    effect: str
    when: Dict[str, Any]
    then: Dict[str, Any]
    priority: int
    baseline: bool = False
    subject: Optional[Dict[str, Any]] = None
    meta: Optional[Dict[str, Any]] = None
    
    # 原始数据
    raw: Dict[str, Any] = field(default_factory=dict)


@dataclass
class CompilationError:
    """编译错误"""
    check: str  # C1-C8
    rule_id: str
    message: str
    severity: str = "error"  # error/warning


@dataclass
class Conflict:
    """冲突信息"""
    rule1_id: str
    rule2_id: str
    conflict_type: str
    message: str


@dataclass
class CompilationResult:
    """编译结果"""
    success: bool
    rules: List[PolicyRule]
    errors: List[CompilationError]
    conflicts: List[Conflict]
    dead_rules: List[str]


class PolicyDSLCompiler:
    """策略 DSL 编译器"""
    
    # D17 §2.3 when 字段白名单
    WHEN_WHITELIST = {
        Domain.ACTION: {
            "capability", "action", "argSources", 
            "context.network", "context.appForeground", "context.timeWindow"
        },
        Domain.NOTIFICATION: {
            "event", "senderDomain", "senderFingerprint", "senderTrust",
            "category", "classifierConfidence", "userPinnedP0", "context.dndActive"
        },
        Domain.CALL_GATE: {
            "call", "caller", "callerClass", "callerIs", "numberPrefix",
            "spamScore", "callRepeatedInWindowMs", "callerHasRecentOutgoingWithinDays",
            "dailyAiHandleUsed", "gateMode", "context.dndActive"
        }
    }
    
    # D17 §2.4 effect 动词白名单
    EFFECT_WHITELIST = {
        Domain.ACTION: {"allow", "deny", "require_confirmation", "require_mitigation"},
        Domain.NOTIFICATION: {
            "deny", "route_p0_native", "route_p1_digest", "route_p2_agent_act"
        },
        Domain.CALL_GATE: {
            "deny", "route_ring_native", "route_agent_handle", "route_record_only"
        }
    }
    
    # D17 §2.5 then 字段白名单
    THEN_WHITELIST_GLOBAL = {"audit", "reason"}
    THEN_WHITELIST = {
        Domain.NOTIFICATION: {
            "route", "summaryModel", "holdUntilDndEnd", "aggregateWindowMs",
            "suppressBadge", "dailyHandleCap", "agentAct", "senderAuthz"
        },
        Domain.CALL_GATE: {
            "route", "handleMode", "audioBoundary", "suggestVoicemail", "digestSuggestion"
        }
    }
    
    # effect 与 route 的一致性映射
    EFFECT_ROUTE_MAP = {
        "route_p0_native": "P0_NATIVE",
        "route_p1_digest": "P1_AGENT_SUMMARY",
        "route_p2_agent_act": "P2_AGENT_HANDLE",
        "route_ring_native": "RING_NATIVE",
        "route_agent_handle": "AGENT_HANDLE",
        "route_record_only": "RECORD_ONLY"
    }
    
    # 高风险 effect（需要 audit）
    HIGH_RISK_EFFECTS = {"route_p2_agent_act", "route_agent_handle"}
    
    def __init__(self):
        self.rules: List[PolicyRule] = []
        self.errors: List[CompilationError] = []
        self.conflicts: List[Conflict] = []
        self.dead_rules: List[str] = []
    
    def compile(self, policy_yaml: str) -> CompilationResult:
        """编译策略 DSL"""
        self.rules = []
        self.errors = []
        self.conflicts = []
        self.dead_rules = []
        
        try:
            # 解析 YAML
            policies = yaml.safe_load(policy_yaml)
            if not isinstance(policies, list):
                self.errors.append(CompilationError(
                    "C1", "N/A", "策略文件必须是规则列表"
                ))
                return self._build_result()
            
            # 解析每条规则
            for policy_dict in policies:
                rule = self._parse_rule(policy_dict)
                if rule:
                    self.rules.append(rule)
            
            # 执行静态检查
            self._check_c1_id_validation()
            self._check_c2_required_fields()
            self._check_c3_when_whitelist()
            self._check_c4_effect_validation()
            self._check_c5_namespace_validation()
            self._check_c7_audit_validation()
            
            # 冲突检测（C6）
            self._check_c6_conflict_matrix()
            
            # 死规则检测（C8）
            self._check_c8_dead_rules()
            
        except yaml.YAMLError as e:
            self.errors.append(CompilationError(
                "C1", "N/A", f"YAML 解析失败: {e}"
            ))
        except Exception as e:
            self.errors.append(CompilationError(
                "C1", "N/A", f"编译失败: {e}"
            ))
        
        return self._build_result()
    
    def _parse_rule(self, policy_dict: Dict[str, Any]) -> Optional[PolicyRule]:
        """解析单条规则"""
        try:
            rule_id = policy_dict.get("id", "")
            
            # 解析 domain
            domain_str = policy_dict.get("domain", "action")
            try:
                domain = Domain(domain_str)
            except ValueError:
                self.errors.append(CompilationError(
                    "C2", rule_id, f"未知的域: {domain_str}"
                ))
                return None
            
            rule = PolicyRule(
                id=rule_id,
                domain=domain,
                effect=policy_dict.get("effect", ""),
                when=policy_dict.get("when", {}),
                then=policy_dict.get("then", {}),
                priority=policy_dict.get("priority", 0),
                baseline=policy_dict.get("baseline", False),
                subject=policy_dict.get("subject"),
                meta=policy_dict.get("_meta"),
                raw=policy_dict
            )
            
            return rule
            
        except Exception as e:
            self.errors.append(CompilationError(
                "C1", policy_dict.get("id", "unknown"), f"规则解析失败: {e}"
            ))
            return None
    
    def _check_c1_id_validation(self):
        """C1: ID 命名空间/格式/唯一性"""
        seen_ids: Set[str] = set()
        
        for rule in self.rules:
            # 格式检查
            if not re.match(r'^(sys|user|learn)\.[a-z0-9_.-]{1,64}$', rule.id):
                self.errors.append(CompilationError(
                    "C1", rule.id, 
                    f"ID 格式不符合规范: {rule.id}"
                ))
            
            # 命名空间检查
            namespace = rule.id.split('.')[0]
            if namespace not in ["sys", "user", "learn"]:
                self.errors.append(CompilationError(
                    "C1", rule.id,
                    f"ID 必须以 sys./user./learn. 开头"
                ))
            
            # learn 规则需要域段
            if namespace == "learn":
                parts = rule.id.split('.')
                if len(parts) < 3 or parts[1] not in ["notify", "callgate"]:
                    self.errors.append(CompilationError(
                        "C1", rule.id,
                        "learn. 规则 ID 必须包含域段 (notify/callgate)"
                    ))
            
            # 唯一性检查
            if rule.id in seen_ids:
                self.errors.append(CompilationError(
                    "C1", rule.id,
                    f"重复的规则 ID: {rule.id}"
                ))
            seen_ids.add(rule.id)
    
    def _check_c2_required_fields(self):
        """C2: 字段必填性、未知字段"""
        required_fields = {"id", "effect", "when", "then", "priority"}
        allowed_fields = {
            "id", "domain", "baseline", "subject", "effect", 
            "when", "then", "priority", "_meta"
        }
        
        for rule in self.rules:
            # 必填字段检查
            for field in required_fields:
                if field not in rule.raw or not rule.raw[field]:
                    self.errors.append(CompilationError(
                        "C2", rule.id,
                        f"缺少必填字段: {field}"
                    ))
            
            # 未知字段检查
            for field in rule.raw.keys():
                if field not in allowed_fields:
                    self.errors.append(CompilationError(
                        "C2", rule.id,
                        f"未知字段: {field}"
                    ))
            
            # 授权轴必须有 subject
            if rule.domain == Domain.ACTION and not rule.subject:
                self.errors.append(CompilationError(
                    "C2", rule.id,
                    "授权轴 (domain:action) 必须有 subject"
                ))
            
            # 路由轴禁止 subject
            if rule.domain in [Domain.NOTIFICATION, Domain.CALL_GATE] and rule.subject:
                self.errors.append(CompilationError(
                    "C2", rule.id,
                    f"路由轴 (domain:{rule.domain.value}) 禁止使用 subject"
                ))
    
    def _check_c3_when_whitelist(self):
        """C3: when 键白名单、值枚举、比较串"""
        for rule in self.rules:
            whitelist = self.WHEN_WHITELIST.get(rule.domain, set())
            
            for key in rule.when.keys():
                # 白名单检查（支持点路径前缀匹配）
                if not self._is_key_whitelisted(key, whitelist):
                    self.errors.append(CompilationError(
                        "C3", rule.id,
                        f"when 键 '{key}' 不在域 {rule.domain.value} 的白名单中"
                    ))
                
                # 比较串格式检查
                value = rule.when[key]
                if isinstance(value, str) and self._is_comparison_string(value):
                    if not self._validate_comparison_string(value):
                        self.errors.append(CompilationError(
                            "C3", rule.id,
                            f"when 键 '{key}' 的比较串格式错误: {value}"
                        ))
    
    def _is_key_whitelisted(self, key: str, whitelist: Set[str]) -> bool:
        """检查键是否在白名单（支持点路径前缀）"""
        if key in whitelist:
            return True
        
        # 检查点路径前缀（如 context.* 匹配 context.dndActive）
        for allowed in whitelist:
            if '.' in allowed and key.startswith(allowed.split('.')[0] + '.'):
                return True
        
        return False
    
    def _is_comparison_string(self, value: str) -> bool:
        """判断是否是比较串"""
        return any(value.startswith(op) for op in [">=", "<=", ">", "<", "=="])
    
    def _validate_comparison_string(self, value: str) -> bool:
        """验证比较串格式"""
        pattern = r'^(>=|<=|>|<|==)\s*-?\d+(\.\d+)?$'
        return bool(re.match(pattern, value))
    
    def _check_c4_effect_validation(self):
        """C4: effect 动词、route 同族一致性"""
        for rule in self.rules:
            # effect 白名单检查
            whitelist = self.EFFECT_WHITELIST.get(rule.domain, set())
            if rule.effect not in whitelist:
                self.errors.append(CompilationError(
                    "C4", rule.id,
                    f"effect '{rule.effect}' 不适用于域 {rule.domain.value}"
                ))
            
            # route 与 effect 一致性检查
            if rule.effect.startswith("route_"):
                expected_route = self.EFFECT_ROUTE_MAP.get(rule.effect)
                actual_route = rule.then.get("route")
                
                if expected_route and actual_route != expected_route:
                    self.errors.append(CompilationError(
                        "C4", rule.id,
                        f"effect '{rule.effect}' 与 then.route '{actual_route}' 不一致，应为 '{expected_route}'"
                    ))
    
    def _check_c5_namespace_validation(self):
        """C5: 命名空间与 provenance 验证"""
        for rule in self.rules:
            namespace = rule.id.split('.')[0]
            
            # sys. 规则检查（需要平台签名，此处仅语法检查）
            if namespace == "sys" and not rule.baseline:
                # sys. 非 baseline 规则（预留检查）
                pass
            
            # learn. 规则必须有 _meta
            if namespace == "learn":
                if not rule.meta:
                    self.errors.append(CompilationError(
                        "C5", rule.id,
                        "learn. 规则必须有 _meta 字段"
                    ))
                elif rule.meta.get("state") != "suggestion":
                    self.errors.append(CompilationError(
                        "C5", rule.id,
                        "learn. 规则的 _meta.state 必须为 'suggestion'"
                    ))
                elif rule.meta.get("needsUserApproval") != True:
                    self.errors.append(CompilationError(
                        "C5", rule.id,
                        "learn. 规则的 _meta.needsUserApproval 必须为 true"
                    ))
            
            # 非 learn 规则不应有 _meta
            if namespace != "learn" and rule.meta:
                self.errors.append(CompilationError(
                    "C5", rule.id,
                    f"{namespace}. 规则不应有 _meta 字段"
                ))
    
    def _check_c6_conflict_matrix(self):
        """C6: 冲突矩阵检查"""
        # 按域分组
        by_domain: Dict[Domain, List[PolicyRule]] = {}
        for rule in self.rules:
            if rule.domain not in by_domain:
                by_domain[rule.domain] = []
            by_domain[rule.domain].append(rule)
        
        for domain, rules in by_domain.items():
            # 检查 baseline 规则之间的重叠
            baseline_rules = [r for r in rules if r.baseline]
            for i, r1 in enumerate(baseline_rules):
                for r2 in baseline_rules[i+1:]:
                    overlap = self._rules_overlap(r1, r2)
                    if overlap:
                        self.conflicts.append(Conflict(
                            r1.id, r2.id, "baseline_overlap",
                            f"两条 baseline 规则的 when 条件重叠"
                        ))
            
            # 检查同 priority 规则的冲突
            by_priority: Dict[int, List[PolicyRule]] = {}
            for rule in rules:
                if rule.priority not in by_priority:
                    by_priority[rule.priority] = []
                by_priority[rule.priority].append(rule)
            
            for priority, same_pri_rules in by_priority.items():
                if len(same_pri_rules) > 1:
                    for i, r1 in enumerate(same_pri_rules):
                        for r2 in same_pri_rules[i+1:]:
                            if self._rules_overlap(r1, r2) and r1.effect != r2.effect:
                                self.conflicts.append(Conflict(
                                    r1.id, r2.id, "priority_conflict",
                                    f"相同 priority={priority} 的规则重叠但 effect 不同"
                                ))
    
    def _rules_overlap(self, r1: PolicyRule, r2: PolicyRule) -> bool:
        """检查两条规则的 when 条件是否重叠（简化版）"""
        # 简化实现：检查 when 键是否有交集
        keys1 = set(r1.when.keys())
        keys2 = set(r2.when.keys())
        
        # 如果键集完全不同，互斥
        if not keys1.intersection(keys2):
            return False
        
        # 如果一个规则有另一个没有的键，则互斥（条件不同）
        if keys1 != keys2:
            return False
        
        # 如果有共同键，检查值是否有交集
        for key in keys1.intersection(keys2):
            v1 = r1.when[key]
            v2 = r2.when[key]
            
            # 简化：如果值不同则不重叠
            if v1 != v2:
                # 对于列表，检查是否有交集
                if isinstance(v1, list) and isinstance(v2, list):
                    if not set(v1).intersection(set(v2)):
                        return False
                else:
                    return False
        
        return True
    
    def _check_c7_audit_validation(self):
        """C7: audit:none 用于 high/接管类"""
        for rule in self.rules:
            audit = rule.then.get("audit")
            
            if audit == "none" and rule.effect in self.HIGH_RISK_EFFECTS:
                self.errors.append(CompilationError(
                    "C7", rule.id,
                    f"高风险 effect '{rule.effect}' 不允许 audit:none"
                ))
    
    def _check_c8_dead_rules(self):
        """C8: 死规则检测"""
        # 按域分组并按优先级排序
        by_domain: Dict[Domain, List[PolicyRule]] = {}
        for rule in self.rules:
            if rule.domain not in by_domain:
                by_domain[rule.domain] = []
            by_domain[rule.domain].append(rule)
        
        for domain, rules in by_domain.items():
            # 按优先级降序排序
            sorted_rules = sorted(rules, key=lambda r: (-r.priority, r.id))
            
            # 检查每条规则是否被更高优先级规则覆盖
            for i, rule in enumerate(sorted_rules):
                for higher_rule in sorted_rules[:i]:
                    if self._rule_covers(higher_rule, rule):
                        self.dead_rules.append(rule.id)
                        self.errors.append(CompilationError(
                            "C8", rule.id,
                            f"死规则：被更高优先级规则 {higher_rule.id} 完全覆盖",
                            severity="warning"
                        ))
                        break
    
    def _rule_covers(self, higher: PolicyRule, lower: PolicyRule) -> bool:
        """检查高优先级规则是否完全覆盖低优先级规则"""
        # 简化实现：如果高优先级规则的 when 条件是低优先级的超集，则覆盖
        for key, value in lower.when.items():
            if key not in higher.when:
                return False
            
            # 检查值是否被覆盖
            higher_val = higher.when[key]
            if isinstance(value, list) and isinstance(higher_val, list):
                if not set(value).issubset(set(higher_val)):
                    return False
            elif value != higher_val:
                return False
        
        return True
    
    def _build_result(self) -> CompilationResult:
        """构建编译结果"""
        return CompilationResult(
            success=len([e for e in self.errors if e.severity == "error"]) == 0,
            rules=self.rules,
            errors=self.errors,
            conflicts=self.conflicts,
            dead_rules=self.dead_rules
        )


def main():
    """命令行入口"""
    if len(sys.argv) < 2:
        print("用法: policy_dsl_compiler.py <policy.yaml>")
        sys.exit(1)
    
    policy_file = sys.argv[1]
    
    try:
        with open(policy_file, 'r', encoding='utf-8') as f:
            policy_yaml = f.read()
        
        compiler = PolicyDSLCompiler()
        result = compiler.compile(policy_yaml)
        
        # 输出结果
        print(f"\n{'='*60}")
        print(f"策略 DSL 编译报告")
        print(f"{'='*60}\n")
        
        print(f"编译状态: {'✓ 成功' if result.success else '✗ 失败'}")
        print(f"规则总数: {len(result.rules)}")
        print(f"错误数量: {len([e for e in result.errors if e.severity == 'error'])}")
        print(f"警告数量: {len([e for e in result.errors if e.severity == 'warning'])}")
        print(f"冲突数量: {len(result.conflicts)}")
        print(f"死规则数量: {len(result.dead_rules)}")
        
        # 输出错误
        if result.errors:
            print(f"\n{'='*60}")
            print("错误和警告:")
            print(f"{'='*60}\n")
            for error in result.errors:
                symbol = "⚠" if error.severity == "warning" else "✗"
                print(f"{symbol} [{error.check}] {error.rule_id}")
                print(f"  {error.message}\n")
        
        # 输出冲突
        if result.conflicts:
            print(f"\n{'='*60}")
            print("冲突矩阵:")
            print(f"{'='*60}\n")
            for conflict in result.conflicts:
                print(f"✗ {conflict.conflict_type}")
                print(f"  规则1: {conflict.rule1_id}")
                print(f"  规则2: {conflict.rule2_id}")
                print(f"  {conflict.message}\n")
        
        # 输出死规则
        if result.dead_rules:
            print(f"\n{'='*60}")
            print("死规则列表:")
            print(f"{'='*60}\n")
            for rule_id in result.dead_rules:
                print(f"⚠ {rule_id}")
        
        sys.exit(0 if result.success else 1)
        
    except FileNotFoundError:
        print(f"错误: 文件不存在: {policy_file}")
        sys.exit(1)
    except Exception as e:
        print(f"错误: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
