#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主线② W4d：patch AINativeBrain/Android.bp，让 static_libs 加入 ainative.* AIDL 模块
用法: python3 tools/mainline2_patch_brain.py <aosp_dir>
"""
import os
import re
import sys


def main():
    if len(sys.argv) < 2:
        print("用法: python3 mainline2_patch_brain.py <aosp_dir>")
        return 2
    aosp = sys.argv[1]
    bp = os.path.join(aosp, "packages/apps/AINativeBrain/Android.bp")
    if not os.path.exists(bp):
        print(f"[ERR] 未找到: {bp}")
        return 1

    # 收集 ainative.* AIDL 模块
    overlay_base = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                                 "overlay", "frameworks", "base", "core", "java", "com", "ainative"))
    modules = ["ainative.core"]
    for root, dirs, files in os.walk(overlay_base):
        if "Android.bp" not in files:
            continue
        with open(os.path.join(root, "Android.bp"), encoding="utf-8", errors="replace") as f:
            c = f.read()
        m = re.search(r'aidl_interface\s*{\s*name:\s*"([^"]+)"', c)
        if m:
            modules.append(m.group(1))
    modules = sorted(set(modules))

    with open(bp, encoding="utf-8") as f:
        content = f.read()

    # 在 static_libs 块末尾加入
    m = re.search(r'static_libs:\s*\[(.*?)\]', content, re.S)
    if not m:
        print("[ERR] AINativeBrain 无 static_libs 块")
        return 1
    existing = set(re.findall(r'"([^"]+)"', m.group(1)))
    additions = [mod for mod in modules if mod not in existing]
    if not additions:
        print("[OK] AINativeBrain static_libs 已包含全部 ainative 模块")
        return 0
    insert_pos = m.end(1) - 1
    add_text = "".join(f'        "{mod}",\n' for mod in additions)
    content = content[:insert_pos] + "\n" + add_text + content[insert_pos:]
    with open(bp, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"[OK] AINativeBrain static_libs 加入: {', '.join(additions)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
