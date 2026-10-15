#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主线2 W9：构建产物验收校验（CI 门禁）

对照固定基线校验镜像内容，任一不达标即返回非零退出码。
用法: python3 w9_verify.py <aosp_root> [--json <out.json>]
"""
import json
import os
import re
import subprocess
import sys
import zipfile

# ---- 验收基线（依据 2026-10-07 已验证的构建确定，留有余量）----
BASELINE = {
    "system.img":      1_000_000_000,   # ≥1GB
    "vendor.img":         30_000_000,   # ≥30MB
    "product.img":       200_000_000,   # ≥200MB
}
MIN_FRAMEWORK_CLASSES = 480      # framework.jar 中 com/ainative 类数
MIN_SERVICE_CLASSES   = 220      # services.jar 中 server/ainative 类数
MIN_SERVICE_IFACES    = 17       # 注册的服务接口数
MIN_CONFIG_FILES      = 10       # /vendor/ainative/config 文件数
MIN_SERVICE_CONTEXTS  = 40       # sepolicy 服务上下文条目数


def dex_strings(jar, pattern):
    """从 jar 的 classes*.dex 中提取匹配 pattern 的唯一字符串"""
    found = set()
    try:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if re.match(r'classes\d*\.dex$', name):
                    data = z.read(name)
                    for m in re.finditer(pattern.encode(), data):
                        found.add(m.group().decode('utf-8', 'replace'))
    except Exception as e:
        return set(), str(e)
    return found, None


def main():
    if len(sys.argv) < 2:
        print("用法: python3 w9_verify.py <aosp_root> [--json <out>]")
        return 2
    root = sys.argv[1]
    json_out = None
    if "--json" in sys.argv:
        json_out = sys.argv[sys.argv.index("--json") + 1]

    prod = os.path.join(root, "out/target/product/cpday10")
    results = []
    failed = 0

    def check(name, ok, detail, baseline=""):
        nonlocal failed
        results.append({"check": name, "ok": bool(ok), "detail": detail, "baseline": baseline})
        if not ok:
            failed += 1
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {name}: {detail}" + (f"  (基线 {baseline})" if baseline else ""))

    print("=" * 64)
    print("W9 构建产物验收校验")
    print("=" * 64)

    # ---- 1. 镜像产物 ----
    print("\n[1] 镜像产物")
    for img, min_size in BASELINE.items():
        p = os.path.join(prod, img)
        if os.path.exists(p):
            sz = os.path.getsize(p)
            check(img, sz >= min_size, f"{sz/1048576:.0f} MB", f"≥{min_size//1048576} MB")
        else:
            check(img, False, "缺失")

    # ---- 2. framework.jar：AIDL 契约 + 平台类 ----
    print("\n[2] framework.jar（AIDL 契约 + 平台类）")
    fw = os.path.join(prod, "system/framework/framework.jar")
    if os.path.exists(fw):
        classes, err = dex_strings(fw, r'Lcom/ainative/[A-Za-z0-9/_$]*;')
        check("com/ainative 类数", len(classes) >= MIN_FRAMEWORK_CLASSES,
              f"{len(classes)} 个", f"≥{MIN_FRAMEWORK_CLASSES}")
    else:
        check("framework.jar", False, "缺失")

    # ---- 3. services.jar：服务实现 + 注册表 ----
    print("\n[3] services.jar（服务实现 + 注册表）")
    sv = os.path.join(prod, "system/framework/services.jar")
    if os.path.exists(sv):
        classes, _ = dex_strings(sv, r'Lcom/android/server/ainative/[A-Za-z0-9_$]*;')
        check("服务实现类数", len(classes) >= MIN_SERVICE_CLASSES,
              f"{len(classes)} 个", f"≥{MIN_SERVICE_CLASSES}")

        reg, _ = dex_strings(sv, r'AinativeServiceRegistry')
        check("AinativeServiceRegistry", len(reg) >= 1, f"{len(reg)} 处引用")

        ifaces, _ = dex_strings(sv, r'com\.ainative\.[a-z]+\.[A-Z][A-Za-z]+')
        ifaces = {i for i in ifaces if not i.startswith("com.ainative.permission")}
        check("注册的服务接口", len(ifaces) >= MIN_SERVICE_IFACES,
              f"{len(ifaces)} 个", f"≥{MIN_SERVICE_IFACES}")
    else:
        check("services.jar", False, "缺失")

    # ---- 4. vendor 配置 ----
    print("\n[4] vendor 配置")
    cfg = os.path.join(prod, "vendor/ainative/config")
    n = len(os.listdir(cfg)) if os.path.isdir(cfg) else 0
    check("/vendor/ainative/config 文件", n >= MIN_CONFIG_FILES, f"{n} 个", f"≥{MIN_CONFIG_FILES}")

    # ---- 5. sepolicy ----
    print("\n[5] sepolicy")
    sc = os.path.join(prod, "system/etc/selinux/plat_service_contexts")
    if os.path.exists(sc):
        cnt = sum(1 for ln in open(sc, encoding="utf-8", errors="replace") if "ainative" in ln)
        check("服务上下文条目", cnt >= MIN_SERVICE_CONTEXTS, f"{cnt} 条", f"≥{MIN_SERVICE_CONTEXTS}")
    else:
        check("plat_service_contexts", False, "缺失")

    # ---- 6. 源码链路（回归检查：关键修复仍在位）----
    print("\n[6] 源码链路（关键修复回归）")
    fixes = [
        ("frameworks/base/services/proguard.flags",
         "com.android.server.ainative", "R8 keep 规则"),
        ("frameworks/base/services/java/com/android/server/SystemServer.java",
         "AinativeServiceRegistry.start(", "SystemServer 注册调用"),
        ("frameworks/base/services/core/java/com/android/server/ainative/AinativeServiceRegistry.java",
         "public static void start(", "服务注册表"),
        ("device/ainative/cpday10/BoardConfig.mk",
         "TARGET_COPY_OUT_VENDOR := vendor", "vendor 分区布局"),
    ]
    for rel, needle, label in fixes:
        p = os.path.join(root, rel)
        ok = os.path.exists(p) and needle in open(p, encoding="utf-8", errors="replace").read()
        check(label, ok, "在位" if ok else "缺失")

    # ---- 汇总 ----
    print("\n" + "=" * 64)
    total = len(results)
    print(f"验收结果: {total - failed}/{total} 通过, {failed} 失败")
    print("=" * 64)

    if json_out:
        with open(json_out, "w", encoding="utf-8") as f:
            json.dump({"total": total, "failed": failed, "results": results},
                      f, ensure_ascii=False, indent=2)
        print(f"JSON 报告: {json_out}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
