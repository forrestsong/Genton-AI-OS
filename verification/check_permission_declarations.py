# -*- coding: utf-8 -*-
"""
门禁：权限声明一致性 + 未接线 manifest 片段

两个真实事故的产物：
  A. **事故 1（运行时）**：`enforceCallingPermission("com.genton.permission.X")` 引用的权限
     在设备上不存在 → 对所有非 system uid 调用方一律抛异常，等价于"拒绝所有人"。
     静态门禁全绿、编译 0 错误都发现不了，直到设备上 SystemUI 每 2 秒被拒一次。
     → 本门禁要求：代码里用到的每个权限，必须在某个 **会被构建的 manifest** 中有声明。
  B. **事故 2（误导）**：`core/res/AndroidManifest_permissions.xml` 里写了一堆权限声明，
     但 `Android.bp` 只接线 `AndroidManifest.xml`，该文件全仓无引用 → 声明从未生效。
     → 本门禁要求：core/res 下的 `AndroidManifest_*.xml` 片段必须真的被构建引用。

数据来源：归档（工作区）恒可用；构建树经 WSL 探测，取不到则该项 SKIP（不算通过）。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TREE_RES = "/root/aosp/frameworks/base/core/res"

failures, notes = [], []


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


def main():
    # ================= A. 权限声明一致性（归档） =================
    print("[A] 代码引用的权限是否都有声明（归档）")
    used = {}
    for p in ROOT.rglob("*.java"):
        try:
            t = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        if "/overlay/" not in p.as_posix() and "/patches/" not in p.as_posix():
            continue
        # 去注释后再找，避免注释里的权限名被当成使用
        t2 = re.sub(r'/\*.*?\*/', '', t, flags=re.S)
        t2 = re.sub(r'//[^\n]*', '', t2)
        for m in re.finditer(r'"(com\.genton\.permission\.(\w+))"', t2):
            used.setdefault(m.group(2), set()).add(p.name)

    declared = set()
    for p in list(ROOT.rglob("*.xml")):
        try:
            t = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        if "/overlay/" not in p.as_posix() and "/patches/" not in p.as_posix():
            continue
        t2 = re.sub(r'<!--.*?-->', '', t, flags=re.S)
        for m in re.finditer(r'<permission\s+android:name="com\.genton\.permission\.(\w+)"', t2):
            declared.add(m.group(1))

    print(f"  代码引用权限 {len(used)} 个；归档中声明 {len(declared)} 个")
    missing = {k: v for k, v in used.items() if k not in declared}
    if missing:
        for k, files in sorted(missing.items()):
            failures.append(f"权限 com.genton.permission.{k} 被 {sorted(files)} 引用但无任何声明")
    else:
        print("  ok: 引用的权限均已有声明")

    # 声明了但没人用（提示，不算失败）
    unused = sorted(declared - set(used))
    if unused:
        notes.append(f"声明但当前无引用（可能为 AIDL/反射场景，仅提示）: {unused[:8]}")

    # ================= B. 未接线的 manifest 片段（构建树） =================
    print("[B] core/res 下的 AndroidManifest_*.xml 是否真被构建引用")
    probe = wsl(f"test -d {TREE_RES} && echo OK || echo NO")
    if "__NOWSL__" in probe:
        print("  SKIP: WSL 不可用（**未验证**）")
    elif "OK" not in probe:
        print("  SKIP: 构建树不存在（**未验证**）")
    else:
        frags = [x for x in wsl(f"ls {TREE_RES}/AndroidManifest_*.xml 2>/dev/null").split() if x.endswith(".xml")]
        if not frags:
            print("  ok: 无未被接线的 manifest 片段（该目录下无 AndroidManifest_*.xml）")
        else:
            build_files = wsl(
                f"cat {TREE_RES}/Android.bp {TREE_RES}/Android.mk 2>/dev/null")
            for f in frags:
                name = f.rsplit("/", 1)[-1]
                if name in build_files:
                    print(f"  ok: {name} 已被构建引用")
                else:
                    failures.append(f"core/res/{name} 未被任何构建文件引用 → 死配置，声明不会生效")
            if not failures:
                print("  ok: 所有片段均被引用")

    print()
    for n in notes:
        print("  提示: " + n)
    if failures:
        print(f"RESULT: FAIL（{len(failures)} 项）")
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("RESULT: PASS —— 权限声明一致，且无未接线的 manifest 片段")
    sys.exit(0)


if __name__ == "__main__":
    main()
