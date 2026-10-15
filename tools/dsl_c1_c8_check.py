#!/usr/bin/env python3
"""
AI Native OS - 策略 DSL C1-C8 静态编译检查（纯标准库）

任务编号：H1.1（质量工程部 · CI 基建）
验收项  ：DSL C1-C8 编译检查（AIDL/Soong 编译占位 + schema/契约静态校验 +
          DSL C1-C8 编译检查 + sepolicy/权限审计脚本 四合一流水线中的 DSL 检查步骤）

检查项（对齐 D17 §2 与 policy_dsl_compiler.py 的 C1-C8）：
  C1  ID 命名空间/格式/唯一性（^sys|user|learn 点 a-z0-9_.- 1-64 长度）
  C2  必填字段（id/domain/effect/when/priority）与未知字段
  C3  when 键白名单、值枚举、比较串格式
  C4  effect 动词白名单、route 同族一致性
  C5  命名空间与 provenance（learn. 需 _meta.state=suggestion 等）
  C6  冲突矩阵（baseline 重叠 / 同 priority 不同 effect）
  C7  audit 校验（高风险 effect 不允许 audit:none）
  C8  死规则检测（被更高优先级规则完全覆盖）

依赖：tools/dsl_parser.py（同目录，纯标准库）
运行：python3 tools/dsl_c1_c8_check.py [policy.dsl] [--report out.md]
退出码：0=通过，1=存在错误，2=文件/参数错误
"""

import os
import re
import sys
from typing import Any, Dict, List, Optional, Set, Tuple

# 兼容 Windows 控制台 UTF-8 输出
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dsl_parser import DslParseError, parse_dsl_file  # noqa: E402


# ============================================================
# C1-C8 检查器
# ============================================================

# D17 §2.3 when 字段白名单（按域；支持 context.* / call.* 点路径前缀）
WHEN_WHITELIST: Dict[str, Set[str]] = {
    "action": {
        "capability", "action", "argSources", "event", "or",
        "context.network", "context.appForeground", "context.timeWindow",
        "context.intentMatch", "context.tokenSignatureValid",
        "context.tokenExpired", "context.tokenScopeMatch",
        "context.actionRiskLevel",
    },
    "notification": {
        "event", "senderDomain", "senderFingerprint", "senderTrust",
        "category", "classifierConfidence", "userPinnedP0", "context.dndActive",
        "context.userPinnedP0", "or",
    },
    "call_gate": {
        "call", "caller", "callerClass", "callerIs", "numberPrefix",
        "spamScore", "callRepeatedInWindowMs",
        "callerHasRecentOutgoingWithinDays", "dailyAiHandleUsed", "gateMode",
        "context.dndActive", "context.userMarkedVIP", "or",
    },
}

# D17 §2.4 effect 动词白名单（按域）
EFFECT_WHITELIST: Dict[str, Set[str]] = {
    "action": {"allow", "deny", "require_confirmation", "require_mitigation"},
    "notification": {"deny", "route_p0_native", "route_p1_digest", "route_p2_agent_act"},
    "call_gate": {"deny", "route_ring_native", "route_agent_handle", "route_record_only"},
}

# effect 与 route 一致性映射（对齐 policy_dsl_compiler.EFFECT_ROUTE_MAP）
EFFECT_ROUTE_MAP: Dict[str, str] = {
    "route_p0_native": "P0_NATIVE",
    "route_p1_digest": "P1_AGENT_SUMMARY",
    "route_p2_agent_act": "P2_AGENT_HANDLE",
    "route_ring_native": "RING_NATIVE",
    "route_agent_handle": "AGENT_HANDLE",
    "route_record_only": "RECORD_ONLY",
}

HIGH_RISK_EFFECTS: Set[str] = {"route_p2_agent_act", "route_agent_handle"}

AUDIT_VALUES: Set[str] = {"mandatory", "periodic", "none", "full", "summary"}

# DSL 特殊值函数（when 值可出现的函数调用形式）
WHEN_FUNC_PATTERN = re.compile(
    r'^(starts_with|matches|contains|missing|one_of|in)\s*\('
)

REQUIRED_FIELDS = ("id", "domain", "effect", "when", "priority")
ALLOWED_FIELDS = ("id", "domain", "baseline", "subject", "effect",
                  "when", "then", "priority", "_meta", "audit")


class CheckError:
    """单条检查结果"""

    def __init__(self, check: str, rule_id: str, message: str,
                 severity: str = "error"):
        self.check = check
        self.rule_id = rule_id
        self.message = message
        self.severity = severity  # error / warning

    def __repr__(self) -> str:  # pragma: no cover
        return f"[{self.check}] {self.rule_id}: {self.message}"


class DslC1C8Checker:
    def __init__(self, rules: List[Dict[str, Any]]):
        self.rules = rules
        self.errors: List[CheckError] = []

    # --------------------------------------------------------
    # 工具
    # --------------------------------------------------------

    @staticmethod
    def _is_key_whitelisted(key: str, whitelist: Set[str]) -> bool:
        """检查键是否在白名单（支持点路径前缀，如 context.* / call.*）"""
        if key in whitelist:
            return True
        for allowed in whitelist:
            # 白名单条目自身即前缀（如 call → call.number）
            if key.startswith(allowed + "."):
                return True
            if "." in allowed and key.startswith(allowed.split(".")[0] + "."):
                return True
        return False

    @staticmethod
    def _is_comparison_string(value: str) -> bool:
        return any(value.startswith(op) for op in (">=", "<=", ">", "<", "=="))

    @staticmethod
    def _validate_comparison_string(value: str) -> bool:
        return bool(re.match(r'^(>=|<=|>|<|==)\s*-?\d+(\.\d+)?$', value))

    @staticmethod
    def _collect_when_keys(when: Any, out: List[str]) -> None:
        """递归收集 when 中的键（处理 or 列表）"""
        if isinstance(when, dict):
            for k, v in when.items():
                out.append(k)
                if isinstance(v, dict):
                    DslC1C8Checker._collect_when_keys(v, out)
                elif isinstance(v, list):
                    for item in v:
                        if isinstance(item, dict):
                            DslC1C8Checker._collect_when_keys(item, out)
        elif isinstance(when, list):
            for item in when:
                if isinstance(item, dict):
                    DslC1C8Checker._collect_when_keys(item, out)

    # --------------------------------------------------------
    # C1：ID 命名空间/格式/唯一性
    # --------------------------------------------------------

    def check_c1(self) -> None:
        seen: Set[str] = set()
        for rule in self.rules:
            rid = rule.get("id", "")
            if not re.match(r'^(sys|user|learn)\.[a-z0-9_.-]{1,64}$', rid):
                self.errors.append(CheckError(
                    "C1", rid, f"ID 格式不符合规范: {rid}"))
            namespace = rid.split(".")[0]
            if namespace not in ("sys", "user", "learn"):
                self.errors.append(CheckError(
                    "C1", rid, "ID 必须以 sys./user./learn. 开头"))
            if rid in seen:
                self.errors.append(CheckError("C1", rid, f"重复的规则 ID: {rid}"))
            seen.add(rid)

    # --------------------------------------------------------
    # C2：必填字段、未知字段
    # --------------------------------------------------------

    def check_c2(self) -> None:
        for rule in self.rules:
            rid = rule.get("id", "")
            for field in REQUIRED_FIELDS:
                if field not in rule or rule[field] in (None, "", {}):
                    self.errors.append(CheckError(
                        "C2", rid, f"缺少必填字段: {field}"))
            for field in rule.keys():
                if field not in ALLOWED_FIELDS:
                    self.errors.append(CheckError(
                        "C2", rid, f"未知字段: {field}"))
            # 授权轴必须有 subject
            if rule.get("domain") == "action" and not rule.get("subject"):
                self.errors.append(CheckError(
                    "C2", rid, "授权轴 (domain:action) 必须有 subject"))
            # 路由轴禁止 subject
            if rule.get("domain") in ("notification", "call_gate") and rule.get("subject"):
                self.errors.append(CheckError(
                    "C2", rid, f"路由轴 (domain:{rule.get('domain')}) 禁止使用 subject"))

    # --------------------------------------------------------
    # C3：when 键白名单、值枚举、比较串
    # --------------------------------------------------------

    def check_c3(self) -> None:
        for rule in self.rules:
            rid = rule.get("id", "")
            domain = rule.get("domain", "action")
            whitelist = WHEN_WHITELIST.get(domain, set())
            keys: List[str] = []
            self._collect_when_keys(rule.get("when", {}), keys)
            for key in keys:
                if not self._is_key_whitelisted(key, whitelist):
                    self.errors.append(CheckError(
                        "C3", rid, f"when 键 '{key}' 不在域 {domain} 的白名单中"))
            # 顶层 when 值校验（比较串/函数形式）
            self._check_when_values(rule.get("when", {}), rid)

    def _check_when_values(self, when: Any, rid: str) -> None:
        if isinstance(when, dict):
            for key, value in when.items():
                if isinstance(value, str):
                    if self._is_comparison_string(value) and \
                            not self._validate_comparison_string(value):
                        self.errors.append(CheckError(
                            "C3", rid, f"when 键 '{key}' 的比较串格式错误: {value}"))
                elif isinstance(value, (dict, list)):
                    self._check_when_values(value, rid)
        elif isinstance(when, list):
            for item in when:
                self._check_when_values(item, rid)

    # --------------------------------------------------------
    # C4：effect 动词、route 同族一致性
    # --------------------------------------------------------

    def check_c4(self) -> None:
        for rule in self.rules:
            rid = rule.get("id", "")
            domain = rule.get("domain", "action")
            effect = rule.get("effect", "")
            whitelist = EFFECT_WHITELIST.get(domain, set())
            if effect not in whitelist:
                self.errors.append(CheckError(
                    "C4", rid, f"effect '{effect}' 不适用于域 {domain}"))
            if effect.startswith("route_"):
                expected = EFFECT_ROUTE_MAP.get(effect)
                actual = None
                then = rule.get("then") or {}
                if isinstance(then, dict):
                    actual = then.get("route")
                if expected and actual is not None and actual != expected:
                    self.errors.append(CheckError(
                        "C4", rid,
                        f"effect '{effect}' 与 then.route '{actual}' 不一致，"
                        f"应为 '{expected}'"))

    # --------------------------------------------------------
    # C5：命名空间与 provenance
    # --------------------------------------------------------

    def check_c5(self) -> None:
        for rule in self.rules:
            rid = rule.get("id", "")
            namespace = rid.split(".")[0]
            meta = rule.get("_meta") or {}
            if namespace == "learn":
                if not meta:
                    self.errors.append(CheckError(
                        "C5", rid, "learn. 规则必须有 _meta 字段"))
                elif meta.get("state") != "suggestion":
                    self.errors.append(CheckError(
                        "C5", rid, "learn. 规则的 _meta.state 必须为 'suggestion'"))
                elif meta.get("needsUserApproval") is not True:
                    self.errors.append(CheckError(
                        "C5", rid, "learn. 规则的 _meta.needsUserApproval 必须为 true"))
            else:
                if meta and "immutable" in meta:
                    # 非 learn 规则允许 _meta（基线带 immutable），不做硬性拒绝
                    pass

    # --------------------------------------------------------
    # C6：冲突矩阵
    # --------------------------------------------------------

    def check_c6(self) -> None:
        conflicts: List[str] = []
        by_domain: Dict[str, List[Dict[str, Any]]] = {}
        for rule in self.rules:
            d = rule.get("domain", "action")
            by_domain.setdefault(d, []).append(rule)

        for domain, rules in by_domain.items():
            # 同 priority 规则：重叠且 effect 不同 = 冲突（裁决歧义）
            # 不同 priority：由优先级确定裁决，不构成矩阵冲突
            by_prio: Dict[int, List[Dict[str, Any]]] = {}
            for rule in rules:
                by_prio.setdefault(rule.get("priority", 0), []).append(rule)
            for prio, same in by_prio.items():
                if len(same) > 1:
                    for i, r1 in enumerate(same):
                        for r2 in same[i + 1:]:
                            if r1.get("effect") == r2.get("effect"):
                                continue  # 同 effect 冗余不冲突（确定性裁决）
                            if self._when_satisfiable(
                                    r1.get("when", {}), r2.get("when", {})):
                                conflicts.append(
                                    f"{r1.get('id')} <-> {r2.get('id')} "
                                    f"(priority_conflict prio={prio}: "
                                    f"{r1.get('effect')} vs {r2.get('effect')})")
        if conflicts:
            self.errors.append(CheckError(
                "C6", "N/A", "冲突矩阵检测到冲突: " + "; ".join(conflicts)))

    # --------------------------------------------------------
    # when 可满足性分析（C6/C8 共用）
    # --------------------------------------------------------

    @classmethod
    def _when_signatures(cls, when: Any) -> List[Dict[str, Any]]:
        """
        将 when 展开为若干条件集合签名（primary 条件 × or 分支）。

        例：{capability: X, or: [{action: A}, {action: B}]}
          → [{capability: X, action: A}, {capability: X, action: B}]
        无 or 时 → [when]
        """
        if not isinstance(when, dict):
            return [{}]
        primary = {k: v for k, v in when.items() if k != "or"}
        or_branches = when.get("or")
        if not isinstance(or_branches, list) or not or_branches:
            return [dict(primary)]
        signatures: List[Dict[str, Any]] = []
        for branch in or_branches:
            if isinstance(branch, dict):
                merged = dict(primary)
                merged.update(branch)
                signatures.append(merged)
        return signatures or [dict(primary)]

    @classmethod
    def _when_satisfiable(cls, w1: Any, w2: Any) -> bool:
        """两条 when 是否存在同时匹配的请求（任何签名组合可满足即冲突可能）"""
        sigs1 = cls._when_signatures(w1)
        sigs2 = cls._when_signatures(w2)
        for s1 in sigs1:
            for s2 in sigs2:
                if cls._signatures_intersect(s1, s2):
                    return True
        return False

    @classmethod
    def _signatures_intersect(cls, s1: Dict[str, Any],
                              s2: Dict[str, Any]) -> bool:
        """两个条件签名是否存在交集（公共键值相容）"""
        shared = set(s1.keys()) & set(s2.keys())
        for key in shared:
            v1, v2 = s1[key], s2[key]
            if not cls._values_intersect(v1, v2):
                return False
        return True

    @classmethod
    def _values_intersect(cls, v1: Any, v2: Any) -> bool:
        """两个条件值是否存在交集（精确 / 列表 / DSL 函数形式）"""
        # 列表
        if isinstance(v1, list) and isinstance(v2, list):
            return any(cls._values_intersect(a, b) for a in v1 for b in v2)
        if isinstance(v1, list):
            return any(cls._values_intersect(a, v2) for a in v1)
        if isinstance(v2, list):
            return any(cls._values_intersect(v1, b) for b in v2)
        # 函数形式
        f1 = cls._parse_dsl_func(v1) if isinstance(v1, str) else None
        f2 = cls._parse_dsl_func(v2) if isinstance(v2, str) else None
        if f1 or f2:
            return cls._func_values_intersect(f1, f2, v1, v2)
        # 标量
        if isinstance(v1, bool) or isinstance(v2, bool):
            return v1 == v2
        if isinstance(v1, (int, float)) and isinstance(v2, (int, float)):
            return v1 == v2
        return v1 == v2

    @staticmethod
    def _parse_dsl_func(value: Any) -> Optional[Tuple[str, List[str]]]:
        """解析 DSL 函数形式值，返回 (函数名, 参数列表)；非函数返回 None"""
        if not isinstance(value, str):
            return None
        m = re.match(r'^(starts_with|matches|contains|missing|one_of|in)\s*\((.*)\)$',
                     value.strip())
        if not m:
            return None
        name = m.group(1)
        args_raw = m.group(2)
        args = [a.strip().strip('"').strip("'") for a in args_raw.split(",")
                if a.strip()]
        return name, args

    @classmethod
    def _func_values_intersect(cls, f1: Optional[Tuple[str, List[str]]],
                               f2: Optional[Tuple[str, List[str]]],
                               v1: Any, v2: Any) -> bool:
        """函数形式条件值的交集判断（保守：无法判定时视为可相交）"""
        # missing() 只与 missing() 可同时成立
        if (f1 and f1[0] == "missing") or (f2 and f2[0] == "missing"):
            return bool(f1 and f2 and f1[0] == "missing" and f2[0] == "missing")
        if f1 and f2:
            n1, a1 = f1
            n2, a2 = f2
            if n1 == "matches" and n2 == "starts_with":
                return cls._regex_prefix_intersect(a1[0], a2)
            if n2 == "matches" and n1 == "starts_with":
                return cls._regex_prefix_intersect(a2[0], a1)
            if n1 == n2 and n1 in ("starts_with", "contains", "one_of", "in"):
                return bool(set(a1) & set(a2))
            if n1 == "matches" and n2 == "matches":
                return True  # 保守：正则语义难判定
            return True  # 保守
        # 一侧函数，一侧字面量
        if f1:
            return cls._literal_func_intersect(v2, f1)
        return cls._literal_func_intersect(v1, f2)

    @staticmethod
    def _regex_prefix_intersect(regex: str, prefixes: List[str]) -> bool:
        """matches(regex) 与 starts_with(prefix...) 是否有交集"""
        try:
            rx = re.compile(regex)
            for p in prefixes:
                if rx.fullmatch(p):
                    return True
                # 前缀自身与 regex 有公共串（前缀可扩展为匹配串）
                if rx.search(p):
                    return True
            return False
        except re.error:
            return True  # 正则无效，保守

    @staticmethod
    def _literal_func_intersect(literal: Any, func: Tuple[str, List[str]]) -> bool:
        """字面值与函数形式是否有交集"""
        name, args = func
        if name == "missing":
            return literal is None
        if name == "matches":
            if not isinstance(literal, str):
                return True
            try:
                return bool(re.search(args[0], literal))
            except re.error:
                return True
        if name == "starts_with":
            return isinstance(literal, str) and any(
                literal.startswith(a) for a in args)
        if name in ("contains", "one_of", "in"):
            if isinstance(literal, list):
                return bool(set(literal) & set(args))
            return literal in args
        return True

    @classmethod
    def _rule_covers(cls, higher: Dict[str, Any],
                     lower: Dict[str, Any]) -> bool:
        """高优先级规则是否完全覆盖低优先级规则（when 超集）"""
        w_h = higher.get("when", {})
        w_l = lower.get("when", {})
        if not isinstance(w_h, dict) or not isinstance(w_l, dict):
            return False
        # 低优先级规则的所有签名都必须被高优先级规则覆盖
        for sig_l in cls._when_signatures(w_l):
            covered = False
            for sig_h in cls._when_signatures(w_h):
                if cls._signature_covers(sig_h, sig_l):
                    covered = True
                    break
            if not covered:
                return False
        return True

    @classmethod
    def _signature_covers(cls, sig_h: Dict[str, Any],
                          sig_l: Dict[str, Any]) -> bool:
        """sig_h 是否覆盖 sig_l（sig_l 的每个键值都被 sig_h 相容/更宽）"""
        for key, value_l in sig_l.items():
            if key not in sig_h:
                return False
            value_h = sig_h[key]
            if isinstance(value_l, list):
                if isinstance(value_h, list):
                    if not set(value_l).issubset(set(value_h)):
                        return False
                else:
                    if not all(cls._values_intersect(value_h, x)
                               for x in value_l):
                        return False
            elif not cls._values_intersect(value_h, value_l):
                return False
        return True

    # --------------------------------------------------------
    # C7：audit 校验
    # --------------------------------------------------------

    def check_c7(self) -> None:
        for rule in self.rules:
            rid = rule.get("id", "")
            effect = rule.get("effect", "")
            audit = rule.get("audit")
            then = rule.get("then") or {}
            if isinstance(then, dict) and "audit" in then:
                audit = then["audit"]
            if audit == "none" and effect in HIGH_RISK_EFFECTS:
                self.errors.append(CheckError(
                    "C7", rid, f"高风险 effect '{effect}' 不允许 audit:none"))
            if audit is not None and audit not in AUDIT_VALUES:
                self.errors.append(CheckError(
                    "C7", rid, f"audit 取值非法: {audit}（应为 {sorted(AUDIT_VALUES)}）"))

    # --------------------------------------------------------
    # C8：死规则检测
    # --------------------------------------------------------

    def check_c8(self) -> None:
        dead: List[Tuple[str, str]] = []
        by_domain: Dict[str, List[Dict[str, Any]]] = {}
        for rule in self.rules:
            d = rule.get("domain", "action")
            by_domain.setdefault(d, []).append(rule)

        for domain, rules in by_domain.items():
            sorted_rules = sorted(
                rules, key=lambda r: (-r.get("priority", 0), r.get("id", "")))
            for i, rule in enumerate(sorted_rules):
                for higher in sorted_rules[:i]:
                    if self._rule_covers(higher, rule):
                        dead.append((rule.get("id", ""), higher.get("id", "")))
                        break
        for rule_id, covered_by in dead:
            self.errors.append(CheckError(
                "C8", rule_id,
                f"死规则：被更高优先级规则 {covered_by} 完全覆盖",
                severity="warning"))

    # --------------------------------------------------------
    # 汇总
    # --------------------------------------------------------

    def run(self) -> Tuple[bool, List[CheckError]]:
        self.check_c1()
        self.check_c2()
        self.check_c3()
        self.check_c4()
        self.check_c5()
        self.check_c6()
        self.check_c7()
        self.check_c8()
        errors = [e for e in self.errors if e.severity == "error"]
        return len(errors) == 0, self.errors


def main() -> int:
    argv = sys.argv[1:]
    report_file = None
    positionals: List[str] = []
    i = 0
    while i < len(argv):
        if argv[i] == "--report" and i + 1 < len(argv):
            report_file = argv[i + 1]
            i += 2
        elif argv[i].startswith("--"):
            i += 1
        else:
            positionals.append(argv[i])
            i += 1

    if positionals:
        dsl_file = positionals[0]
    else:
        dsl_file = os.path.join(os.path.dirname(__file__), "..",
                                "vendor", "genton", "config",
                                "policy_baseline.dsl")

    if not os.path.exists(dsl_file):
        print(f"✗ 文件不存在: {dsl_file}")
        return 2

    try:
        rules = parse_dsl_file(dsl_file)
    except (DslParseError, OSError) as e:
        print(f"✗ DSL 解析失败: {e}")
        return 2

    checker = DslC1C8Checker(rules)
    ok, errors = checker.run()

    print("=" * 62)
    print("策略 DSL C1-C8 静态编译检查")
    print(f"文件: {os.path.relpath(dsl_file)}  规则数: {len(rules)}")
    print("=" * 62)
    if ok:
        print("✓ C1-C8 全部通过（零错误）")
    else:
        print(f"✗ 存在 {len(errors)} 个错误 / "
              f"{len([e for e in checker.errors if e.severity == 'warning'])} 个警告")
        for e in errors:
            print(f"  ✗ [{e.check}] {e.rule_id}: {e.message}")
    for e in checker.errors:
        if e.severity == "warning":
            print(f"  ⚠ [{e.check}] {e.rule_id}: {e.message}")

    if report_file:
        lines = [
            "# 策略 DSL C1-C8 检查报告",
            "",
            f"- 文件: `{os.path.relpath(dsl_file)}`",
            f"- 规则数: {len(rules)}",
            f"- 结果: {'PASS' if ok else 'FAIL'}",
            "",
            "| 检查项 | 规则 | 级别 | 信息 |",
            "|---|---|---|---|",
        ]
        for e in checker.errors:
            lines.append(
                f"| {e.check} | {e.rule_id or 'N/A'} | "
                f"{'ERROR' if e.severity == 'error' else 'WARN'} | {e.message} |")
        with open(report_file, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print(f"\n报告已写入: {report_file}")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
