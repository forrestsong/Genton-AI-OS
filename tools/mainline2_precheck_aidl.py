#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主线② 预检：验证所有 AIDL Android.bp 引用的 srcs 文件存在 + 语法粗检
用法: python3 tools/mainline2_precheck_aidl.py
"""
import fnmatch
import glob as globmod
import os
import re
import sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "overlay", "frameworks", "base", "core", "java", "com", "ainative")
BASE = os.path.normpath(BASE)

def parse_bp_srcs(bp_path):
    """从 Android.bp 提取 srcs 列表（简化解析，支持多行与注释）"""
    with open(bp_path, encoding="utf-8", errors="replace") as f:
        content = f.read()
    # 去掉注释
    content = re.sub(r"//.*", "", content)
    srcs = []
    m = re.search(r"srcs:\s*\[(.*?)\]", content, re.S)
    if m:
        for item in re.findall(r'"([^"]+)"', m.group(1)):
            srcs.append(item)
    return srcs

def main():
    errors = []
    modules = 0
    for root, dirs, files in os.walk(BASE):
        if "Android.bp" not in files:
            continue
        bp = os.path.join(root, "Android.bp")
        # 跳过顶层（ainative.core 是 java_library）
        if root == BASE:
            continue
        srcs = parse_bp_srcs(bp)
        modules += 1
        for s in srcs:
            p = os.path.join(root, s)
            if any(ch in s for ch in "*?"):
                # 通配符：检查是否有匹配文件
                matches = globmod.glob(p)
                if not matches:
                    errors.append(f"[{os.path.basename(root)}] GLOB-NO-MATCH: {s}")
            elif not os.path.exists(p):
                errors.append(f"[{os.path.basename(root)}] MISSING: {s}")
    print(f"checked {modules} AIDL modules")
    if errors:
        print(f"found {len(errors)} missing refs:")
        for e in errors:
            print(f"  [ERR] {e}")
        return 1
    print("OK: all Android.bp refs exist")
    return 0

if __name__ == "__main__":
    sys.exit(main())
