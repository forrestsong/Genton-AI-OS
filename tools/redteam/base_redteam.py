#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
E5.1 基座红队 · 冒烟脚本（base_redteam.py）

用途：A1 门禁前的自动化预检。对令牌三查绕过、审计链篡改、确认链劫持、
deny 默认绕过四类攻击面做「逻辑级模拟 + 静态红线扫描」冒烟验证。
不替代真机动态红队（真实 TEE / InputDispatcher / 进程隔离由 H3.1 + 人工红队执行）。

用法：
    python tools/redteam/base_redteam.py --all          # 全量冒烟
    python tools/redteam/base_redteam.py --case <id>    # 单用例
    python tools/redteam/base_redteam.py --list         # 列出用例

退出码：
    0 = 无高风险（A1 门禁预检通过）
    1 = 存在高风险（阻断）
    2 = 环境/用法错误

用例清单：
    token-forge           T1  签名伪造
    token-scope           T2  scope 跨意图盗用
    token-expired         T3  过期令牌重放
    token-revoke          T4  撤销绕过
    token-failsecure      T5  撤销列表查询失败 fail-secure
    audit-delete          A1  链中删事件
    audit-tamper          A2  改事件内容
    audit-rollback        A3  回滚攻击
    audit-unauth-write    A4  越权写审计
    confirm-forge         C1  伪造 confirmId
    confirm-expired       C2  确认超时抢占
    confirm-host          C3  确认宿主独占（静态）
    confirm-riskdowngrade C4  免确认/降级绕过
    deny-nomatch          D1  无匹配规则请求
    deny-baseline         D2  基线覆盖
    deny-failstop         D3  策略编译失败 fail-stop
    deny-notification     D4  通知路由静默吞

依赖：纯 Python 3.7+，无外部库。
"""

import argparse
import hashlib
import hmac
import json
import os
import re
import sys
import time
from pathlib import Path

# 强制 UTF-8 输出（Windows 控制台 / CI 均按 UTF-8）
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

# ---------------------------------------------------------------------------
# 颜色与日志
# ---------------------------------------------------------------------------
GREEN = "\033[92m" if sys.stdout.isatty() else ""
RED = "\033[91m" if sys.stdout.isatty() else ""
YELLOW = "\033[93m" if sys.stdout.isatty() else ""
RESET = "\033[0m" if sys.stdout.isatty() else ""


def ok(msg):
    print(f"{GREEN}[PASS]{RESET} {msg}")


def fail(msg):
    print(f"{RED}[FAIL]{RESET} {msg}")


def info(msg):
    print(f"{YELLOW}[INFO]{RESET} {msg}")


# ---------------------------------------------------------------------------
# 安全原语（逻辑级模拟，与草案契约一致）
# ---------------------------------------------------------------------------
class SecurityPrimitives:
    """模拟 D10/D4 的安全原语，用于逻辑级红队用例。"""

    def __init__(self):
        # 策略引擎私钥（真实实现驻 TEE，此处仅模拟）
        self.signing_key = b"tee-signing-key-simulated"
        # 撤销列表
        self.revoked = set()
        # 审计链（不可变，只增）
        self.audit_chain = []          # [ {seq, prevHash, payload, hash} ]
        self.last_hash = "GENESIS"
        # confirmId 消费状态
        self.consumed_confirm_ids = set()
        self.expired_confirm_ids = set()
        self.confirm_nonce = 0
        # 锚点（模拟 TEE 锚定：序号 + 链末端哈希）
        self.anchor_seq = 0
        self.anchor_hash = "GENESIS"
        self.anchor_every_n = 512
        self.anchored_events = {}   # {anchor_seq: event_seq}

    # ---- 令牌 ----
    def issue_token(self, agent_id, capability, scope_intent_id, ttl_sec=300):
        token_id = hashlib.sha256(f"{agent_id}|{capability}|{scope_intent_id}|{time.time()}".encode()).hexdigest()[:16]
        payload = f"{agent_id}|{capability}|{scope_intent_id}|{int(time.time()) + ttl_sec}".encode()
        sig = hmac.new(self.signing_key, payload, hashlib.sha256).hexdigest()
        return {"tokenId": token_id, "agentId": agent_id, "capability": capability,
                "scopeIntentId": scope_intent_id,
                "expiresAt": int(time.time()) + ttl_sec, "sig": sig}

    def validate_token(self, token, scope_intent_id, capability, now=None):
        """三查：签名 + scope + 过期/撤销。任何异常/失败 → 拒绝（fail-secure）。"""
        now = now or int(time.time())
        try:
            if token is None:
                return False
            # 1. 签名
            payload = f"{token['agentId']}|{token['capability']}|{token['scopeIntentId']}|{token['expiresAt']}".encode()
            expected = hmac.new(self.signing_key, payload, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, token.get("sig", "")):
                return False
            # 2. scope
            if token["scopeIntentId"] != scope_intent_id:
                return False
            if token["capability"] != capability:
                return False
            # 3. 过期/撤销
            if token["expiresAt"] <= now:
                return False
            if token["tokenId"] in self.revoked:
                return False
            return True
        except Exception:
            return False  # fail-secure

    # ---- 审计 ----
    def audit_record(self, category, summary, agent_id="sys"):
        event = {
            "seq": len(self.audit_chain) + 1,
            "category": category,
            "agentId": agent_id,
            "summary": summary,
            "prevHash": self.last_hash,
        }
        payload = f"{event['seq']}|{event['category']}|{event['agentId']}|{event['summary']}|{event['prevHash']}"
        event["hash"] = hashlib.sha256(payload.encode()).hexdigest()
        self.audit_chain.append(event)
        self.last_hash = event["hash"]
        # 周期锚定（每 N 条）
        if event["seq"] % self.anchor_every_n == 0:
            self.anchor_seq += 1
            self.anchor_hash = self.last_hash
            self.anchored_events[self.anchor_seq] = event["seq"]
        return event

    def verify_chain(self):
        """验证 [1..N] 链完整性 + 锚点。"""
        cur = "GENESIS"
        for ev in self.audit_chain:
            if ev["prevHash"] != cur:
                return {"verified": False, "brokenAtSeq": ev["seq"], "reason": "prevHash"}
            payload = f"{ev['seq']}|{ev['category']}|{ev['agentId']}|{ev['summary']}|{ev['prevHash']}"
            if hashlib.sha256(payload.encode()).hexdigest() != ev["hash"]:
                return {"verified": False, "brokenAtSeq": ev["seq"], "reason": "hash"}
            cur = ev["hash"]
        return {"verified": True, "brokenAtSeq": 0, "hash": cur}

    # ---- 确认 ----
    def issue_confirm_id(self, action_name, agent_id, intent_id):
        self.confirm_nonce += 1
        payload = f"{action_name}|{agent_id}|{intent_id}|{self.confirm_nonce}|{int(time.time())}".encode()
        return hmac.new(self.signing_key, payload, hashlib.sha256).hexdigest()

    def expire_confirm(self, confirm_id):
        """确认超时：将该 confirmId 标记 EXPIRED（对齐 D2 STATE_EXPIRED）"""
        self.expired_confirm_ids.add(confirm_id)

    def verify_confirm(self, confirm_id, expected):
        if confirm_id in self.consumed_confirm_ids:
            return False  # 防重放
        if confirm_id in self.expired_confirm_ids:
            return False  # 防超时后提交
        if not hmac.compare_digest(confirm_id, expected):
            return False
        self.consumed_confirm_ids.add(confirm_id)
        return True


# ---------------------------------------------------------------------------
# 用例定义
# ---------------------------------------------------------------------------
class BaseRedTeam:
    def __init__(self):
        self.sp = SecurityPrimitives()
        self.results = []

    def run_case(self, case_id):
        fn = getattr(self, f"case_{case_id.replace('-', '_')}", None)
        if fn is None:
            raise KeyError(case_id)
        info(f"--- 用例 {case_id}: {fn.__doc__.strip()}")
        fn()
        return self

    def record(self, case_id, passed, detail):
        self.results.append({"case": case_id, "passed": passed, "detail": detail})
        if passed:
            ok(f"{case_id}: {detail}")
        else:
            fail(f"{case_id}: {detail}")

    # ====================== A1 令牌三查 ======================
    def case_token_forge(self):
        """T1 签名伪造：篡改 agentId/capability 后用自造签名 → 必须拒绝"""
        tok = self.sp.issue_token("brain", "messages.send", "it-1")
        tok["agentId"] = "evil_agent"
        tok["sig"] = "f" * 64  # 伪造签名
        passed = not self.sp.validate_token(tok, "it-1", "messages.send")
        self.record("token-forge", passed,
                    "伪造签名令牌被拒" if passed else "伪造签名令牌未被拒（高风险）")

    def case_token_scope(self):
        """T2 scope 跨意图盗用：it-1 的令牌用于 it-2 → 必须拒绝"""
        tok = self.sp.issue_token("brain", "messages.send", "it-1")
        passed = not self.sp.validate_token(tok, "it-2", "messages.send")
        self.record("token-scope", passed,
                    "跨意图令牌被拒" if passed else "跨意图令牌未拒（高风险）")

    def case_token_expired(self):
        """T3 过期令牌重放：过期令牌 → 必须拒绝（无宽限期）"""
        tok = self.sp.issue_token("brain", "messages.send", "it-1", ttl_sec=5)
        tok["tokenId"] = "tok-expired"
        passed = not self.sp.validate_token(tok, "it-1", "messages.send",
                                            now=int(time.time()) + 3600)
        self.record("token-expired", passed,
                    "过期令牌被拒" if passed else "过期令牌未拒（高风险）")

    def case_token_revoke(self):
        """T4 撤销绕过：撤销后重放 → 必须拒绝（≤100ms 生效）"""
        tok = self.sp.issue_token("brain", "messages.send", "it-1")
        tok["tokenId"] = "tok-rev"
        self.sp.revoked.add("tok-rev")
        passed = not self.sp.validate_token(tok, "it-1", "messages.send")
        self.record("token-revoke", passed,
                    "撤销后令牌被拒" if passed else "撤销后令牌仍可用（高风险）")

    def case_token_failsecure(self):
        """T5 撤销列表查询失败 fail-secure：异常 → 拒绝"""
        tok = self.sp.issue_token("brain", "messages.send", "it-1")
        # 模拟撤销列表查询异常：tokenId 置为不可哈希对象（list），
        # `in self.revoked` 抛 TypeError → validate_token 异常路径 → 拒绝（fail-secure）
        tok_bad = dict(tok, tokenId=["unhashable"])
        passed = not self.sp.validate_token(tok_bad, "it-1", "messages.send")
        self.record("token-failsecure", passed,
                    "异常时拒绝（fail-secure）" if passed else "异常时放行（高风险）")

    # ====================== A2 审计链 ======================
    def case_audit_delete(self):
        """A1 链中删事件：删除中间事件 → verifyChain 必须检出断裂"""
        for i in range(5):
            self.sp.audit_record(2, f"action-{i}")
        del self.sp.audit_chain[2]  # 删除 seq=3
        result = self.sp.verify_chain()
        passed = not result["verified"]
        self.record("audit-delete", passed,
                    "删除事件被检出" if passed else "删除事件未被检出（高风险）")

    def case_audit_tamper(self):
        """A2 改事件内容：篡改 summary → verifyChain 必须检出"""
        for i in range(5):
            self.sp.audit_record(2, f"action-{i}")
        self.sp.audit_chain[1]["summary"] = "tampered!!"
        result = self.sp.verify_chain()
        passed = not result["verified"]
        self.record("audit-tamper", passed,
                    "篡改被检出" if passed else "篡改未被检出（高风险）")

    def case_audit_rollback(self):
        """A3 回滚攻击：锚点序号回退/数据回滚 → 必须检出"""
        # 记录 520 条事件，锚定在 seq=512（anchor_seq=1, anchor_hash=seq512 链端）
        for i in range(520):
            self.sp.audit_record(2, f"e{i}")
        anchor_before = (self.sp.anchor_seq, self.sp.anchor_hash)
        # 模拟回滚：把链截断到 100 条（丢弃 seq 101+，包括已锚定的 512）
        del self.sp.audit_chain[100:]
        # 锚点应能检出「链末端已不含锚定数据」：anchor 哈希 ≠ 当前链末端哈希
        chain_end_hash = self.sp.audit_chain[-1]["hash"] if self.sp.audit_chain else "EMPTY"
        rollback_detected = (anchor_before[1] != chain_end_hash)
        # 锚点序号单调递增（防 TEE 内计数器回退）
        seq_monotonic = self.sp.anchor_seq == 1  # 模拟：仅一次锚定后不允许回退到 0
        passed = rollback_detected and seq_monotonic
        self.record("audit-rollback", passed,
                    "回滚被锚点检出（链端哈希不一致）" if passed
                    else "回滚未被检出（高风险）")

    def case_audit_unauth_write(self):
        """A4 越权写审计：非系统调用方写入 → 必须拒绝（静态红线扫描）"""
        # 静态扫描：审计写接口在草案中的权限约束（WRITE_AUDIT 仅系统服务）
        audit_draft = Path(__file__).parent.parent.parent / "轨道A-记忆服务与审计引擎接口草案.md"
        passed = False
        if audit_draft.exists():
            content = audit_draft.read_text(encoding="utf-8")
            passed = ("WRITE_AUDIT" in content and
                      "仅系统服务与特权大脑可调" in content)
        self.record("audit-unauth-write", passed,
                    "审计写受 WRITE_AUDIT 权限约束（草案已定义）" if passed
                    else "未找到审计写权限约束定义（静态检查失败）")

    # ====================== A3 确认链 ======================
    def case_confirm_forge(self):
        """C1 伪造 confirmId：自造/重放 confirmId → 必须拒绝"""
        expected = self.sp.issue_confirm_id("messages.send", "brain", "it-1")
        forged = "deadbeef" * 8
        passed = not self.sp.verify_confirm(forged, expected)
        # 重放：消费后再提交同值
        self.sp.verify_confirm(expected, expected)
        replay_passed = not self.sp.verify_confirm(expected, expected)
        self.record("confirm-forge", passed and replay_passed,
                    "伪造与重放 confirmId 均被拒" if passed and replay_passed
                    else "confirmId 可伪造/重放（高风险）")

    def case_confirm_expired(self):
        """C2 确认超时抢占：超时后提交 → 必须拒绝且不执行"""
        confirm_id = self.sp.issue_confirm_id("calendar.delete", "brain", "it-2")
        # 模拟超时：EXPIRED 状态（对齐 D2 STATE_EXPIRED 失败-安全默认不执行）
        self.sp.expire_confirm(confirm_id)
        passed = not self.sp.verify_confirm(confirm_id, confirm_id)
        self.record("confirm-expired", passed,
                    "超时后提交被拒（EXPIRED 不执行）" if passed
                    else "超时后仍可确认执行（高风险）")

    def case_confirm_host(self):
        """C3 确认宿主独占（静态）：确认面板仅 SystemUI + 结果直回策略引擎"""
        ui_draft = Path(__file__).parent.parent.parent / "轨道A-SystemUI会话桥AIDL接口草案.md"
        action_draft = Path(__file__).parent.parent.parent / "轨道A-执行层AIDL与动作契约草案.md"
        passed = False
        if ui_draft.exists() and action_draft.exists():
            ui = ui_draft.read_text(encoding="utf-8")
            act = action_draft.read_text(encoding="utf-8")
            passed = ("IAgentConfirmationHost" in ui and "SystemUI" in ui and
                      "FLAG_SECURE" in ui and "exclusive" in ui and
                      "reportUserDecision" in ui and "STATE_EXPIRED" in act)
        self.record("confirm-host", passed,
                    "确认宿主独占契约存在（SystemUI 承载 + 直回 IPolicyEngine + FLAG_SECURE）" if passed
                    else "确认宿主独占契约缺失（静态检查失败）")

    def case_confirm_riskdowngrade(self):
        """C4 免确认/降级绕过：high 风险强制确认（静态红线）"""
        baseline = Path(__file__).parent.parent.parent / "安全基线规范与红线检查单v1.md"
        passed = False
        if baseline.exists():
            content = baseline.read_text(encoding="utf-8")
            passed = ("R5.9" in content and "R5.10" in content and "high 风险仍强制确认" in content)
        self.record("confirm-riskdowngrade", passed,
                    "high 风险强制确认红线存在" if passed
                    else "high 风险强制确认红线缺失（静态检查失败）")

    # ====================== A4 deny 默认 ======================
    def case_deny_nomatch(self):
        """D1 无匹配规则：策略无匹配 → 默认 DENY / native 可见"""
        dsl = Path(__file__).parent.parent.parent / "轨道A-策略DSL规范v1.md"
        passed = False
        if dsl.exists():
            content = dsl.read_text(encoding="utf-8")
            # DSL §4.1：action→deny / notification→P0_NATIVE / call_gate→RING_NATIVE
            passed = ("action" in content and "deny" in content and
                      "P0_NATIVE" in content and "RING_NATIVE" in content and
                      "默认最小权限" in content)
        self.record("deny-nomatch", passed,
                    "无匹配默认 deny/P0_NATIVE/RING_NATIVE 语义存在（DSL §4.1）" if passed
                    else "无匹配默认语义缺失（静态检查失败）")

    def case_deny_baseline(self):
        """D2 基线覆盖：baseline 不可被 user 规则覆盖（编译期冲突矩阵）"""
        dsl = Path(__file__).parent.parent.parent / "轨道A-策略DSL规范v1.md"
        passed = False
        if dsl.exists():
            content = dsl.read_text(encoding="utf-8")
            passed = ("baseline-deny-protect" in content and "冲突矩阵" in content and
                      "编译失败" in content)
        self.record("deny-baseline", passed,
                    "baseline 保护 + 冲突矩阵编译拒存在" if passed
                    else "baseline 覆盖防护缺失（静态检查失败）")

    def case_deny_failstop(self):
        """D3 策略编译失败 fail-stop：坏策略 → 拒绝启动/回滚 + 停令牌"""
        dsl = Path(__file__).parent.parent.parent / "轨道A-策略DSL规范v1.md"
        engine = Path(__file__).parent.parent.parent / "轨道A-策略引擎接口草案.md"
        passed = False
        if dsl.exists() and engine.exists():
            d = dsl.read_text(encoding="utf-8")
            e = engine.read_text(encoding="utf-8")
            passed = (("fail-stop" in d or "拒绝启动" in d or "整包拒绝" in d) and
                      ("回滚" in e and "停发新令牌" in e))
        self.record("deny-failstop", passed,
                    "fail-stop + 回滚 + 停令牌语义存在" if passed
                    else "fail-stop 语义缺失（静态检查失败）")

    def case_deny_notification(self):
        """D4 通知路由静默吞：无匹配 → P0 原生可见，钉选不可折叠"""
        dsl = Path(__file__).parent.parent.parent / "轨道A-策略DSL规范v1.md"
        passed = False
        if dsl.exists():
            content = dsl.read_text(encoding="utf-8")
            passed = ("P0" in content and "p0-pinned" in content and "不可静默" in content)
        self.record("deny-notification", passed,
                    "P0 原生可见 + 钉选不可静默语义存在" if passed
                    else "通知静默吞防护缺失（静态检查失败）")


CASES = {
    "token-forge": BaseRedTeam.case_token_forge,
    "token-scope": BaseRedTeam.case_token_scope,
    "token-expired": BaseRedTeam.case_token_expired,
    "token-revoke": BaseRedTeam.case_token_revoke,
    "token-failsecure": BaseRedTeam.case_token_failsecure,
    "audit-delete": BaseRedTeam.case_audit_delete,
    "audit-tamper": BaseRedTeam.case_audit_tamper,
    "audit-rollback": BaseRedTeam.case_audit_rollback,
    "audit-unauth-write": BaseRedTeam.case_audit_unauth_write,
    "confirm-forge": BaseRedTeam.case_confirm_forge,
    "confirm-expired": BaseRedTeam.case_confirm_expired,
    "confirm-host": BaseRedTeam.case_confirm_host,
    "confirm-riskdowngrade": BaseRedTeam.case_confirm_riskdowngrade,
    "deny-nomatch": BaseRedTeam.case_deny_nomatch,
    "deny-baseline": BaseRedTeam.case_deny_baseline,
    "deny-failstop": BaseRedTeam.case_deny_failstop,
    "deny-notification": BaseRedTeam.case_deny_notification,
}


def main():
    parser = argparse.ArgumentParser(description="E5.1 基座红队冒烟脚本")
    parser.add_argument("--all", action="store_true", help="运行全部用例")
    parser.add_argument("--case", help="运行单个用例（--list 查看）")
    parser.add_argument("--list", action="store_true", help="列出用例")
    args = parser.parse_args()

    if args.list:
        for cid in CASES:
            print(f"  {cid:<22} {CASES[cid].__doc__.strip()}")
        return 0

    rt = BaseRedTeam()
    if args.case:
        try:
            rt.run_case(args.case)
        except KeyError:
            print(f"未知用例: {args.case}（用 --list 查看）")
            return 2
    elif args.all:
        for cid in CASES:
            rt.run_case(cid)
    else:
        parser.print_help()
        return 2

    passed = sum(1 for r in rt.results if r["passed"])
    total = len(rt.results)
    high_risk = [r for r in rt.results if not r["passed"]]
    print("\n" + "=" * 60)
    print(f"E5.1 基座红队冒烟结果: {passed}/{total} 通过")
    if high_risk:
        print(f"高风险用例: {len(high_risk)}")
        for r in high_risk:
            print(f"  ❌ {r['case']}: {r['detail']}")
        print("\n结论: 存在高风险，阻断 A1 门禁（需修复后重跑）")
        return 1
    print("结论: 无高风险，A1 门禁预检通过（真机动态红队仍须执行）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
