# -*- coding: utf-8 -*-
"""
WBS 3.2 验收：构建配置自洽性

检查 .mk 中引用的文件是否真的能在本仓库解析到，以及同一份配置是否存在互相冲突的副本。

**解析规则（关键，避免误报）**：本仓库是"归档树 + overlay"，构建时把 overlay/ 内容拷进
AOSP 树（cp -r overlay/* .）。因此一个形如 `frameworks/base/data/etc/x.xml` 的引用，
必须在以下根上依次解析：
    1) <repo>/<path>
    2) <repo>/overlay/<path>
    3) <repo>/patches/**/<basename>   （补丁栈里的部署载荷）
只要任一根命中即视为可解析，并记录**命中的是哪一根**——报告里必须能看出依据。
AOSP 自身路径（含 $(...) 变量、device/generic/**）不参与检查。

断言：
  A. inherit-product 引用的 .mk 必须可解析（AOSP 内部路径除外）
  B. PRODUCT_COPY_FILES 的源文件必须可解析
  C. 同名 .mk 多副本内容不一致 → FAIL（构建配置存在二义性：以哪份为准？）
  D. 已知真实缺口必须在 verification/build_config_baseline.json 登记（只允许减少）
  E. 自检：指向不存在文件的合成配置必须被识别
"""
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "verification" / "build_config_baseline.json"

MK_DIRS = ["patches", "overlay", "vendor", "temp_vendor", "temp_device_config"]

INHERIT = re.compile(r'inherit-product\s*,\s*([^\s),]+\.mk)')
COPY_SRC = re.compile(r'([A-Za-z0-9_./\-]+):\$\(TARGET_COPY_OUT')
AOSP_INTERNAL = ("device/generic/", "build/", "vendor/google", "device/google")

failures = []


def fail(m):
    failures.append(m)
    print("FAIL: " + m)


def ok(m):
    print("  ok: " + m)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def resolve(path, repo=ROOT):
    """返回 (命中的绝对路径 or None, 依据说明)。"""
    if path.startswith("$") or any(path.startswith(p) for p in AOSP_INTERNAL):
        return "AOSP", "AOSP 内部路径，跳过"
    cands = [("repo", repo / path), ("overlay", repo / "overlay" / path)]
    for tag, c in cands:
        if c.exists():
            return str(c), f"命中 {tag} 根"
    name = Path(path).name
    hits = list((repo / "patches").rglob(name))
    if hits:
        return str(hits[0]), f"命中 patches 载荷（{hits[0].relative_to(repo)}）"
    return None, "未解析到"


def collect_mk_files():
    files = []
    for d in MK_DIRS:
        base = ROOT / d
        if base.exists():
            files += [p for p in base.rglob("*.mk")]
    return files


def check_references(mk_files):
    missing = []
    total = 0
    for p in sorted(mk_files):
        raw = read(p)
        text = raw.replace("\\\n", " ")   # 合并续行，便于整体匹配
        refs = set(INHERIT.findall(text)) | set(COPY_SRC.findall(text))
        for ref in refs:
            total += 1
            resolved, why = resolve(ref)
            if resolved is None:
                missing.append((p, ref))
            elif resolved != "AOSP":
                pass
    return total, missing


def find_conflicting_copies(mk_files):
    """同名 .mk 多副本且内容不同 → 二义性。"""
    by_name = defaultdict(list)
    for p in mk_files:
        by_name[p.name].append(p)
    conflicts = []
    for name, paths in by_name.items():
        if len(paths) < 2:
            continue
        hashes = {}
        for p in paths:
            h = hashlib.sha256(read(p).encode("utf-8", "replace")).hexdigest()[:12]
            hashes.setdefault(h, []).append(p)
        if len(hashes) > 1:
            conflicts.append((name, hashes))
    return conflicts


def main():
    mk_files = collect_mk_files()
    print(f"扫描 .mk 文件 {len(mk_files)} 个")

    # ---------- E. 自检 ----------
    print("[E] 检测器自检")
    fake = ROOT / "verification" / "_selftest_fake.mk"
    try:
        fake.write_text("PRODUCT_COPY_FILES += \\\n"
                        "    definitely/not/here/file_xyz.json:$(TARGET_COPY_OUT_VENDOR)/x.json\n",
                        encoding="utf-8")
        _, missing = check_references([fake])
        if missing:
            ok("能识别指向不存在文件的引用")
        else:
            fail("检测器未能识别不存在的引用 —— 后续 PASS 不可信")
    finally:
        if fake.exists():
            fake.unlink()

    # ---------- A/B 引用解析 ----------
    print("[A/B] 引用可解析性")
    total, missing = check_references(mk_files)
    base = {}
    if BASELINE.exists():
        base = json.loads(BASELINE.read_text(encoding="utf-8")).get("known_gaps", {})
    # 路径一律归一化为正斜杠，避免 Windows 反斜杠导致基线键不匹配
    def key(p, r, root=ROOT):
        return f"{Path(p).resolve().relative_to(root.resolve()).as_posix()}::{r}"

    new_missing = [(p, r) for (p, r) in missing if key(p, r) not in base]
    stale = [k for k in base if k not in {key(p, r) for (p, r) in missing}]
    print(f"  共 {total} 条引用；未解析 {len(missing)} 条（其中基线内 {len(missing) - len(new_missing)} 条）")
    if new_missing:
        for p, r in new_missing:
            fail(f"{p.relative_to(ROOT)} 引用了不存在的文件: {r}")
    else:
        ok("无新增未解析引用")
    if stale:
        for k in stale:
            fail(f"基线条目已可解析，必须收紧: {k}")

    # ---------- C 二义性 ----------
    print("[C] 同名 .mk 副本二义性")
    policy = {}
    if BASELINE.exists():
        policy = json.loads(BASELINE.read_text(encoding="utf-8")).get("conflict_policy", {})
    conflicts = find_conflicting_copies(mk_files)
    undeclared = []
    for name, hashes in conflicts:
        paths = [str(p.relative_to(ROOT)) for hs in hashes.values() for p in hs]
        decl = policy.get(name)
        if not decl:
            undeclared.append((name, paths))
            continue
        winner = decl.get("winner", "")
        if not (ROOT / winner).exists():
            fail(f"{name} 声明的权威副本不存在: {winner}")
            continue
        losers = [p for p in paths if p.replace("/", "\\") != winner.replace("/", "\\")]
        ok(f"{name}：以 {winner} 为准（{decl.get('pending_decision','')} 待人工确认；"
           f"被遮蔽 {len(losers)} 份）")
    if undeclared:
        for name, paths in undeclared:
            fail(f"{name} 存在未声明的二义性（{len(paths)} 份）: " + " | ".join(paths))
    elif not conflicts:
        ok("无内容冲突的同名 .mk")
    elif not any(not policy.get(n) for n, _ in conflicts):
        ok("所有冲突副本均已有显式权威声明")

    print()
    if failures:
        print(f"RESULT: FAIL（{len(failures)} 项）")
        sys.exit(1)
    print(f"RESULT: PASS —— 构建配置自洽（基线内剩余 {len(base)} 项已知缺口）")
    sys.exit(0)


if __name__ == "__main__":
    main()
