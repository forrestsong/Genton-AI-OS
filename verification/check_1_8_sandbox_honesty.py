# -*- coding: utf-8 -*-
"""
WBS 1.8 静态验收：沙箱不得"假装隔离生效"

背景：AgentSandboxManagerService.startSandbox 在没有真实隔离的情况下返回 STATUS_OK，
并发放 sandboxPid = -1 / sandboxUid = -1 的句柄；registerConsent 的"签名校验"
实为"调用方自己先登记过"（自证）；且这两个服务文件只存在于 patches/，从未并入 overlay。

WBS 1.8 的口径是"真实现，或从对外能力清单下线"。本检查验收的是**诚实性**：
在隔离未实现时，必须 fail-closed 拒绝启动，而不是发放假句柄。

断言：
  A. startSandbox 必须有显式的隔离能力门禁，且门禁必须早于任何句柄构造。
  B. Binder 面必须要求 MANAGE_SANDBOX 权限。
  C. 必须显式声明"清单签名未校验""不得加入 AIDL"等诚实性约束。
  D. 自检：检测器必须能识别旧实现（无门禁、直接返回 OK + pid=-1）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MGR = ROOT / "patches/p5-missing-services/service/AgentSandboxManagerService.java"

failures = []


def fail(m):
    failures.append(m)
    print("FAIL: " + m)


def ok(m):
    print("  ok: " + m)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def strip_comments(t):
    t = re.sub(r'/\*.*?\*/', lambda m: "\n" * m.group(0).count("\n"), t, flags=re.S)
    return re.sub(r'//[^\n]*', '', t)


def body_of(text, name):
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


def isolation_gate_ok(body: str) -> bool:
    """门禁存在、且早于句柄构造。"""
    gate = re.search(r'!\s*ISOLATION_IMPLEMENTED\s*\)?\s*\{', body)
    if not gate:
        return False
    handle = re.search(r'h\.sandboxPid\s*=\s*-1|rec\.handle\s*=\s*h', body)
    if not handle:
        return False
    return gate.start() < handle.start()


# ---------- D. 自检 ----------
print("[D] 检测器自检")
OLD_BODY = """{
    if (manifest == null || manifest.agentId == null) { return STATUS_DENIED; }
    if (!mConsented.containsKey(manifest.agentId)) { return STATUS_UNSIGNED; }
    SandboxHandle h = new SandboxHandle();
    h.sandboxPid = -1;
    h.sandboxUid = -1;
    rec.handle = h;
    return STATUS_OK;
}"""
if not isolation_gate_ok(OLD_BODY):
    ok("能识别旧实现（无隔离门禁却发放 pid=-1 句柄并返回 OK）")
else:
    fail("检测器无法识别旧实现 —— 后续 PASS 不可信")

# ---------- 主检查 ----------
print("[A] startSandbox 隔离门禁")
if not MGR.exists():
    fail(f"缺少 {MGR.relative_to(ROOT)}")
else:
    raw = read(MGR)
    text = strip_comments(raw)
    if re.search(r'ISOLATION_IMPLEMENTED\s*=\s*false\s*;', text):
        ok("显式声明 ISOLATION_IMPLEMENTED = false")
    else:
        fail("未看到显式的隔离能力开关（无法表达『未实现』这一事实）")
    body = body_of(text, "startSandbox")
    if body is None:
        fail("未找到 startSandbox")
    elif isolation_gate_ok(body):
        ok("门禁存在且早于句柄构造（不会发放假句柄）")
    else:
        fail("门禁缺失或晚于句柄构造 —— 仍会发放 pid/uid = -1 的假沙箱句柄")

    print("[B] Binder 面鉴权")
    if re.search(r'MANAGE_SANDBOX', text) and re.search(r'onTransact', text):
        ok("onTransact 要求 MANAGE_SANDBOX")
    else:
        fail("Binder 面缺少 MANAGE_SANDBOX 鉴权")

    print("[C] 诚实性声明")
    for pat, desc in (
        (r'ISOLATION_IMPLEMENTED', "有隔离能力开关"),
        (r'签名\s*NOT\s*verified|NOT verified|签名未校验|不做清单签名校验', "声明清单签名未校验"),
        (r'绝不可把它加入\s*IAgentSandboxManager|不得加入\s*IAgentSandboxManager', "声明不得暴露为 AIDL"),
        (r'假沙箱句柄', "声明不得发放假句柄"),
    ):
        if re.search(pat, raw):
            ok(desc)
        else:
            fail(f"缺少诚实性声明：{desc}")

    if re.search(r'真实进程拉起待接入\s*$', raw, re.M):
        fail("仍保留含糊的『待接入』说法（应显式说明隔离未实现且 fail-closed）")
    else:
        ok("无含糊的『待接入』残留")

print()
if failures:
    print(f"RESULT: FAIL（{len(failures)} 项）")
    sys.exit(1)
print("RESULT: PASS —— WBS 1.8a 沙箱诚实性（fail-closed，不发假句柄）静态验收通过")
print("注意：隔离本身**仍未实现**；1.8 只有在真机跑通 isolatedProcess 后才能记为完成。")
sys.exit(0)
