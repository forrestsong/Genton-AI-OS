#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Genton 补丁部署器（WBS 3.1a 重建版）

替代原 apply-patches.sh 的行为。原脚本的三处致命问题：
  1. 映射表列的是 AOSP 上游目录（frameworks_base…），而 patches/ 下实际是 p0–p5 等交付批次
     → 每一条都走 `[ ! -d ]` 静默 `return 0`；
  2. 要求 `*.patch` 文件，而实际载荷是裸 `.java/.te/.mk` → 再次静默 `return 0`；
  3. 无论发生什么最后都打印"✅ 补丁应用完成 (13/13)"。
一句话：**它什么都没做，却报告成功。**

本版原则：
  * 目标路径**由文件自身推导**（代码文件读 `package` 声明），不手写易漂移的清单；
  * 任何无法推导目标的文件 → **非零退出**（不再静默跳过）；
  * 任何未被映射的补丁目录 → **非零退出**；
  * 每个补丁目录必须至少贡献 1 个文件，否则视为失败（防止"空转成功"）；
  * 冲突副本按 patches/../verification/build_config_baseline.json 的 conflict_policy
    只部署声明的权威版本，并打印被遮蔽的副本；
  * 默认 **dry-run**；真正写入必须显式 `--apply --target <AOSP_ROOT>`。

用法：
  python3 patches/apply_patches.py --dry-run
  python3 patches/apply_patches.py --apply --target /root/aosp
"""
import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
POLICY_FILE = ROOT / "verification" / "build_config_baseline.json"

# 代码文件：目标根目录（相对 AOSP 树根）
CODE_ROOTS = {
    "aidl": "frameworks/base/core/java",
    "com.ainative": "frameworks/base/core/java",
    "com.android.server.ainative": "frameworks/base/services/core/java",
    "com.android.systemui": "frameworks/base/packages/SystemUI/src",
    "com.ainative.brain": "packages/apps/AINativeBrain/src",
}

# 非代码载荷：按 (子目录, 文件名或扩展名) 显式映射（相对 AOSP 树根）
NONCODE_RULES = [
    (".te", "system/sepolicy/private"),
    ("file_contexts", "system/sepolicy/private"),
    ("service_contexts", "system/sepolicy/private"),
    ("vendor_file_contexts", "system/sepolicy/private"),
    ("vendor_service_contexts", "system/sepolicy/private"),
    ("plat_file_contexts_test", "system/sepolicy/private"),
    ("seapp_contexts", "system/sepolicy/private"),
    ("proguard.flags", "frameworks/base/services/core/java/com/android/server/ainative"),
    ("service_fuzzer_bindings.go", "system/sepolicy/private"),
    ("Android.bp", "packages/apps/AINativeBrain"),
    ("AndroidManifest.xml", "packages/apps/AINativeBrain"),
    ("inference_providers.json", "vendor/ainative/config"),
    (".mk", "device/ainative/cpday10"),
    ("action_registry.json", "vendor/ainative/config"),
    ("privapp-permissions-ainative-systemui.xml", "frameworks/base/packages/SystemUI"),
    ("privapp-permissions-ainative.xml", "frameworks/base/data/etc"),
    ("sysconfig-ainative.xml", "frameworks/base/data/etc"),
    ("README.md", None),          # 文档不部署
    ("SystemServer.java.patched", "frameworks/base/services/java/com/android/server"),
]

CODE_EXT = {".java", ".kt", ".aidl"}
PKG_RE = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def derive_target(payload: Path, rel: Path):
    """返回 (目标相对路径 or None, 说明)。None 表示无法推导/仅文档。"""
    name = payload.name
    if name.endswith(".md"):
        return None, "文档，不部署"
    ext = payload.suffix.lower()

    if ext == ".aidl":
        # AIDL 的 package 与目标根由包名决定
        m = PKG_RE.search(read(payload))
        if not m:
            return None, "AIDL 缺少 package 声明"
        pkg = m.group(1)
        # 优先按具体包名前缀匹配
        for prefix, root in CODE_ROOTS.items():
            if prefix == "aidl" or pkg == prefix or pkg.startswith(prefix + "."):
                return f"{root}/{pkg.replace('.', '/')}/{name}", f"由 package {pkg} 推导"
        return None, f"未知包名 {pkg}"

    if ext in CODE_EXT:
        if ext == ".kt" and "brain" in rel.parts:
            m = PKG_RE.search(read(payload))
            pkg = m.group(1) if m else "com.ainative.brain"
            return f"{CODE_ROOTS['com.ainative.brain']}/{pkg.replace('.', '/')}/{name}", \
                   f"由 package {pkg} 推导（大脑专用根）"
        m = PKG_RE.search(read(payload))
        if not m:
            return None, "缺少 package 声明"
        pkg = m.group(1)
        # 最长前缀优先，避免 com.ainative 抢走 com.android.server.ainative
        for prefix in sorted((k for k in CODE_ROOTS if k != "aidl"), key=len, reverse=True):
            if pkg == prefix or pkg.startswith(prefix + "."):
                return f"{CODE_ROOTS[prefix]}/{pkg.replace('.', '/')}/{name}", \
                       f"由 package {pkg} 推导"
        return None, f"未知包名 {pkg}"

    for token, root in NONCODE_RULES:
        if name == token or name.endswith(token):
            if root is None:
                return None, "文档，不部署"
            return f"{root}/{name}", f"非代码规则 {token}"
    return None, "非代码载荷且无映射规则"


def load_policy():
    if POLICY_FILE.exists():
        return json.loads(POLICY_FILE.read_text(encoding="utf-8")).get("conflict_policy", {})
    return {}


def load_baseline():
    p = HERE / "deploy_baseline.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8")).get("known_unmapped", {})
    return {}


def collect():
    """返回 (deploys, shadowed, skipped, errors)。

    重要：冲突判定必须**跨补丁目录全局**进行。4 份 ainative_overlay.mk 分散在
    4 个不同批次目录里，若只在目录内比较，它们会依次被写入同一目标路径——
    最后写入者获胜，正是 3.2 要消灭的"二义性"。
    """
    policy = load_policy()
    baseline = load_baseline()
    deploys, shadowed, skipped, errors = [], [], [], []
    per_dir = defaultdict(int)
    unmapped_now = set()

    # 第一遍：全局按文件名聚合
    all_files = defaultdict(list)      # basename -> [(payload, pdir)]
    for pdir in sorted(p for p in HERE.iterdir() if p.is_dir()):
        for payload in sorted(pdir.rglob("*")):
            if payload.is_file():
                all_files[payload.name].append((payload, pdir.name))

    # 第二遍：逐文件决定部署/遮蔽
    for name, items in sorted(all_files.items()):
        decl = policy.get(name)
        winner = Path(decl["winner"]).resolve() if decl else None
        if decl and len(items) > 1:
            for payload, pdir in items:
                if payload.resolve() != winner:
                    shadowed.append((name, payload.relative_to(ROOT).as_posix(),
                                     decl.get("pending_decision", "")))
        for payload, pdir in items:
            if winner is not None and len(items) > 1 and payload.resolve() != winner:
                continue  # 被遮蔽副本不部署
            if winner is not None and len(items) > 1 and payload.resolve() == winner:
                pass  # 权威副本照常部署
            rel = payload.relative_to(HERE / pdir)
            target, why = derive_target(payload, rel)
            if target is None:
                if "文档" in why:
                    continue
                relpath = payload.relative_to(ROOT).as_posix()
                unmapped_now.add(relpath)
                if relpath in baseline:
                    skipped.append((relpath, baseline[relpath]))
                    continue
                errors.append(f"{relpath}：{why}")
                continue
            deploys.append((payload, target, why, pdir))
            per_dir[pdir] += 1

    # 基线必须收紧：已能推导的条目不得继续留在基线里
    for stale in sorted(set(baseline) - unmapped_now):
        errors.append(f"基线条目已可推导，必须从 deploy_baseline.json 移除：{stale}")

    for pdir in sorted(p.name for p in HERE.iterdir() if p.is_dir()):
        if per_dir.get(pdir, 0) == 0 and not any(s[0].startswith(f"patches/{pdir}/")
                                                 for s in skipped):
            errors.append(f"补丁目录 {pdir} 未贡献任何文件（空转即失败）")
    return deploys, shadowed, skipped, errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只打印部署计划（默认）")
    ap.add_argument("--apply", action="store_true", help="真正写入目标树")
    ap.add_argument("--target", help="AOSP 树根（--apply 时必填）")
    a = ap.parse_args()

    if a.apply and not a.target:
        print("ERROR: --apply 必须显式给出 --target <AOSP_ROOT>（拒绝在未知目标上写入）")
        return 2
    if not a.apply:
        a.dry_run = True

    deploys, shadowed, skipped, errors = collect()

    print(f"部署计划：{len(deploys)} 个文件  被遮蔽：{len(shadowed)}  "
          f"基线内未映射：{len(skipped)}  错误：{len(errors)}")
    for payload, target, why, pdir in deploys:
        mark = "APPLY" if a.apply else "DRY  "
        print(f"  {mark} [{pdir}] {payload.relative_to(ROOT).as_posix()} -> {target}  ({why})")
    for name, path, dec in shadowed:
        print(f"  SHADOW {path} （{name} 的权威版本由 {dec} 决定，本副本不部署）")
    for relpath, reason in skipped:
        print(f"  SKIP(baseline) {relpath} —— {reason}")
    for e in errors:
        print(f"  ERROR {e}")

    if errors:
        print(f"\nFAIL：{len(errors)} 项无法推导或空转——拒绝以『成功』退出")
        return 1

    if a.apply:
        target = Path(a.target)
        if not target.is_dir():
            print(f"ERROR: 目标树不存在: {target}")
            return 2
        written = 0
        for payload, rel_target, why, pdir in deploys:
            dst = target / rel_target
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(payload.read_bytes())
            written += 1
        print(f"\nOK：已写入 {written} 个文件到 {target}")
    else:
        print(f"\nOK（dry-run）：{len(deploys)} 个文件可推导、无空转目录")
    return 0


if __name__ == "__main__":
    sys.exit(main())
