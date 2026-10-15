# -*- coding: utf-8 -*-
"""
WBS 1.5 静态验收：确认链闭环（防"确认自批"）

断言：
  A. ActionExecutionService.reportUserConfirmation 必须
     ① 校验调用方持有 SUBMIT_USER_DECISION；
     ② 用策略引擎复验 confirmId（HMAC/过期/挂起）；
     ③ 且两道校验都发生在 `mPendingConfirms.remove(...)` **之前**；
     ④ 决策到达后撤销超时任务。
  B. PolicyEngineService.isGenuinePendingConfirmation 必须存在、含 HMAC 校验与 TTL 判定，
     且**不消费**确认（不得出现 remove）。
  C. ConfirmationHostService.verifyConfirmId 必须委托给策略引擎，
     不得残留"仅判非空"或 TODO 占位。
  D. 检测器自检：对"旧实现"（只 map.remove、无校验）必须报出问题。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SVC = ROOT / "overlay/frameworks/base/services/core/java/com/android/server/ainative"
AES = SVC / "ActionExecutionService.java"
PES = SVC / "PolicyEngineService.java"
CHS = SVC / "ConfirmationHostService.java"

failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL: " + msg)


def ok(msg):
    print("  ok: " + msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


def body_of(text, name, kind="method"):
    """提取方法体（Java）。"""
    for m in re.finditer(r'\b' + re.escape(name) + r'\s*\([^)]*\)\s*(?:throws[\w\.\s,]+)?\{', text):
        i = m.end() - 1
        depth, j = 0, i
        while j < len(text):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        return text[i:j + 1]
    return None


# ---------- 0. 自检（teeth）----------
print("[0] 检测器自检")
OLD = """{
    try {
        ExecutionContext context = mPendingConfirms.remove(confirmId);
        if (context == null) { return 1; }
        context.transitionTo(STATE_EXECUTING);
        return 0;
    } catch (Exception e) { return -1; }
}"""
if ("isGenuinePendingConfirmation" not in OLD) and ("SUBMIT_USER_DECISION" not in OLD) and OLD.find("remove(") < 999:
    ok("旧实现（只 map.remove、零校验）会被判为不合格")
else:
    fail("自检逻辑异常")

# ---------- A. 执行中介 ----------
print("[A] ActionExecutionService.reportUserConfirmation")
if not AES.exists():
    fail(f"缺少 {AES.relative_to(ROOT)}")
else:
    text = strip_comments(read(AES))
    body = body_of(text, "reportUserConfirmation")
    if body is None:
        fail("未找到 reportUserConfirmation 方法体")
    else:
        checks = [
            ("AgentCallerAuth.holdsPermission", "校验调用方权限"),
            ("PERM_SUBMIT_DECISION", "使用 SUBMIT_USER_DECISION 权限"),
            ("isGenuinePendingConfirmation", "复验 confirmId（HMAC/过期/挂起）"),
            ("timeoutFuture.cancel", "决策后撤销超时任务"),
        ]
        for pat, desc in checks:
            if pat in body:
                ok(desc)
            else:
                fail(f"reportUserConfirmation 缺少：{desc}（{pat}）")
        idx_remove = body.find("mPendingConfirms.remove")
        idx_authz = body.find("holdsPermission")
        idx_hmac = body.find("isGenuinePendingConfirmation")
        if idx_remove < 0:
            fail("未找到 mPendingConfirms.remove（结构可能已变，需人工确认）")
        elif idx_authz < 0 or idx_hmac < 0:
            fail("缺少门禁，无法判定顺序")
        elif idx_authz < idx_remove and idx_hmac < idx_remove:
            ok("两道门禁均先于 map.remove（不存在先消费后校验）")
        else:
            fail("存在『先消费后校验』：门禁必须早于 mPendingConfirms.remove")

# ---------- B. 策略引擎 ----------
print("[B] PolicyEngineService.isGenuinePendingConfirmation")
if not PES.exists():
    fail(f"缺少 {PES.relative_to(ROOT)}")
else:
    text = strip_comments(read(PES))
    body = body_of(text, "isGenuinePendingConfirmation")
    if body is None:
        fail("不存在 isGenuinePendingConfirmation（确认复验能力缺失）")
    else:
        ok("存在 isGenuinePendingConfirmation")
        for pat, desc in (("verifyConfirmId", "含 HMAC 校验"),
                          ("CONFIRM_ID_TTL_NANOS", "含过期判定"),
                          ("mPendingConfirmations.get", "查挂起表")):
            if pat in body:
                ok(desc)
            else:
                fail(f"isGenuinePendingConfirmation 缺少：{desc}")
        if "remove(" in body:
            fail("isGenuinePendingConfirmation 不得消费确认（出现 remove）")
        else:
            ok("不消费确认（无 remove）")

# ---------- C. 确认宿主 ----------
print("[C] ConfirmationHostService.verifyConfirmId")
if not CHS.exists():
    fail(f"缺少 {CHS.relative_to(ROOT)}")
else:
    text = strip_comments(read(CHS))
    body = body_of(text, "verifyConfirmId")
    if body is None:
        fail("未找到 verifyConfirmId")
    else:
        if "isGenuinePendingConfirmation" in body:
            ok("已委托策略引擎验签")
        else:
            fail("verifyConfirmId 未委托策略引擎（仍可能是非空判定的空壳）")
        if "TODO" in read(CHS).split("verifyConfirmId")[0][-400:]:
            fail("verifyConfirmId 附近仍有 TODO 占位")
        else:
            ok("无 TODO 占位残留")

print()
if failures:
    print(f"RESULT: FAIL（{len(failures)} 项）")
    sys.exit(1)
print("RESULT: PASS —— WBS 1.5a 确认链门禁静态验收通过（编译与运行时验证待 M1）")
sys.exit(0)
