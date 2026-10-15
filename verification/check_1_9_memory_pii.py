# -*- coding: utf-8 -*-
"""
WBS 1.9 静态验收：记忆写入不得落高风险 PII 明文

背景：AgentMemoryService.maskPII 对身份证号/银行卡号/密码**只置标记不掩码**，
而"拒绝写入"逻辑只作用于 KIND_LONG_TERM —— 于是 WORKING / SYSTEM 记忆把
证件号、银行卡号、密码**原样落库**。

断言：
  A. 高风险类别（身份证/银行卡/凭据赋值）必须同时**掩码**并置高风险标记。
  B. 写入路径必须有防御性不变量：高风险但未掩码 → 拒绝（fail-closed）。
  C. 落库内容必须取掩码后字段（maskedContent），不得直接使用原始 content。
  D. 长期记忆策略不得因引入"敏感关键词"而放宽（关键词仍应触发长期记忆拒绝）。
  E. 自检：检测器必须能识别旧实现（只标记不掩码）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SVC = ROOT / "overlay/frameworks/base/services/core/java/com/android/server/genton"
MEM = SVC / "AgentMemoryService.java"

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


def _braces(body, open_idx):
    depth, j = 0, open_idx
    while j < len(body):
        if body[j] == "{":
            depth += 1
        elif body[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    return body[open_idx:j + 1]


def high_risk_is_masked(body: str) -> bool:
    """每个高风险类别都必须既掩码又置标记。

    注意两种写法都要支持：直接 `if (PATTERN.matcher(x).find())`，
    以及先取 `Matcher` 变量再 `if (m.find())` —— 本检查器初版只认前者，
    把正确实现误判为"未掩码"（工具比代码更容易错）。
    """
    for pat_name in ("ID_CARD_PATTERN", "BANK_CARD_PATTERN"):
        # 条件形如 `if (PATTERN.matcher(x).find()) {` —— `)` 之后还有 `.find()`，
        # 因此不能用 `\)\s*\{`（本检查器第二次踩这个坑）。
        m = re.search(r'if\s*\([^)]*' + pat_name + r'[^)]*\)[^{]*\{', body)
        if not m:
            return False
        block = _braces(body, m.end() - 1)
        if "replaceAll" not in block or "hasHighRiskPII" not in block:
            return False
    # 凭据赋值：接受 matcher 变量写法，检查模式出现处附近确有掩码与标记
    idx = body.find("PASSWORD_ASSIGN_PATTERN")
    if idx < 0:
        return False
    window = body[idx:idx + 400]
    if "replaceAll" not in window or "hasHighRiskPII" not in window:
        return False
    return True


# ---------- E. 自检 ----------
print("[E] 检测器自检")
OLD_MASK = """{
    if (ID_CARD_PATTERN.matcher(content).find()) {
        result.hasHighRiskPII = true;
        result.reason = "身份证号";
    }
    if (BANK_CARD_PATTERN.matcher(content).find()) {
        result.hasHighRiskPII = true;
        result.reason = "银行卡号";
    }
    if (PASSWORD_ASSIGN_PATTERN.matcher(content).find()) {
        result.hasHighRiskPII = true;
        result.reason = "密码相关";
    }
    return result;
}"""
if not high_risk_is_masked(OLD_MASK):
    ok("能识别旧实现（只标记不掩码 → 明文落库）")
else:
    fail("检测器无法识别旧实现 —— 后续 PASS 不可信")

# ---------- 主检查 ----------
print("[A] 高风险类别必须掩码")
if not MEM.exists():
    fail(f"缺少 {MEM.relative_to(ROOT)}")
else:
    raw = read(MEM)
    text = strip_comments(raw)
    mask_body = body_of(text, "maskPII")
    if mask_body is None:
        fail("未找到 maskPII")
    elif high_risk_is_masked(mask_body):
        ok("身份证/银行卡/凭据赋值 三类均掩码 + 置高风险")
    else:
        fail("仍有高风险类别只置标记不掩码 —— WORKING/SYSTEM 记忆仍会落明文")

    print("[B] 防御性不变量")
    write_body = body_of(text, "write")
    if write_body and re.search(r'hasHighRiskPII\s*&&\s*!\s*(?:maskResult\.)?hasMasked', write_body):
        ok("存在『高风险但未掩码 → 拒绝』的 fail-closed 分支")
    else:
        fail("缺少防御性不变量：高风险未掩码时应拒绝写入")

    print("[C] 落库内容")
    if re.search(r'newEntry\.content\s*=\s*maskResult\.maskedContent', text):
        ok("落库内容取 maskedContent")
    else:
        fail("落库内容未使用掩码后字段")

    print("[D] 长期记忆策略未被放宽")
    if re.search(r'hasHighRiskPII\s*\|\|\s*maskResult\.hasSensitiveKeyword', text) or \
       re.search(r'hasSensitiveKeyword\s*\|\|\s*maskResult\.hasHighRiskPII', text):
        ok("敏感关键词仍触发长期记忆拒绝（策略未被放宽）")
    else:
        fail("长期记忆拒绝条件未覆盖敏感关键词 —— 策略可能被放宽")

print()
if failures:
    print(f"RESULT: FAIL（{len(failures)} 项）")
    sys.exit(1)
print("RESULT: PASS —— WBS 1.9a 记忆 PII 脱敏静态验收通过（编译与运行时待 M1）")
sys.exit(0)
