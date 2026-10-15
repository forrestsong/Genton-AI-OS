# -*- coding: utf-8 -*-
"""
WBS 1.3 验收工具：Binder 接口鉴权清单（authorization inventory）

用途：把"未鉴权 Binder 方法数 = 0"这条验收标准变成可测量、可棘轮的指标。

做法：
  1. 扫描全部 AIDL 接口，抽出方法清单；
  2. 在服务实现树中定位 `IXxx.Stub` 的实现类与对应方法体；
  3. 判定每个方法体是否含调用方鉴权原语（enforce*/check*Permission、checkSignatures，
     或调用了同文件内含鉴权原语的私有助手）；
  4. 与基线 verification/authz_baseline.json 比对：
     - 出现**新的**未鉴权方法 → FAIL（回归）
     - 基线中的方法已加鉴权或已消失 → FAIL（基线必须同步收紧）

用法：
  python verification/check_1_3_binder_authz.py                 # 验收（CI 用）
  python verification/check_1_3_binder_authz.py --write-baseline # 重新生成基线（收紧后）
  python verification/check_1_3_binder_authz.py --report        # 打印完整清单
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "verification" / "authz_baseline.json"

AIDL_DIRS = [
    ROOT / "overlay" / "frameworks" / "base" / "core" / "java" / "com" / "genton",
    ROOT / "aidl" / "com" / "genton",
]
IMPL_DIRS = [
    ROOT / "overlay" / "frameworks" / "base" / "services" / "core" / "java" / "com" / "android" / "server" / "genton",
    ROOT / "frameworks" / "base" / "services" / "core" / "java" / "com" / "android" / "server" / "genton",
    ROOT / "overlay" / "frameworks" / "base" / "packages" / "SystemUI" / "src" / "com" / "android" / "systemui" / "genton",
]

# 强鉴权原语：任何一个出现在方法体内（或其直接调用的同文件助手内）即视为已鉴权
AUTHZ_STRONG = re.compile(
    r'\b(?:enforceCalling(?:OrSelf)?Permission|checkCalling(?:OrSelf)?Permission'
    r'|enforceCalling(?:OrSelf)?Uid|checkCalling(?:OrSelf)?Uid'
    r'|checkSignatures|enforceCallingOrSelfUriPermission'
    r'|MANAGE_AI_NATIVE_AGENTS|BIND_AGENT_UI|INTERNAL_SYSTEM_WINDOW)\b'
    # 项目内统一鉴权助手（其内部必须仍含 enforceCallingPermission，见下方不变量检查）
    r'|AgentCallerAuth\.(?:enforceTransaction|holdsPermission)')
# 弱信号：只取调用方身份但未做判定（审计上常见"身份映射冒充鉴权"）
AUTHZ_WEAK = re.compile(r'\bgetCallingUid\s*\(\s*\)|\bgetCallingPid\s*\(\s*\)|\bgetCallingUserHandle\s*\(\s*\)')


def strip_comments(text: str) -> str:
    """剥离注释，但**保留行号**（块注释替换为等量空行）。

    必须做这一步：AgentScreenService.checkPermission() 里的
    `mContext.enforceCallingPermission(...)` 是被注释掉的，若把注释计入，
    清单会把"注释里的鉴权"当成真鉴权，覆盖率虚高。
    """
    text = re.sub(r'/\*.*?\*/', lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    text = re.sub(r'//[^\n]*', '', text)
    return text


def aidl_interfaces():
    """返回 {interface_name: {method: path}}"""
    ifaces = {}
    for d in AIDL_DIRS:
        if not d.exists():
            continue
        for p in sorted(d.rglob("*.aidl")):
            raw = p.read_text(encoding="utf-8", errors="replace")
            text = strip_comments(raw)
            m = re.search(r'(?:oneway\s+)?interface\s+(\w+)[^{]*\{(.*)\}', text, re.S)
            if not m:
                continue
            name, body = m.group(1), m.group(2)
            methods = {}
            for stmt in body.split(";"):
                stmt = stmt.strip()
                if not stmt or "(" not in stmt:
                    continue
                if stmt.startswith(("const", "parcelable", "enum", "union", "import")):
                    continue
                mm = re.search(r'(\w+)\s*\(', stmt)
                if mm:
                    methods.setdefault(mm.group(1), p)
            if methods:
                ifaces.setdefault(name, {}).update(methods)
    return ifaces


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def class_bodies_for_stub(iface_names):
    """找出实现了任一接口 Stub 的类：(iface, impl_file, class_start, class_body)"""
    found = []
    for d in IMPL_DIRS:
        if not d.exists():
            continue
        for p in sorted(list(d.rglob("*.java")) + list(d.rglob("*.kt"))):
            text = strip_comments(read(p))   # 注释中的鉴权调用不计入
            for iface in iface_names:
                for m in re.finditer(re.escape(iface) + r'\.Stub', text):
                    # 向上找最近的 class 声明
                    head = text.rfind("class ", 0, m.start())
                    if head < 0 or m.start() - head > 400:
                        continue
                    brace = text.find("{", m.end())
                    if brace < 0:
                        continue
                    depth, j = 0, brace
                    while j < len(text):
                        if text[j] == "{":
                            depth += 1
                        elif text[j] == "}":
                            depth -= 1
                            if depth == 0:
                                break
                        j += 1
                    line = text[:head].count("\n") + 1
                    found.append((iface, p, line, text[head:j + 1]))
    return found


def method_body(class_body, method):
    """在类体内定位方法体；Java 与 Kotlin 各试一次。"""
    for pat in (r'\b' + re.escape(method) + r'\s*\([^)]*\)\s*(?:throws[\w\.\s,]+)?\{',
                r'override\s+fun\s+' + re.escape(method) + r'\s*\([^)]*\)[^{]*\{'):
        for m in re.finditer(pat, class_body):
            i = m.end() - 1
            depth, j = 0, i
            while j < len(class_body):
                if class_body[j] == "{":
                    depth += 1
                elif class_body[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            yield class_body[i:j + 1]
            return  # 同名首个匹配即可


def helper_is_guarded(class_body, body):
    """方法体若调用同文件内的私有助手，而助手内含强鉴权原语，则视为已鉴权。"""
    calls = set(re.findall(r'\b(\w+)\s*\(', body))
    for helper in calls:
        if helper in ("if", "for", "while", "switch", "catch", "return", "String", "Slog"):
            continue
        for hm in re.finditer(r'\b' + re.escape(helper) + r'\s*\([^)]*\)[^{;]*\{', class_body):
            i = hm.end() - 1
            depth, j = 0, i
            while j < len(class_body):
                if class_body[j] == "{":
                    depth += 1
                elif class_body[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            if AUTHZ_STRONG.search(class_body[i:j + 1]):
                return helper
    return None


def main():
    args = set(sys.argv[1:])

    # ---------- 0. 检测器自检（teeth）----------
    # 真实踩过的坑：AgentScreenService.checkPermission() 里的
    # `mContext.enforceCallingPermission(...)` 是**被注释掉的**。若把注释计入，
    # 覆盖率会从 19.1% 虚报成 21.7%（本工具初版即如此）。此处把该陷阱固化为自检。
    print("[0] 检测器自检")
    off = 'private boolean checkPermission() {\n    // mContext.enforceCallingPermission("x", null);\n    return true;\n}'
    on = 'private boolean checkPermission() {\n    mContext.enforceCallingPermission("x", null);\n    return true;\n}'
    if AUTHZ_STRONG.search(strip_comments(off)):
        print("FAIL: 注释掉的 enforce 被误判为已鉴权 —— 检测器失效")
        sys.exit(1)
    print("  ok: 注释掉的 enforce 不计入（真阴性）")
    if AUTHZ_STRONG.search(strip_comments(on)):
        print("  ok: 真实 enforce 被识别（真阳性）")
    else:
        print("FAIL: 真实 enforce 未被识别 —— 检测器失效")
        sys.exit(1)
    print()

    ifaces = aidl_interfaces()
    impls = class_bodies_for_stub(list(ifaces.keys()))

    rows = []
    for iface, path, line, body in impls:
        # 类级鉴权：onTransact 覆盖该接口全部事务（含未来新增方法）
        class_level = None
        for ot in method_body(body, "onTransact"):
            m = AUTHZ_STRONG.search(ot)
            if m:
                class_level = f"onTransact 内 {m.group(0)}"
                break
            if helper_is_guarded(body, ot):
                class_level = "onTransact 经助手鉴权"
                break
        for method in ifaces.get(iface, {}):
            bodies = list(method_body(body, method))
            if not bodies:
                continue  # 未实现该方法
            mbody = bodies[0]
            m = AUTHZ_STRONG.search(mbody)
            if m:
                verdict, detail = "enforced", f"方法体内 {m.group(0)}"
            elif class_level:
                verdict, detail = "enforced", f"类级 {class_level}"
            else:
                helper = helper_is_guarded(body, mbody)
                if helper:
                    verdict, detail = "enforced", f"经助手 {helper}()"
                elif AUTHZ_WEAK.search(mbody):
                    verdict, detail = "weak", "仅取调用方身份，无判定"
                else:
                    verdict, detail = "none", "无任何调用方校验"
            rows.append({
                "interface": iface, "method": method, "impl": str(path.relative_to(ROOT)).replace("\\", "/"),
                "line": line, "verdict": verdict, "detail": detail,
            })

    by = {"enforced": 0, "weak": 0, "none": 0}
    for r in rows:
        by[r["verdict"]] += 1
    total = len(rows)
    unguarded = {(r["interface"], r["method"]): r for r in rows if r["verdict"] != "enforced"}

    if args & {"--report", "--write-baseline"}:
        print(f"{'接口':<42} {'方法':<34} {'判定':<9} 位置")
        for r in sorted(rows, key=lambda x: (x["verdict"], x["interface"], x["method"])):
            print(f"{r['interface']:<42} {r['method']:<34} {r['verdict']:<9} {r['impl']}:{r['line']} ({r['detail']})")
        print()

    # 不变量：鉴权助手本身不得被掏空（间接层必须仍含真实判定）
    helper_file = ROOT / "overlay/frameworks/base/services/core/java/com/android/server/genton/AgentCallerAuth.java"
    helper_users = [r for r in rows if "AgentCallerAuth" in r["detail"]] if rows else []
    if helper_file.exists():
        helper_text = strip_comments(read(helper_file))
        if "enforceCallingPermission" not in helper_text:
            print("FAIL: AgentCallerAuth 内已无 enforceCallingPermission —— 鉴权被掏空（所有"
                  " 依赖该助手的判定均失效）")
            sys.exit(1)
        used = sum(1 for r in rows if "AgentCallerAuth" in r["detail"])
        print(f"不变量检查：AgentCallerAuth 内部仍含 enforceCallingPermission（当前 {used} 个方法依赖它）")
        print()

    print(f"已实现且暴露的 Binder 方法总数: {total}")
    print(f"  已鉴权(enforced): {by['enforced']}")
    print(f"  弱校验(weak)    : {by['weak']}")
    print(f"  完全未鉴权(none): {by['none']}")
    if total:
        print(f"  鉴权覆盖率: {by['enforced'] / total * 100:.1f}%")

    if "--write-baseline" in args:
        data = {f"{k[0]}#{k[1]}": f"{v['impl']}:{v['line']} ({v['detail']})"
                for k, v in sorted(unguarded.items())}
        BASELINE.write_text(json.dumps({
            "_comment": "WBS 1.3 鉴权基线：下列方法当前未鉴权，只允许减少，不允许新增。收紧后重新生成。",
            "unauthorized": data,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n已写入基线：{BASELINE.relative_to(ROOT)}（{len(data)} 项）")
        sys.exit(0)

    if not BASELINE.exists():
        print(f"\nRESULT: FAIL —— 缺少基线文件 {BASELINE.relative_to(ROOT)}，"
              f"请先运行 --write-baseline 建立初始基线")
        sys.exit(1)

    base = json.loads(BASELINE.read_text(encoding="utf-8"))["unauthorized"]
    regressions = [f"{k}#{m} → {v['impl']}:{v['line']}" for k, v in unguarded.items() if f"{k}#{v['method']}" not in base
                   and k not in base]
    regressions = []
    for (iface, method), v in unguarded.items():
        key = f"{iface}#{method}"
        if key not in base:
            regressions.append(f"{key} → {v['impl']}:{v['line']} ({v['detail']})")
    stale = [k for k in base if k.split("#")[0] not in {"", } and k not in
             {f"{r['interface']}#{r['method']}" for r in rows if r["verdict"] != "enforced"}]

    if regressions:
        print("\nFAIL: 出现新的未鉴权 Binder 方法（安全回归）")
        for r in regressions:
            print("  - " + r)
    if stale:
        print("\nFAIL: 基线中以下条目已加鉴权或已不存在，基线必须收紧")
        for s in stale:
            print("  - " + s)

    if regressions or stale:
        print(f"\nRESULT: FAIL（新增未鉴权 {len(regressions)} 项 / 基线过期 {len(stale)} 项）")
        sys.exit(1)

    remaining = len(base)
    print(f"\nRESULT: PASS —— 无新增未鉴权方法；基线内剩余 {remaining} 项待收敛"
          f"（占已实现方法的 {remaining / total * 100:.1f}%）")
    sys.exit(0)


if __name__ == "__main__":
    main()
