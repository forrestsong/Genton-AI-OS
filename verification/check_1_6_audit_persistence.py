# -*- coding: utf-8 -*-
"""
WBS 1.6 静态验收：审计链持久化 + 锚点真实签名

背景：承载"Agent 干了什么"的 AgentAuditService 此前是**纯内存 ArrayList**，
重启即全丢；TeeAnchorManager 的"签名"是 `别名 + SHA-256` 字符串拼接，
"验签"就是重算同一哈希 —— 任何同权限者都能伪造并"验证通过"，
且 `new TeeAnchorManager(...)` 全仓零命中（调用点必然 NPE 后被吞）。

断言：
  A. AgentAuditService 必须落盘 + fsync；启动时恢复并校验连续性；锚点管理器必须被实例化。
  B. TeeAnchorManager 必须用真实 Signature API 签名/验签；无密钥时 fail-closed；
     密钥必须是签名算法（EC），不得再出现假签名拼接。
  C. 自检：检测器必须能识别旧的假签名实现。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SVC = ROOT / "overlay/frameworks/base/services/core/java/com/android/server/ainative"
AAS = SVC / "AgentAuditService.java"
TAM = SVC / "TeeAnchorManager.java"

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


FAKE_SIGN = re.compile(r'TEE_KEY_ALIAS\s*\+\s*":"\s*\+\s*computeHash')

# ---------- C. 自检 ----------
print("[C] 检测器自检")
old = 'String signature = TEE_KEY_ALIAS + ":" + computeHash(data);'
if FAKE_SIGN.search(old):
    ok("能识别旧的假签名模式（真阳性）")
else:
    fail("检测器无法识别假签名 —— 后续 PASS 不可信")
if not FAKE_SIGN.search("return Base64.getEncoder().encodeToString(sig.sign());"):
    ok("真实签名实现不被误报（真阴性）")
else:
    fail("真实签名被误报")

# ---------- A. 持久化 ----------
print("[A] AgentAuditService 持久化")
if not AAS.exists():
    fail(f"缺少 {AAS.relative_to(ROOT)}")
else:
    text = strip_comments(read(AAS))
    checks = [
        (r'AUDIT_DIR|/data/system/ainative/audit', "有落盘目录常量"),
        (r'\.jsonl', "使用 JSONL 追加日志"),
        (r'getFD\(\)\.sync\(\)', "写入后 fsync（掉电不丢尾部）"),
        (r'private void restoreChainFromDisk\s*\(', "存在启动恢复方法"),
        (r'restoreChainFromDisk\s*\(\s*\)\s*;', "构造函数中调用恢复"),
        (r'persistEvent\s*\(event\)', "recordEvent 中落盘"),
        (r'mTeeAnchorManager\s*=\s*new TeeAnchorManager\s*\(', "锚点管理器已实例化（原为从未实例化）"),
    ]
    for pat, desc in checks:
        if re.search(pat, text):
            ok(desc)
        else:
            fail(f"缺少：{desc}")
    rec = body_of(text, "recordEvent")
    if rec and "persistEvent" in rec:
        ok("落盘发生在 recordEvent 内（非仅内存追加）")
    else:
        fail("recordEvent 未落盘 —— 证据仍在内存里蒸发")
    rest = body_of(text, "restoreChainFromDisk")
    if rest and ("broken" in rest and ("prevHash" in rest or "seq" in rest)):
        ok("恢复时校验 seq/prevHash 连续性")
    else:
        fail("恢复未校验链连续性")

# ---------- B. 锚点 ----------
print("[B] TeeAnchorManager 真实签名")
if not TAM.exists():
    fail(f"缺少 {TAM.relative_to(ROOT)}")
else:
    raw = read(TAM)
    text = strip_comments(raw)
    if FAKE_SIGN.search(text):
        fail("仍存在假签名实现（别名+哈希）")
    else:
        ok("不存在假签名实现")
    if "Thread.sleep" in text:
        fail("仍存在『模拟 TEE 签名延迟』的 Thread.sleep")
    else:
        ok("不存在模拟签名的 sleep")
    for pat, desc in (
        (r'Signature\.getInstance\s*\(\s*"SHA256withECDSA"\s*\)', "使用真实 Signature 算法"),
        (r'\.initSign\s*\(', "真实签名（initSign）"),
        (r'\.initVerify\s*\(', "真实验签（initVerify）"),
        (r'IllegalStateException', "无密钥时 fail-closed（抛异常而非返回假签名）"),
        (r'KEY_ALGORITHM_EC', "签名密钥使用 EC（原用 AES + PURPOSE_SIGN，必被 Keystore 拒绝）"),
    ):
        if re.search(pat, text):
            ok(desc)
        else:
            fail(f"缺少：{desc}")
    if re.search(r'KEY_ALGORITHM_AES', text):
        fail("仍以 AES 生成签名密钥（Keystore 会拒绝 PURPOSE_SIGN）")
    else:
        ok("不再用 AES 生成签名密钥")
    if "StrongBox" in raw and "WBS 1.6b" in raw:
        ok("已显式声明：Keystore 签名 ≠ 硬件 TEE 背书（避免夸大）")
    else:
        fail("缺少『非硬件 TEE 背书』的诚实说明")

print()
if failures:
    print(f"RESULT: FAIL（{len(failures)} 项）")
    sys.exit(1)
print("RESULT: PASS —— WBS 1.6a 审计持久化与真实锚点签名静态验收通过（编译与真机验证待 M1）")
sys.exit(0)
