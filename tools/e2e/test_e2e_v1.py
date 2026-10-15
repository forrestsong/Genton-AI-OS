#!/usr/bin/env python3
"""
AI Native OS - 感知→策略→执行→审计 端到端 v1（H3.1）

任务编号：H3.1（质量工程部 · e2e v1 模拟器）
里程碑  ：A1 · 前置：A3.2, A4.2, A5.2, A7.1 · 依赖草案：D2/D4
验收项  （对齐 WBS A1 门禁与 A11.2→A3.2 链路）：
  1. deny 默认：未注册/无令牌能力 → DENY，不执行，落审计
  2. 令牌三查：签名 / scope / TTL 全链路校验
  3. 确认回灌全链路：高敏动作 → 确认流 → 用户 ALLOW → 令牌 → 执行 → 审计
  4. 感知服务以桩实现（A2.1 前）
  5. 审计哈希链完整（A5.2 语义）

实现方式：桩服务（PerceptionStub / ExecutorStub / AuditStub）+ 真实策略
引擎（vendor/genton/services/policy_engine.py，yaml 桩注入），以
进程内模拟器串联完整链路。

可独立运行：python3 tools/e2e/test_e2e_v1.py
退出码：0=全部通过，1=存在失败。
"""

import hashlib
import importlib.util
import json
import os
import sys
import types
from typing import Any, Dict, List, Optional

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

TOOLS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJECT_ROOT = os.path.dirname(TOOLS_DIR)
ENGINE_PATH = os.path.join(PROJECT_ROOT, "vendor", "genton", "services",
                           "policy_engine.py")


# ============================================================
# 桩服务
# ============================================================

class PerceptionStub:
    """感知桩：产出意图（A2.1 真实感知服务前以桩实现）"""

    def __init__(self):
        self.observations: List[Dict[str, Any]] = []

    def observe(self, capability: str, action: str = None,
                context: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        obs = {"capability": capability, "action": action,
               "context": context or {}, "ts": len(self.observations)}
        self.observations.append(obs)
        return obs


class ExecutorStub:
    """执行桩：仅接受通过三查的令牌执行动作"""

    def __init__(self):
        self.executed: List[Dict[str, Any]] = []
        self.rejected: List[Dict[str, Any]] = []

    def execute(self, action: str, params: Dict[str, Any],
                token_ok: bool) -> bool:
        if not token_ok:
            self.rejected.append({"action": action, "params": params,
                                  "reason": "token_invalid"})
            return False
        self.executed.append({"action": action, "params": params})
        return True


class AuditStub:
    """审计桩：哈希链记录（A5.2 语义）"""

    ZERO = "0" * 64
    FIELDS = ["seq", "eventId", "timestamp", "category", "agentId",
              "actor", "action", "summary", "payloadHash", "prevHash",
              "anchorRef"]

    def __init__(self):
        self.entries: List[Dict[str, Any]] = []
        self._prev = self.ZERO

    def record(self, category: str, action: str, summary: str,
               agent_id: str = "agent_a", actor: str = "agent") -> str:
        event_id = f"evt-{len(self.entries)}"
        payload = {"category": category, "action": action,
                   "summary": summary}
        payload_hash = hashlib.sha256(
            json.dumps(payload, sort_keys=True).encode()).hexdigest()
        entry = {
            "seq": len(self.entries), "eventId": event_id,
            "timestamp": len(self.entries), "category": category,
            "agentId": agent_id, "actor": actor, "action": action,
            "summary": summary, "payloadHash": payload_hash,
            "prevHash": self._prev, "anchorRef": "anchor-1",
        }
        self.entries.append(entry)
        self._prev = self._hash_entry(entry)
        return event_id

    @staticmethod
    def _hash_entry(e: Dict[str, Any]) -> str:
        body = {k: v for k, v in e.items() if k != "prevHash"}
        return hashlib.sha256(
            json.dumps(body, sort_keys=True, default=str).encode()).hexdigest()

    def verify_chain(self) -> List[str]:
        errs: List[str] = []
        prev = self.ZERO
        for e in self.entries:
            if e["prevHash"] != prev:
                errs.append(f"seq={e['seq']} prevHash 断裂")
            prev = self._hash_entry(e)
        return errs


# ============================================================
# 模拟器：串联全链路
# ============================================================

class Simulator:
    """感知→策略→执行→审计 端到端模拟器"""

    def __init__(self, engine: Any):
        self.perception = PerceptionStub()
        self.executor = ExecutorStub()
        self.audit = AuditStub()
        self.engine = engine

    def handle_intent(self, capability: str, action: str = None,
                      context: Optional[Dict[str, Any]] = None,
                      confirm: bool = False) -> Dict[str, Any]:
        """处理一次感知意图，返回链路结果"""
        obs = self.perception.observe(capability, action, context)
        c = self.engine.EvaluationContext(
            agent_id="agent_a", intent_id="intent_1",
            capability=capability, action=action,
            context=context or {})
        c.domain_fields = {"domain": self.engine.Domain.ACTION}
        decision = self.engine.request_capability(c)

        # 审计：策略决策
        self.audit.record("policy_decision", capability or "unknown",
                          decision.decision.name)

        # 确认流
        if decision.decision == self.engine.Decision.REQUIRE_CONFIRMATION:
            if not confirm:
                self.audit.record("confirmation", capability, "pending")
                return {"status": "confirm_pending",
                        "confirm_id": decision.confirm_id}
            res = self.engine.report_user_decision(
                decision.confirm_id, self.engine.UserDecision.ALLOW_ONCE)
            self.audit.record("confirmation", capability, "user_allow")
            if res is None or res.decision != self.engine.Decision.GRANT:
                return {"status": "deny", "reason": "user_denied"}
            decision = res

        # 令牌三查
        if decision.decision == self.engine.Decision.GRANT and decision.token:
            tok = decision.token
            three_checks = self.engine.validate_token(
                tok.token_id, "intent_1", capability)
            if not three_checks:
                self.audit.record("action_execution", action or capability,
                                  "token_three_checks_failed")
                return {"status": "deny", "reason": "token_three_checks_failed"}
            executed = self.executor.execute(action or capability,
                                             {"from": obs["ts"]},
                                             token_ok=True)
            self.audit.record("action_execution", action or capability,
                              "ok" if executed else "failed")
            return {"status": "executed" if executed else "failed"}
        elif decision.decision == self.engine.Decision.DENY:
            self.executor.execute(action or capability, {}, token_ok=False)
            return {"status": "deny", "reason": "deny_default"}
        return {"status": "unknown"}


# ============================================================
# 断言框架
# ============================================================

class Suite:
    def __init__(self):
        self.passed = 0
        self.failed = 0

    def check(self, cond: bool, msg: str):
        if cond:
            self.passed += 1
            print(f"  ✓ {msg}")
        else:
            self.failed += 1
            print(f"  ✗ {msg}")

    def check_eq(self, actual: Any, expected: Any, msg: str):
        self.check(actual == expected,
                   f"{msg}（expected={expected!r}, actual={actual!r}）")


# ============================================================
# 测试用例
# ============================================================

def load_engine() -> Any:
    yaml_stub = types.ModuleType("yaml")
    yaml_stub.safe_load = lambda *a, **k: (_ for _ in ()).throw(
        RuntimeError("yaml stub"))
    yaml_stub.safe_load_all = lambda *a, **k: (_ for _ in ()).throw(
        RuntimeError("yaml stub"))
    sys.modules.setdefault("yaml", yaml_stub)
    spec = importlib.util.spec_from_file_location(
        "genton_policy_engine_e2e", ENGINE_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    engine = mod.PolicyEngine()
    engine.rules = [
        mod.PolicyRule(id="sys.action.baseline.payment-confirm",
                       domain=mod.Domain.ACTION, effect="require_confirmation",
                       when={"capability": "agent.payment_action"},
                       then={"audit": "full"}, priority=1000, baseline=True,
                       source="platform"),
        mod.PolicyRule(id="sys.action.baseline.query-allow",
                       domain=mod.Domain.ACTION, effect="allow",
                       when={"capability": "agent.query_packages"},
                       then={"audit": "periodic"}, priority=1000,
                       baseline=True, source="platform"),
        mod.PolicyRule(id="sys.action.no-token-deny",
                       domain=mod.Domain.ACTION, effect="deny",
                       when={"capability": "missing()"},
                       then={"audit": "full"}, priority=900, source="platform"),
    ]
    engine.baseline_rules = [r for r in engine.rules if r.baseline]
    engine._compile_rules()
    engine.EvaluationContext = mod.EvaluationContext
    engine.Domain = mod.Domain
    engine.Decision = mod.Decision
    engine.UserDecision = mod.UserDecision
    return engine


def test_deny_default(t: Suite, sim: Simulator):
    print("\n[1/4] deny 默认（未注册能力）")
    r = sim.handle_intent("agent.not_registered_cap")
    t.check_eq(r["status"], "deny", "未注册能力 → DENY")
    t.check(len(sim.executor.executed) == 0, "deny 默认不执行任何动作")
    t.check(any(e["category"] == "policy_decision" and "DENY" in e["summary"]
                for e in sim.audit.entries), "决策落审计")


def test_token_three_checks(t: Suite, sim: Simulator):
    print("\n[2/4] 令牌三查全链路")
    r = sim.handle_intent("agent.query_packages", action="query.apps")
    t.check_eq(r["status"], "executed", "查询类动作 GRANT → 执行")
    t.check(len(sim.executor.executed) >= 1, "执行器收到动作")
    # 审计含 action_execution
    t.check(any(e["category"] == "action_execution" for e in sim.audit.entries),
            "执行落审计")


def test_confirmation_feedthrough(t: Suite, sim: Simulator):
    print("\n[3/4] 确认回灌全链路")
    # 未确认 → confirm_pending
    r1 = sim.handle_intent("agent.payment_action", action="payment.confirm")
    t.check_eq(r1["status"], "confirm_pending", "高敏动作先挂起确认流")
    t.check(r1.get("confirm_id") is not None, "返回 confirmId")
    # 用户确认 → 令牌 → 执行
    r2 = sim.handle_intent("agent.payment_action", action="payment.confirm",
                           confirm=True)
    t.check_eq(r2["status"], "executed", "用户确认后执行")
    t.check(any(e["category"] == "confirmation" for e in sim.audit.entries),
            "确认落审计")


def test_audit_hash_chain(t: Suite, sim: Simulator):
    print("\n[4/4] 审计哈希链完整")
    errs = sim.audit.verify_chain()
    t.check_eq(errs, [], "审计哈希链完整（无断裂）")
    t.check(len(sim.audit.entries) > 0, "审计链有事件")
    t.check_eq(sim.audit.entries[0]["prevHash"], AuditStub.ZERO,
               "首条 prevHash = 64 个 0")


def main() -> int:
    print("=" * 62)
    print("H3.1 感知→策略→执行→审计 端到端 v1")
    print("=" * 62)
    t = Suite()
    engine = load_engine()
    sim = Simulator(engine)
    test_deny_default(t, sim)
    test_token_three_checks(t, sim)
    test_confirmation_feedthrough(t, sim)
    test_audit_hash_chain(t, sim)

    print("\n" + "=" * 62)
    print(f"汇总：PASS {t.passed} / FAIL {t.failed}")
    print("=" * 62)
    if t.failed == 0:
        print("✓ H3.1 全链路通过")
        return 0
    print(f"✗ {t.failed} 个断言失败")
    return 1


if __name__ == "__main__":
    sys.exit(main())
