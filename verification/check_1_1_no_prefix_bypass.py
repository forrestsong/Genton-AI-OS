# -*- coding: utf-8 -*-
"""
WBS 1.1 / 1.2a 静态验收检查（证据即脚本）

断言：
  A. 内部动作身份不得由调用方可控的 actionId 前缀决定
     （isInternalAction / supportsAction 函数体内不得出现 startsWith("sys."/"cmd.")）
  B. ActionExecutionService 必须：白名单判定 + 平台签名调用方校验 + 内部动作强制审计
  C. 必须有 scheduleConfirmTimeout 的真实实现（此前未定义 → 编译不过）
  D. validateTaintSources 必须 fail-closed（不得 return true 放行）
  E. 不得再出现"内部动作跳过审计"的分支；不得出现未声明的 mXxx 字段

失败时打印 文件:行号 证据并以非零退出。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SVC = ROOT / "overlay" / "frameworks" / "base" / "services" / "core" / "java" / "com" / "android" / "server" / "genton"
KOTLIN_SVC = ROOT / "frameworks" / "base" / "services" / "core" / "java" / "com" / "android" / "server" / "genton"

failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL: " + msg)


def ok(msg):
    print("  ok: " + msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def find_function_body(text, names):
    """按大括号配对提取指定函数名的函数体，返回 (name, start_line, body)。"""
    out = []
    for name in names:
        for m in re.finditer(r'\b' + re.escape(name) + r'\s*\([^)]*\)\s*(?:throws\s+[\w\.\s,]+)?\{', text):
            i = m.end() - 1  # '{'
            depth = 0
            j = i
            while j < len(text):
                if text[j] == '{':
                    depth += 1
                elif text[j] == '}':
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            body = text[i:j + 1]
            line = text[:m.start()].count('\n') + 1
            out.append((name, line, body))
    return out


# 前缀判定模式：startsWith("sys.") / startsWith("cmd.") / startsWith(PREFIX_SYS) 等
prefix_pat = re.compile(r'startsWith\s*\(\s*(?:"(?:sys|cmd)\.|PREFIX_(?:SYS|CMD))')

# ---------- 0. 检测器自检（teeth）----------
# 一个从未报出过问题的检测器不可信。这里用仓库中**留存的原始脆弱实现**
# （reports/A3.6_完成报告.md 内嵌的代码块）作为负样本，证明检测器确实能识别前缀判定。
print("[0] 检测器自检（负样本：报告中留存的原始前缀判定实现）")
FIXTURE = ROOT / "reports" / "A3.6_完成报告.md"
if not FIXTURE.exists():
    fail("缺少负样本 fixture，无法证明检测器有效")
else:
    fixture_bodies = [(n, l, b) for (n, l, b) in find_function_body(read(FIXTURE), ["isInternalAction"])
                      if prefix_pat.search(b)]
    if fixture_bodies:
        ok(f"检测器在历史脆弱代码上正确报出前缀判定（{FIXTURE.name}:{fixture_bodies[0][1]}）")
    else:
        fail("检测器未能识别已知脆弱实现 —— 检测器失效，其后所有 PASS 均不可信")

# ---------- A. 前缀判定必须消失 ----------
print("[A] 内部动作身份不得按前缀判定")
targets = []
for base in (SVC, KOTLIN_SVC):
    if base.exists():
        targets += [p for p in base.rglob("*") if p.suffix in (".java", ".kt")]
scanned = 0
for p in targets:
    text = read(p)
    for name, line, body in find_function_body(text, ["isInternalAction", "supportsAction"]):
        scanned += 1
        if prefix_pat.search(body):
            fail(f"{p.relative_to(ROOT)}:{line} {name}() 仍按 actionId 前缀判定内部动作")
if scanned == 0:
    fail("[A] 未找到任何 isInternalAction/supportsAction 实现，检查范围可能失效")
else:
    ok(f"扫描 {scanned} 个身份判定函数，未发现前缀判定")

# ---------- B/C/D/E. ActionExecutionService 不变量 ----------
AES = SVC / "ActionExecutionService.java"
if not AES.exists():
    fail(f"缺少 {AES.relative_to(ROOT)}")
else:
    text = read(AES)
    lines = text.split("\n")

    def has(pattern, desc):
        if re.search(pattern, text):
            ok(desc)
        else:
            fail(desc + " —— 未找到：" + pattern)

    print("[B] 白名单 + 调用方信任 + 强制审计")
    has(r'mInternalActionRegistry\.get\(\s*request\.actionId\s*\)',
        "submitAction 使用 InternalActionRegistry 白名单判定")
    has(r'private\s+boolean\s+isTrustedCallerForInternalAction\s*\(', "存在 isTrustedCallerForInternalAction()")
    has(r'checkSignatures\s*\(\s*uid\s*,\s*Process\.SYSTEM_UID\s*\)', "内部动作要求平台签名调用方")
    has(r'auditInternalAction\s*\(', "内部动作强制审计 auditInternalAction()")
    has(r'mInternalActionRegistry\.get\(\s*actionId\s*\)\s*!=\s*null',
        "isInternalAction() 改为白名单判定")

    print("[C] 确认超时必须有真实实现")
    has(r'private\s+void\s+scheduleConfirmTimeout\s*\(\s*ExecutionContext\s+context\s*\)',
        "scheduleConfirmTimeout() 已定义")

    print("[D] taint 校验必须 fail-closed")
    for name, line, body in find_function_body(text, ["validateTaintSources"]):
        if re.search(r'return\s+true\s*;\s*(?://.*)?$', body, re.M) and "argTaints == null" not in body:
            fail(f"{AES.relative_to(ROOT)}:{line} validateTaintSources() 仍无条件放行")
        elif re.search(r'return\s+true\s*;\s*(?://.*)?$', body, re.M):
            ok("validateTaintSources() 仅在未申报 taint 时放行，其余路径 fail-closed")
        else:
            fail(f"{AES.relative_to(ROOT)}:{line} validateTaintSources() 未找到任何放行/拒绝逻辑")
    if "临时放行" in text:
        fail("仍存在『临时放行』注释（应已随 1.2a 移除）")
    else:
        ok("不存在『临时放行』残留")

    print("[E] 不得跳过审计 / 不得有未声明字段")
    for i, ln in enumerate(lines, 1):
        if re.search(r'!\s*isInternalAction\s*\(', ln):
            fail(f"{AES.relative_to(ROOT)}:{i} 仍存在『内部动作跳过审计』分支")
    if not any(re.search(r'!\s*isInternalAction\s*\(', ln) for ln in lines):
        ok("不存在『内部动作跳过审计』分支")
    if "mFailedCount" in text:
        fail(f"{AES.relative_to(ROOT)} 仍引用未声明的 mFailedCount")
    else:
        ok("mFailedCount 已修正为已声明字段")

    used = set(re.findall(r'\b(m[A-Z]\w*)\b', text))
    for name in sorted(used):
        # 声明形如： [修饰符] 类型 name;  或  name = ...;（字段初始化）
        decl = re.compile(
            r'^[ \t]*(?:(?:private|protected|public|static|final|volatile|transient)\s+)*'
            r'[\w\.<>\[\],\s]*\b' + re.escape(name) + r'\s*(?:=[^=]|;)', re.M)
        inline = re.search(r'\b' + re.escape(name) + r'\s*=\s*(?:new\s|Executors\.|null|0|"|\d)', text)
        if not decl.search(text):
            # 允许 lambda/局部变量式初始化（如 ExecutorService x = ...）由 inline 兜底
            if not inline:
                fail(f"{AES.relative_to(ROOT)} 使用 {name} 但文件中找不到声明")
    ok(f"字段声明检查覆盖 {len(used)} 个 m* 标识符")

# ---------- F. 括号配对（语法粗筛）----------
print("[F] 结构粗筛：括号配对")
for p in [AES, SVC / "InternalActionRegistry.java", SVC / "executors" / "InternalActionExecutor.java"]:
    if not p.exists():
        continue
    text = read(p)
    for open_c, close_c in (("{", "}"), ("(", ")"), ("[", "]")):
        if text.count(open_c) != text.count(close_c):
            fail(f"{p.relative_to(ROOT)} 括号不配对：{open_c}={text.count(open_c)} vs {close_c}={text.count(close_c)}")
    ok(f"{p.name} 括号配对")

print()
if failures:
    print(f"RESULT: FAIL（{len(failures)} 项）")
    sys.exit(1)
print("RESULT: PASS —— WBS 1.1 / 1.2a 静态验收通过（编译与运行时验证待 M1 / 3.3）")
sys.exit(0)
