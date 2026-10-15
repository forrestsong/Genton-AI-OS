# -*- coding: utf-8 -*-
"""
编译前符号门禁（WBS 3.3 前置）：构建树内的移植代码必须能自洽

教训来源：把归档的修复移植进构建树时，我插入的 `scheduleConfirmTimeout`
与树内**原有的同名方法**冲突（duplicate method）——这类问题只有编译时才暴露。
本门禁把它提前到编译之前。

检查三类（全部针对构建树 /root/aosp 的 genton 包）：
  A. **重复方法定义**：同一文件内相同「方法名 + 参数个数」出现多次
     （Java 不允许仅返回类型不同的重载，重复即编译失败）
  B. **依赖缺失**：移植代码引用的字段/方法在树内必须存在
  C. **WBS 关键签名**：关键安全不变量在树内成立（与 check_build_tree_parity 互补，
     这里查更细的符号）

WSL 不可用时 SKIP（退出 0），但必须显式声明"未验证"。
"""
import re
import subprocess
import sys
from pathlib import Path

TREE = "/root/aosp/frameworks/base/services/core/java/com/android/server/genton"
FILES = [
    "ActionExecutionService.java", "InternalActionRegistry.java", "PolicyEngineService.java",
    "ConfirmationHostService.java", "AgentAuditService.java", "TeeAnchorManager.java",
    "AgentScreenService.java", "AgentUiTreeService.java", "AuditService.java",
    "AgentMemoryService.java", "AgentSandboxManagerService.java", "AgentCallerAuth.java",
]

# 方法声明行：修饰符 + 返回类型 + 名字(参数)
METHOD_DECL = re.compile(
    r'^\s*(?:public|private|protected)\s+(?:static\s+|final\s+|synchronized\s+)*'
    r'[\w<>\[\],\.\s]+?\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [\w\.\s,]+)?\{', re.M)


def wsl(cmd, timeout=90):
    try:
        r = subprocess.run(["wsl", "-u", "root", "-e", "bash", "-c", cmd],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=timeout)
        return (r.stdout or "") + (r.stderr or "")
    except FileNotFoundError:
        return "__NOWSL__"
    except subprocess.TimeoutExpired:
        return "__TIMEOUT__"


def type_scopes(code):
    """返回 [(open_idx, close_idx, name)]，用于判定符号属于哪个类作用域。

    为什么不能只用大括号深度：两个**并列的嵌套类**深度相同，但字段同名完全合法
    （构建树 AgentAuditService 里 AuditEvent 与另一结构就有同名字段）。
    为什么必须包含**匿名内部类**：`new X.Stub() { ... }` 里的方法与外部类同名合法
    （构建树 ConfirmationHostService.onUserDecision 即如此）。
    本工具前两版分别用"深度"和"仅具名类"，各误报一次。
    """
    scopes = []
    # 具名类/接口/枚举
    for m in re.finditer(r'\b(?:class|interface|enum)\s+(\w+)[^{;]*\{', code):
        i = m.end() - 1
        depth, j = 0, i
        while j < len(code):
            if code[j] == "{":
                depth += 1
            elif code[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        scopes.append((i, j, m.group(1)))
    # 匿名内部类：new Xxx(...) { ... }
    for m in re.finditer(r'\bnew\s+[\w\.<>\[\],\s]+\([^;{]*\)\s*\{', code):
        i = m.end() - 1
        depth, j = 0, i
        while j < len(code):
            if code[j] == "{":
                depth += 1
            elif code[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        line = code[:m.start()].count("\n") + 1
        scopes.append((i, j, f"<anon@{line}>"))
    return scopes


def scope_of(offset, scopes):
    """最内层类名；不在任何类内返回 <top>。"""
    best = None
    for o, c, name in scopes:
        if o < offset < c and (best is None or o > best[0]):
            best = (o, name)
    return best[1] if best else "<top>"


def main():
    failures = []
    probe = wsl(f"test -d {TREE} && echo OK || echo NO")
    if "__NOWSL__" in probe:
        print("SKIP：WSL 不可用，无法验证构建树（**未验证**，不是通过）")
        sys.exit(0)
    if "OK" not in probe:
        print(f"SKIP：构建树目录不存在（{TREE}）——**未验证**")
        sys.exit(0)

    print("[A] 重复方法定义")
    for f in FILES:
        out = wsl(f"cat {TREE}/{f} 2>/dev/null")
        if out.strip() == "" or out.startswith("__"):
            failures.append(f"{f}: 无法读取")
            continue
        # 去注释，避免注释中的签名被误计
        code = re.sub(r'/\*.*?\*/', lambda m: "\n" * m.group(0).count("\n"), out, flags=re.S)
        code = re.sub(r'//[^\n]*', '', code)
        scopes = type_scopes(code)
        seen = {}
        for m in METHOD_DECL.finditer(code):
            name, params = m.group(1), m.group(2)
            # 参数类型（含数组/泛型），忽略参数名——避免把正常重载误判为重复
            types = []
            for p in [x for x in re.split(r',(?![^<]*>)', params) if x.strip()]:
                p = re.sub(r'@\w+(\([^)]*\))?', '', p).strip()
                tm = re.search(r'([\w<>\[\],\.]+)\s+\w+\s*$', p)
                types.append(tm.group(1) if tm else p)
            # 作用域：最内层类名（不是大括号深度——并列嵌套类深度相同）
            key = (name, tuple(types), scope_of(m.start(), scopes))
            line = code[:m.start()].count("\n") + 1
            seen.setdefault(key, []).append(line)
        dups = {k: v for k, v in seen.items() if len(v) > 1}
        if dups:
            for (name, types, depth), lines in dups.items():
                failures.append(f"{f}: 方法 {name}({','.join(types)}) 同作用域(深度{depth}) "
                                f"重复定义于行 {lines}")
        else:
            print(f"  ok: {f}（{len(seen)} 个签名，无同作用域重复）")

        # 字段重复声明：编译器报 "duplicate declaration of field"。
        # 教训：首次真实编译即因此失败（AgentAuditService.mTeeAnchorManager 被移植重复声明），
        # 而本门禁第一版只查了方法，漏了字段。
        fields = {}
        for m in re.finditer(
                r'^\s*(?:private|protected|public)\s+(?:static\s+|final\s+|volatile\s+|transient\s+)*'
                r'[\w<>\[\],\.]+\s+(\w+)\s*(?:=[^=]|;)', code, re.M):
            fname = m.group(1)
            fields.setdefault((fname, scope_of(m.start(), scopes)), []).append(
                code[:m.start()].count("\n") + 1)
        fdups = {k: v for k, v in fields.items() if len(v) > 1}
        if fdups:
            for (fname, depth), lines in fdups.items():
                failures.append(f"{f}: 字段 {fname} 同作用域(深度{depth}) 重复声明于行 {lines}")
        elif not dups:
            print(f"  ok: {f}（字段 {len(fields)} 个，无重复声明）")

    # 孤儿注解：`@Override` 后跟注释/空行而非方法签名。
    # 教训：按锚点在方法前插入代码时，若该方法的 @Override 恰好在上方，注解会被"留下"
    # 给新方法 —— 首次真实编译即在 PolicyEngineService 命中此坑。
    print("[A2] 孤儿注解（@Override 后非方法声明）")
    orphan = re.compile(r'@Override\s*\n\s*(?:/\*\*|//|\*)')
    total_orphan = 0
    for f in FILES:
        out = wsl(f"cat {TREE}/{f} 2>/dev/null")
        if out.startswith("__") or not out.strip():
            continue
        for m in orphan.finditer(out):
            line = out[:m.start()].count("\n") + 1
            failures.append(f"{f}:{line} @Override 后不是方法声明（注解错位，编译报"
                            f"『does not override』）")
            total_orphan += 1
    if total_orphan == 0:
        print("  ok: 无孤儿 @Override")

    print("[B] 移植代码的依赖符号")
    DEPS = [
        ("ActionExecutionService.java", "mAuditInstrumentation"),
        ("ActionExecutionService.java", "mCancelledCount"),
        ("ActionExecutionService.java", "notifyCancelled"),
        ("ActionExecutionService.java", "auditActionExecution"),
        ("ActionExecutionService.java", "CONFIRM_TIMEOUT_MS"),
        ("ActionExecutionService.java", "mTimeoutScheduler"),
        ("ActionExecutionService.java", "mPolicyEngine"),
        ("AgentAuditService.java", "mLastAnchor"),
        ("AgentAuditService.java", "mSeqGenerator"),
        ("ConfirmationHostService.java", "mPolicyEngine"),
        ("PolicyEngineService.java", "mPendingConfirmations"),
        ("PolicyEngineService.java", "CONFIRM_ID_TTL_NANOS"),
        ("PolicyEngineService.java", "verifyConfirmId"),
        ("AgentScreenService.java", "mContext"),
        ("AgentUiTreeService.java", "mContext"),
        ("AgentMemoryService.java", "mContext"),
        ("AgentSandboxManagerService.java", "mContext"),
    ]
    for f, sym in DEPS:
        out = wsl(f"grep -c '{sym}' {TREE}/{f} 2>/dev/null || echo 0")
        try:
            n = int(out.strip().splitlines()[-1])
        except Exception:
            n = -1
        if n >= 1:
            print(f"  ok: {f} 含 {sym}（{n} 处）")
        else:
            failures.append(f"{f} 缺少依赖符号 {sym}（编译必失败）")

    print("[C] 关键安全签名")
    # 注意：这些模式会直接拼进 shell 的 grep（BRE）。
    # BRE 中 `\(` 是"分组"而不是字面括号——若写成 getFD\(\)\.sync\(\) 会永远匹配不到
    # （本工具第一版即因此把已落盘的 fsync 误报为缺失）。故此处一律用未转义写法。
    KEY = [
        ("ActionExecutionService.java", "private void scheduleConfirmTimeout", 1, "确认超时定义应恰好 1 处"),
        ("ActionExecutionService.java", "mTimeoutScheduler.schedule", 1, "超时应走调度器（非 Thread.sleep 占池）"),
        ("ActionExecutionService.java", "Thread.sleep(CONFIRM_TIMEOUT_MS)", 0, "不得再用 sleep 占住线程池"),
        ("AgentAuditService.java", "getFD().sync()", 1, "审计落盘须 fsync"),
        ("TeeAnchorManager.java", "SHA256withECDSA", 2, "锚点须真实签名 + 验签"),
        ("AgentCallerAuth.java", "enforceCallingPermission", 1, "鉴权助手须真落地"),
    ]
    for f, pat, want, desc in KEY:
        out = wsl(f"grep -c '{pat}' {TREE}/{f} 2>/dev/null || echo 0")
        try:
            n = int(out.strip().splitlines()[-1])
        except Exception:
            n = -1
        # grep -c 计"行数"，签名/验签在同一行可能出现多次；此处按 >=want 判定
        okk = (n == want) if want == 0 else (n >= want)
        if okk:
            print(f"  ok: {f} {desc}（{n}）")
        else:
            failures.append(f"{f}: {desc} —— 实际 {n}，期望"
                            + (f" {want}" if want else " 0"))

    print()
    if failures:
        print(f"RESULT: FAIL（{len(failures)} 项）—— 编译前必须先修")
        for x in failures:
            print("  - " + x)
        sys.exit(1)
    print("RESULT: PASS —— 构建树内移植代码符号自洽（无重复定义、依赖齐备）")
    sys.exit(0)


if __name__ == "__main__":
    main()
