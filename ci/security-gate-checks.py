#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
E1.2 CI 安全门 · 静态红线审计核（security-gate.sh / security-gate.bat 共用）

本文件是 security-gate 双版本（bash/bat）共享的"检查核"实现，
保证 Linux（WSL）/Windows 行为一致。

用法:
    python3 ci/security-gate-checks.py <项目根> console|json

返回码:
    0 = 通过（无 CRITICAL/HIGH）
    1 = 失败（存在 CRITICAL 或 HIGH 违规）

检查范围（对应红线）:
    B.  权限清单静态审计 R1.1/R1.2/R1.3/R1.5（AndroidManifest_permissions.xml）
    B2. 全仓 manifest 引用一致性 R1.9
    C.  privapp 白名单审计 R1.9
    D.  sepolicy 审计 R2.8/R2.9/R10.7/R1.10（neverallow / 白名单式）
    E.  service_contexts 对账 R2.8
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

# 强制 UTF-8 输出（Windows 控制台/CI 解析均按 UTF-8 读取）
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

base = sys.argv[1]
QUIET = (sys.argv[2] == "json")

violations = []          # {rule, severity, message, locations}
checks_run = [0]


def add(rule, sev, msg, locs=None):
    violations.append({"rule": rule, "severity": sev, "message": msg,
                       "locations": locs or []})


def out(msg):
    if not QUIET:
        print(msg)


def check(rule, sev, ok, ok_msg, bad_msg, locs=None):
    checks_run[0] += 1
    if ok:
        out(f"    [PASS] {rule}: {ok_msg}")
    else:
        add(rule, sev, bad_msg, locs)
        out(f"    [FAIL] {rule}: {bad_msg}")


# ---------- B. 权限清单静态审计（R1.1 / R1.2 / R1.3 / R1.5） ----------
# 权限定义唯一事实源（R1.2）
manifest = os.path.join(base, "overlay/frameworks/base/core/res/AndroidManifest_permissions.xml")
all_manifests = []
for root_dir, _, files in os.walk(base):
    for fn in files:
        if fn in ("AndroidManifest.xml", "AndroidManifest_permissions.xml"):
            p = os.path.join(root_dir, fn)
            if "build" not in p and ".git" not in p:
                all_manifests.append(p)
all_manifests = sorted(set(all_manifests))

declared_ainative = set()
if os.path.exists(manifest):
    out("[security-gate] B. 权限清单静态审计 ...")
    content = open(manifest, encoding="utf-8").read()
    # 权限定义（兼容属性顺序）
    perms = re.findall(
        r'<permission[^>]*android:name="([^"]*)"[^>]*android:protectionLevel="([^"]*)"',
        content)
    if not perms:
        perms = re.findall(
            r'<permission[^>]*android:protectionLevel="([^"]*)"[^>]*android:name="([^"]*)"',
            content)
        perms = [(n, p) for p, n in perms]
    ainative_perms = [(n, p) for n, p in perms if "com.ainative." in n]
    check("R1.2", "HIGH",
          len(ainative_perms) > 0,
          f"权限唯一事实源存在（{len(ainative_perms)} 个 com.ainative.* 定义）",
          "AndroidManifest_permissions.xml 未定义任何 com.ainative.* 权限", [manifest])
    for name, prot in ainative_perms:
        if "signature" not in prot:
            add("R1.1", "HIGH", f"权限 {name} 保护级别 {prot} 缺少 signature", [name])
        if "dangerous" in prot or "normal" in prot:
            add("R1.1", "CRITICAL", f"权限 {name} 使用了 {prot}（红线禁止 dangerous/normal）", [name])
        if not re.match(r"^com\.ainative\.permission\.[A-Z0-9_]+$", name):
            add("R1.5", "MEDIUM", f"权限命名不符合 com.ainative.permission.* 模式: {name}", [name])
    # R1.3 描述完整性
    no_desc = [n for n, _ in ainative_perms
               if not re.search(r'<permission[^>]*android:name="' + re.escape(n) +
                                r'"[^>]*android:description="[^"]+"', content)]
    check("R1.3", "MEDIUM", not no_desc,
          "所有 com.ainative.* 权限都有 description",
          f"{len(no_desc)} 个权限缺少 description（用途说明含糊）", no_desc[:5])
    declared_ainative = {n for n, _ in ainative_perms}
else:
    out("[security-gate] B. 跳过：权限 manifest 缺失（INFO）")

# B2. 全仓 manifest 中 uses-permission com.ainative.* 必须已声明（R1.9 一致性）
out("[security-gate] B2. 全仓 manifest 引用检查 ...")
undeclared_used = []
for mp in all_manifests:
    if os.path.exists(mp):
        mc = open(mp, encoding="utf-8").read()
        for used in re.findall(r'<uses-permission[^>]*android:name="(com\.ainative\.[^"]*)"', mc):
            if used not in declared_ainative:
                undeclared_used.append(f"{used} @ {os.path.relpath(mp, base)}")
check("R1.9", "HIGH", not undeclared_used,
      "全仓 uses-permission 的 com.ainative.* 均已声明",
      f"引用了未声明权限: {undeclared_used[:5]}",
      [os.path.relpath(mp, base) for mp in all_manifests])

# ---------- C. privapp 白名单审计（R1.9） ----------
privapp = os.path.join(base, "permissions/privapp-permissions-ainative.xml")
if os.path.exists(privapp):
    out("[security-gate] C. privapp 白名单审计 ...")
    root = ET.parse(privapp).getroot()
    all_names = []
    for priv in root.findall("privapp-permissions"):
        all_names += [p.get("name") for p in priv.findall("permission")]
    dup = {n for n in all_names if all_names.count(n) > 1}
    check("R1.9", "MEDIUM", not dup,
          "privapp 授予项无重复",
          f"重复授予权限: {sorted(dup)}", [privapp])
    bad = [n for n in all_names
           if not (n.startswith("android.permission.") or n.startswith("com.ainative.permission."))]
    check("R1.9", "HIGH", not bad,
          "privapp 授予项均属 android.* / com.ainative.* 命名空间",
          f"非规范权限名: {bad}", [privapp])
    undeclared = [n for n in all_names
                  if n.startswith("com.ainative.") and n not in declared_ainative]
    check("R1.9", "HIGH", not undeclared,
          "privapp 授予的 com.ainative 权限均已在权限清单声明",
          f"未在权限清单声明的 com.ainative 权限: {undeclared}", [privapp])
else:
    out("[security-gate] C. 跳过：privapp-permissions 缺失（INFO）")

# ---------- D. sepolicy 审计（R2.8 / R2.9 / R10.7 / R1.10） ----------
te = os.path.join(base, "sepolicy/ainative.te")
declared_types = set()
if os.path.exists(te):
    out("[security-gate] D. sepolicy 审计 ...")
    content = open(te, encoding="utf-8").read()
    has_neverallow = ("neverallow" in content and
                      "ainative_service_type" in content and
                      "appdomain" in content)
    check("R1.10", "CRITICAL", has_neverallow,
          "存在 appdomain 禁访 ainative 服务的 neverallow",
          "缺少 appdomain 对 ainative_service_type 的 neverallow（第三方可访问核心服务）", [te])
    broad = re.findall(r'^allow\s+(\S*ainative\S*)\s+[^ ]*\s+(?:\*|(?:\{[^}]*\}))\s+\*', content, re.M)
    broad2 = re.findall(r'^allow\s+(\S*ainative\S*)\s+[^ ]*\s+[^ ]*\s+\*', content, re.M)
    check("R2.8", "HIGH", not broad and not broad2,
          "ainative 域未见 class/perm 通配全放行",
          f"发现全放行 allow（应白名单化）: {sorted(set(broad + broad2))[:5]}", [te])
    has_audit_guard = re.search(
        r'neverallow\s+\{\s*domain\s*-\s*ainative_audit\s*-\s*system_server\s*\}\s+'
        r'ainative_audit_data_file:file read', content)
    check("R2.9", "HIGH", bool(has_audit_guard),
          "审计数据文件 read 受 neverallow 保护",
          "审计数据文件缺少只读保护 neverallow", [te])
    declared_types = set(re.findall(
        r'^type\s+(ainative_\w+_service)\s*,\s*ainative_service_type', content, re.M))
else:
    out("[security-gate] D. 跳过：ainative.te 缺失（INFO）")

# ---------- E. service_contexts 对账（R2.8） ----------
ctx = os.path.join(base, "sepolicy/service_contexts")
if os.path.exists(ctx):
    out("[security-gate] E. service_contexts 对账 ...")
    content = open(ctx, encoding="utf-8").read()
    mapped = set(re.findall(
        r'^(\S+)\s+u:object_r:(ainative_\w+_service):s0', content, re.M))
    mapped_types = {t for _, t in mapped}
    if declared_types:
        unmapped = declared_types - mapped_types
        check("R2.8", "HIGH", not unmapped,
              "ainative.te 中声明域均在 service_contexts 有映射",
              f"未映射到 service_contexts 的域类型: {sorted(unmapped)}", [te, ctx])
    bad_names = [s for s, _ in mapped if not re.match(r'^ainative_[a-z_]+$', s)]
    check("R2.8", "LOW", not bad_names,
          "服务名命名规范",
          f"服务名不符合 ainative_* 模式: {bad_names}", [ctx])
else:
    out("[security-gate] E. 跳过：service_contexts 缺失（INFO）")

# ---------- 汇总 ----------
by_sev = {}
for v in violations:
    by_sev.setdefault(v["severity"], []).append(v)
blocking = len(by_sev.get("CRITICAL", [])) + len(by_sev.get("HIGH", []))
result = {
    "tool": "security-gate-checks",
    "task": "E1.2",
    "checks_run": checks_run[0],
    "violations": violations,
    "blocking": blocking,
    "passed": blocking == 0,
}
if QUIET:
    print(json.dumps(result, ensure_ascii=False, indent=2))
else:
    out("")
    out(f"    [SUMMARY] 检查 {checks_run[0]} 项，违规 {len(violations)}（阻断 {blocking}）")
sys.exit(1 if blocking > 0 else 0)
