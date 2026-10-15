#!/usr/bin/env python3
"""
AI Native OS - 沙箱网关端到端（H3.3）

任务编号：H3.3（质量工程部 · 沙箱网关 e2e）
里程碑  ：A4 · 前置：B6.2 · 依赖草案：D14
验收项  （对齐 WBS A4 门禁与 D14 沙箱网关规范）：
  1. 5 OP 白名单：仅白名单操作可执行，其余一律拒绝
  2. 逐调用令牌校验 + 逐调用审计
  3. 一键撤销即时生效（撤销后下一调用即拒绝）
  4. deny 默认（未知 OP / 无令牌 → 拒绝）

实现方式：桩服务（SandboxGateway / TokenIssuer / AuditStub）进程内模拟。

可独立运行：python3 tools/e2e/test_e2e_sandbox.py
退出码：0=全部通过，1=存在失败。
"""

import hashlib
import hmac
import json
import sys
import time
from typing import Any, Dict, List, Optional

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

# 沙箱 5 OP 白名单（D14：三方 SDK 沙箱可执行的最小操作集）
OP_WHITELIST: List[str] = [
    "read.notification",   # 读通知元数据（脱敏）
    "read.contact",        # 读联系人（脱敏）
    "query.file_meta",     # 查询文件元数据
    "write.calendar",      # 写日历事件（需确认）
    "send.in_app_message", # 应用内消息
]


# ============================================================
# 桩服务
# ============================================================

class SandboxGateway:
    """沙箱网关：白名单 + 逐调用令牌/审计 + 一键撤销"""

    def __init__(self, op_whitelist: Optional[List[str]] = None,
                 signing_key: bytes = b"sandbox-key"):
        self.whitelist = set(op_whitelist or OP_WHITELIST)
        self.signing_key = signing_key
        self.tokens: Dict[str, Dict[str, Any]] = {}
        self.revoked_all = False
        self.audit: List[Dict[str, Any]] = []
        self.call_seq = 0

    # ---- 令牌签发 ----
    def issue_token(self, op: str, agent_id: str = "sdk_3p") -> str:
        tid = f"st-{len(self.tokens) + 1}"
        sig = hmac.new(self.signing_key,
                       f"{tid}:{op}".encode(), hashlib.sha256).hexdigest()
        self.tokens[tid] = {"op": op, "agent_id": agent_id, "sig": sig}
        return tid

    def _verify_token(self, token_id: str, op: str) -> bool:
        tok = self.tokens.get(token_id)
        if not tok or self.revoked_all:
            return False
        if tok["op"] != op:
            return False
        expected = hmac.new(self.signing_key,
                            f"{token_id}:{op}".encode(),
                            hashlib.sha256).hexdigest()
        return hmac.compare_digest(tok["sig"], expected)

    # ---- 调用入口 ----
    def call(self, op: str, token_id: str, params: Optional[Dict[str, Any]] = None
             ) -> Dict[str, Any]:
        self.call_seq += 1
        seq = self.call_seq
        # deny 默认：OP 不在白名单
        if op not in self.whitelist:
            self.audit.append({"seq": seq, "op": op, "result": "deny",
                               "reason": "op_not_whitelisted"})
            return {"ok": False, "reason": "op_not_whitelisted"}
        # 逐调用令牌校验
        if not self._verify_token(token_id, op):
            self.audit.append({"seq": seq, "op": op, "result": "deny",
                               "reason": "token_invalid"})
            return {"ok": False, "reason": "token_invalid"}
        # 执行（桩）
        result = {"op": op, "data": {"stub": True, "seq": seq}}
        self.audit.append({"seq": seq, "op": op, "result": "ok",
                           "token_id": token_id, "ts": time.time()})
        return {"ok": True, "result": result}

    # ---- 一键撤销 ----
    def revoke_all(self) -> int:
        self.revoked_all = True
        n = len(self.tokens)
        self.tokens.clear()
        return n

    def revoked_count(self) -> int:
        return len(self.tokens)


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

def test_5op_whitelist(t: Suite, gw: SandboxGateway):
    print("\n[1/4] 5 OP 白名单")
    t.check_eq(len(OP_WHITELIST), 5, f"白名单恰好 5 个 OP（实际 {len(OP_WHITELIST)}）")
    for op in OP_WHITELIST:
        tid = gw.issue_token(op)
        r = gw.call(op, tid)
        t.check(r["ok"], f"白名单 OP '{op}' 可执行")
    # 非白名单 OP → deny 默认
    tid = gw.issue_token("read.notification")
    r = gw.call("read.sms_content", tid)  # 不在白名单
    t.check(not r["ok"], "非白名单 OP 'read.sms_content' → 拒绝")
    t.check_eq(r["reason"], "op_not_whitelisted", "拒绝原因为 op_not_whitelisted")
    r2 = gw.call("exec.shell", tid)
    t.check(not r2["ok"], "非白名单 OP 'exec.shell' → 拒绝（deny 默认）")


def test_per_call_token_audit(t: Suite, gw: SandboxGateway):
    print("\n[2/4] 逐调用令牌校验 + 逐调用审计")
    tid = gw.issue_token("read.notification")
    before = len(gw.audit)
    r1 = gw.call("read.notification", tid)
    t.check(r1["ok"], "合法令牌调用成功")
    r2 = gw.call("read.contact", tid)  # 令牌 op 不匹配
    t.check(not r2["ok"], "令牌 op 与调用 OP 不匹配 → 拒绝")
    t.check_eq(r2["reason"], "token_invalid", "拒绝原因为 token_invalid")
    # 每次调用都有审计
    new_audits = gw.audit[before:]
    t.check_eq(len(new_audits), 2, "2 次调用产生 2 条审计")
    t.check(all("result" in a for a in new_audits), "每条审计含结果")
    t.check(new_audits[0]["result"] == "ok" and new_audits[1]["result"] == "deny",
            "审计结果正确（ok / deny）")


def test_revoke_immediate(t: Suite, gw: SandboxGateway):
    print("\n[3/4] 一键撤销即时生效")
    tid = gw.issue_token("read.notification")
    t.check(gw.call("read.notification", tid)["ok"], "撤销前可调用")
    n = gw.revoke_all()
    t.check_eq(n, 1, f"一键撤销 {n} 个令牌")
    t.check(gw.revoked_count() == 0, "撤销后令牌表清空")
    # 下一调用立即拒绝（即时生效）
    r = gw.call("read.notification", tid)
    t.check(not r["ok"], "撤销后下一调用立即拒绝")
    # 旧令牌不可再用
    r2 = gw.call("query.file_meta", tid)
    t.check(not r2["ok"], "旧令牌对任何 OP 均失效")
    # 一键撤销 = 沙箱总闸：撤销后新签发令牌同样被闸住（deny 默认直到显式恢复）
    tid2 = gw.issue_token("query.file_meta")
    t.check(not gw.call("query.file_meta", tid2)["ok"],
            "撤销总闸开启后新令牌也被拒绝（deny 默认）")
    # 显式恢复后重新签发可调用
    gw.revoked_all = False
    tid3 = gw.issue_token("query.file_meta")
    t.check(gw.call("query.file_meta", tid3)["ok"],
            "显式恢复后重新签发可调用")


def test_deny_default(t: Suite, gw: SandboxGateway):
    print("\n[4/4] deny 默认")
    # 无令牌调用
    r = gw.call("read.notification", "st-not-exist")
    t.check(not r["ok"], "无令牌 → 拒绝")
    t.check_eq(r["reason"], "token_invalid", "无令牌 → token_invalid")
    # 伪造令牌
    r2 = gw.call("read.notification", "st-forged-1")
    t.check(not r2["ok"], "伪造令牌 → 拒绝")
    # 未知 OP + 无令牌 → 拒绝（op_not_whitelisted 优先于 token）
    r3 = gw.call("unknown.op", "whatever")
    t.check(not r3["ok"] and r3["reason"] == "op_not_whitelisted",
            "未知 OP 无论令牌均拒绝")


def main() -> int:
    print("=" * 62)
    print("H3.3 沙箱网关端到端")
    print("=" * 62)
    t = Suite()
    gw = SandboxGateway()
    test_5op_whitelist(t, gw)
    gw2 = SandboxGateway()
    test_per_call_token_audit(t, gw2)
    gw3 = SandboxGateway()
    test_revoke_immediate(t, gw3)
    gw4 = SandboxGateway()
    test_deny_default(t, gw4)

    print("\n" + "=" * 62)
    print(f"汇总：PASS {t.passed} / FAIL {t.failed}")
    print("=" * 62)
    if t.failed == 0:
        print("✓ H3.3 全部通过")
        return 0
    print(f"✗ {t.failed} 个断言失败")
    return 1


if __name__ == "__main__":
    sys.exit(main())
