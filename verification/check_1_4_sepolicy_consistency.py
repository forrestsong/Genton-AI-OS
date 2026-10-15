# -*- coding: utf-8 -*-
"""
WBS 1.4 验收工具：SELinux 策略与实现的一致性 + 语法粗筛

检查三类问题（都能定位到 文件:行号）：
  A. 注册的服务名 是否有 service_contexts 条目
     （缺失 → 回落 default_android_service，最小权限失效）
  B. service_contexts 引用的 *_service 类型 是否在 .te 中声明
     （缺失 → 策略编译失败）
  C. .te 语法粗筛：语句必须以 ';' 结尾；括号配对
     （AOSP sepolicy 编译器对缺分号是硬失败）

用法：
  python verification/check_1_4_sepolicy_consistency.py
  python verification/check_1_4_sepolicy_consistency.py --report
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

REGISTRY = ROOT / "overlay/frameworks/base/services/core/java/com/android/server/genton/GentonServiceRegistry.java"
SERVICE_CONTEXTS = [
    ROOT / "overlay/system/sepolicy/private/service_contexts",
    ROOT / "sepolicy/service_contexts",
]
TE_DIRS = [
    ROOT / "overlay/system/sepolicy/private",
    ROOT / "sepolicy",
]

# 只有真正的策略语句必须以 ';' 结尾。
# 注意：binder_call()/binder_use() 等是 AOSP 的 m4 宏，惯例**不写分号**，
# 若把它们判为"缺分号"会产生大量误报（本工具初版即如此）。
STMT_STARTERS = ("allow ", "neverallow ", "dontaudit ", "auditallow ", "type ", "typeattribute ",
                 "type_transition ", "allowxperm ", "typealias ")


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r'(?m)^\s*#.*$', '', text)


def registered_services():
    if not REGISTRY.exists():
        return set()
    text = read(REGISTRY)
    return set(re.findall(r'reg\(\s*"([^"]+)"', text))


def service_contexts_entries():
    entries = {}
    for p in SERVICE_CONTEXTS:
        if not p.exists():
            continue
        for i, line in enumerate(read(p).splitlines(), 1):
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            parts = s.split()
            if len(parts) >= 2:
                entries.setdefault(parts[0], (p, i, parts[1]))
    return entries


def declared_service_types():
    decl = {}
    for d in TE_DIRS:
        if not d.exists():
            continue
        for p in sorted(d.rglob("*.te")):
            for i, line in enumerate(strip_comments(read(p)).splitlines(), 1):
                m = re.match(r'\s*type\s+(\w+)\s*,\s*([\w\s,]+);', line)
                if m:
                    decl.setdefault(m.group(1), (p, i))
    return decl


def syntax_issues():
    """只报告可证明的语法问题：策略语句缺少结尾分号。

    刻意不做括号/花括号配对检查：.te 中的 `{ ... }` 权限集合与
    `userdebug_or_eng(\\` ... ')` 这类 m4 多行宏会让逐行启发式大量误报。
    宁可漏报，也不要制造噪声——噪声会让门禁失去信任。
    """
    issues = []
    for d in TE_DIRS:
        if not d.exists():
            continue
        for p in sorted(d.rglob("*.te")):
            text = strip_comments(read(p))
            lines = text.splitlines()
            i = 0
            while i < len(lines):
                s = lines[i].strip()
                if s.startswith(STMT_STARTERS):
                    start = i
                    buf = s
                    # 策略语句可以跨行（如多行权限集合 { a b c }），累积到分号为止
                    while ";" not in buf.split("#", 1)[0] and i + 1 < len(lines):
                        i += 1
                        buf += " " + lines[i].strip()
                    code = buf.split("#", 1)[0].strip()
                    if ";" not in code:
                        issues.append((p, start + 1, "策略语句缺少结尾分号", s[:80]))
                i += 1
    return issues


def main():
    args = set(sys.argv[1:])
    report = "--report" in args

    services = registered_services()
    contexts = service_contexts_entries()
    types_decl = declared_service_types()
    syntax = syntax_issues()

    missing_ctx = sorted(s for s in services if s not in contexts)
    used_types = {(v[2], v[0], v[1]) for v in contexts.values() if v[2].startswith("genton")}
    missing_type = sorted((t, p, i) for (t, p, i) in used_types if t not in types_decl)

    # 基线：允许存在的已知缺口（只允许减少）。新增缺口即 FAIL。
    base_file = ROOT / "verification" / "sepolicy_baseline.json"
    baseline = {}
    if base_file.exists():
        import json
        baseline = json.loads(base_file.read_text(encoding="utf-8")).get("known_gaps", {})
    regressions = [s for s in missing_ctx if s not in baseline]
    stale = [s for s in baseline if s not in missing_ctx]

    print(f"注册服务: {len(services)}    service_contexts 条目: {len(contexts)}"
          f"    已声明 genton_* 类型: {len([k for k in types_decl if k.startswith('genton')])}")

    if report:
        print("\n--- 已注册服务与其 SELinux 上下文 ---")
        for s in sorted(services):
            if s in contexts:
                p, i, t = contexts[s]
                print(f"  OK   {s:<46} -> {t}  ({p.relative_to(ROOT)}:{i})")
            else:
                print(f"  MISS {s:<46} -> (无 service_contexts 条目)")
        print("\n--- genton_* 类型声明情况 ---")
        for t, p, i in sorted(used_types):
            mark = "OK  " if t in types_decl else "MISS"
            where = f"{types_decl[t][0].relative_to(ROOT)}:{types_decl[t][1]}" if t in types_decl else "(未声明)"
            print(f"  {mark} {t:<36} {where}")

    print("\n--- A. 缺失 service_contexts 的服务 ---")
    if missing_ctx:
        for s in missing_ctx:
            tag = "BASELINE" if s in baseline else "FAIL"
            print(f"  {tag:<8} {s}" + (f"  （{baseline[s]}）" if s in baseline else ""))
    else:
        print("  PASS 无缺失")

    print("\n--- B. service_contexts 引用但未声明的类型 ---")
    if missing_type:
        for t, p, i in missing_type:
            print(f"  FAIL {t} （被 {p.relative_to(ROOT)}:{i} 引用，但无 type 声明）")
    else:
        print("  PASS 无缺失")

    print("\n--- C. .te 语法粗筛 ---")
    if syntax:
        for p, i, why, line in syntax:
            print(f"  FAIL {p.relative_to(ROOT)}:{i} {why}  | {line[:90]}")
    else:
        print("  PASS 未发现策略语句缺分号")

    if regressions:
        print("\nFAIL: 出现新的缺失 service_contexts 服务（安全回归）")
        for s in regressions:
            print("  - " + s)
    if stale:
        print("\nFAIL: 基线中的缺口已修复或已不存在，基线必须收紧")
        for s in stale:
            print("  - " + s)

    failed = len(regressions) + len(missing_type) + len(syntax) + len(stale)
    print()
    if failed:
        print(f"RESULT: FAIL —— 共 {failed} 项（新增缺失={len(regressions)} 类型未声明={len(missing_type)} "
              f"语法={len(syntax)} 基线过期={len(stale)}）")
        sys.exit(1)
    print(f"RESULT: PASS —— 服务上下文一致（基线内剩余 {len(baseline)} 项已知缺口）")
    sys.exit(0)


if __name__ == "__main__":
    main()
