# -*- coding: utf-8 -*-
"""
WBS 3.1 验收：补丁栈必须"要么真的铺上去，要么失败"

本检查器与其它检查器不同：它**真的执行** patches/apply_patches.py --dry-run，
并要求它以 0 退出；同时对脚本本身做若干反模式断言（防止退回旧的"恒报成功"）。

断言：
  A. 部署器 dry-run 必须成功，且输出中"错误：0"；每个补丁目录都必须贡献文件。
  B. 不得存在静默跳过路径：apply-patches.sh 中没有 `return 0` 式静默；
     部署器遇到无法推导的载荷必须非零退出。
  C. 默认必须是 dry-run；`--apply` 必须要求显式 `--target`（拒绝在未知目标上写入）。
  D. 冲突副本必须**跨补丁目录全局**判定遮蔽（防同一目标被多份配置依次覆盖）。
  E. 基线（deploy_baseline.json）只允许减少。
  F. 自检：合成"静默成功"的脚本必须被识别。
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PATCHES = ROOT / "patches"
DEPLOYER = PATCHES / "apply_patches.py"
WRAPPER = PATCHES / "apply-patches.sh"
BASELINE = PATCHES / "deploy_baseline.json"

failures = []


def fail(m):
    failures.append(m)
    print("FAIL: " + m)


def ok(m):
    print("  ok: " + m)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


SILENT_SKIP = re.compile(r'^\s*return\s+0\s*$', re.M)
FAKE_SUCCESS = re.compile(r'补丁应用完成|✅.*完成')


def strip_shell_comments(text: str) -> str:
    """去掉整行注释与行尾注释后再做反模式判定。

    必须做这一步：新 apply-patches.sh 的注释里**刻意记录**了旧实现的失败特征
    （"✅ 补丁应用完成 (13/13)" 等），若把注释计入就会把正确实现判为错误
    —— 与 1.3/1.4 两轮踩过的"注释不是代码"是同一个坑。
    """
    out = []
    for line in text.splitlines():
        s = line.split("#", 1)[0]
        out.append(s)
    return "\n".join(out)


def looks_silent_success(script: str) -> bool:
    """旧实现的特征：静默 return 0 + 恒定成功输出（忽略注释）。"""
    code = strip_shell_comments(script) if script.lstrip().startswith("#!") or "#!/bin" in script[:60] else script
    return bool(SILENT_SKIP.search(code)) and bool(FAKE_SUCCESS.search(code))


def main():
    # ---------- F. 自检 ----------
    print("[F] 检测器自检")
    OLD_SCRIPT = """#!/bin/bash
apply_patch_dir() {
    if [ ! -d "$PATCHES_DIR/$1" ]; then
        return 0
    fi
    return 0
}
log_info "✅ 补丁应用完成 (13/13)"
"""
    if looks_silent_success(OLD_SCRIPT):
        ok("能识别旧的『静默跳过 + 恒报成功』实现")
    else:
        fail("检测器无法识别旧实现 —— 后续 PASS 不可信")

    for p in (DEPLOYER, WRAPPER):
        if not p.exists():
            fail(f"缺少 {p.relative_to(ROOT)}")
            print()
            sys.exit(1)

    # ---------- B. 反模式 ----------
    print("[B] 不得存在静默跳过 / 恒报成功")
    wrapper = read(WRAPPER)
    wrapper_code = strip_shell_comments(wrapper)
    if SILENT_SKIP.search(wrapper_code) or FAKE_SUCCESS.search(wrapper_code):
        fail("apply-patches.sh 含静默 return 0 或恒报成功输出")
    else:
        ok("apply-patches.sh 无静默跳过、无恒报成功（注释中的历史说明不计入）")
    if "exec " in wrapper and "apply_patches.py" in wrapper:
        ok("apply-patches.sh 委托给 apply_patches.py（exec，退出码透传）")
    else:
        fail("apply-patches.sh 未委托部署器")
    if re.search(r'command -v\s+"\$PY"', wrapper) and "exit 127" in wrapper:
        ok("python3 缺失时以非零退出（不假装成功）")
    else:
        fail("python3 缺失时未显式失败")

    deployer = read(DEPLOYER)
    if re.search(r'if\s+errors:', deployer) and re.search(r'return 1', deployer):
        ok("部署器对无法推导/空转项返回非零")
    else:
        fail("部署器未对错误返回非零")

    # ---------- C. 默认 dry-run + 显式 target ----------
    print("[C] 默认 dry-run，写入需显式目标")
    if re.search(r'if\s+not\s+a\.apply:\s*\n\s*a\.dry_run\s*=\s*True', deployer):
        ok("默认 dry-run")
    else:
        fail("未确认默认 dry-run 语义")
    if re.search(r'if\s+a\.apply\s+and\s+not\s+a\.target', deployer):
        ok("--apply 必须显式 --target")
    else:
        fail("--apply 未强制要求 --target")

    # ---------- D. 冲突遮蔽必须全局 ----------
    print("[D] 冲突遮蔽须跨补丁目录全局判定")
    if "all_files" in deployer and "跨补丁目录全局" in deployer:
        ok("部署器按文件名全局聚合后再判定遮蔽")
    else:
        fail("冲突遮蔽可能仍局限在单个补丁目录内（会互相覆盖）")

    # ---------- A. 真跑一次 dry-run ----------
    print("[A] 真跑 apply_patches.py --dry-run")
    # 强制子进程以 UTF-8 输出：否则 Windows 上部署器的中文统计行按本地码页
    # （GBK/cp936）编码，本检查器按 UTF-8 解码会得到乱码，正则永不匹配。
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    r = subprocess.run([sys.executable, str(DEPLOYER), "--dry-run"],
                       cwd=str(ROOT), capture_output=True, text=True,
                       encoding="utf-8", errors="replace", env=env)
    out = (r.stdout or "") + (r.stderr or "")
    if r.returncode == 0:
        ok("dry-run 以 0 退出")
    else:
        fail(f"dry-run 非零退出（{r.returncode}）")
    m = re.search(r'部署计划：(\d+) 个文件\s+被遮蔽：(\d+)\s+基线内未映射：(\d+)\s+错误：(\d+)', out)
    n_deploy = n_shadow = n_skip = n_err = None
    if not m:
        fail("未能从输出解析部署统计（输出格式变了？）")
    else:
        n_deploy, n_shadow, n_skip, n_err = map(int, m.groups())
        ok(f"统计解析：部署 {n_deploy} / 遮蔽 {n_shadow} / 基线跳过 {n_skip} / 错误 {n_err}")
        if n_err == 0:
            ok("错误为 0")
        else:
            fail(f"存在 {n_err} 项无法推导或空转")
        if n_deploy >= 60:
            ok(f"实际可部署文件数 {n_deploy}（不再是空转）")
        else:
            fail(f"可部署文件数异常偏少：{n_deploy}（疑似退化为空转）")
        if n_shadow >= 1:
            ok(f"冲突副本被遮蔽 {n_shadow} 份（全局判定生效）")
        else:
            fail("未遮蔽任何冲突副本 —— 多份同名配置会互相覆盖")

    # ---------- E. 基线只允许减少 ----------
    print("[E] 部署基线只允许减少")
    base = {}
    if BASELINE.exists():
        base = json.loads(read(BASELINE)).get("known_unmapped", {})
    if n_skip is None:
        fail(f"跳过计数未知（统计行未解析），无法与基线 {len(base)} 项比对")
    elif n_skip == len(base):
        ok(f"基线 {len(base)} 项与实际跳过一致（无过期条目）")
    else:
        fail(f"基线 {len(base)} 项与实际跳过 {n_skip} 不一致 —— 基线必须收紧")
    for k, v in base.items():
        if not (ROOT / k).exists():
            fail(f"基线条目指向不存在的文件：{k}")
        if len(v) < 10:
            fail(f"基线条目缺少理由：{k}")

    print()
    if failures:
        print(f"RESULT: FAIL（{len(failures)} 项）")
        sys.exit(1)
    print(f"RESULT: PASS —— 补丁栈可复现：{n_deploy} 个载荷可部署、"
          f"{n_shadow} 份冲突副本被遮蔽、{n_skip} 项基线（不可静默跳过）")
    sys.exit(0)


if __name__ == "__main__":
    main()
