#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主线② W4c：批量修正所有 AIDL Android.bp 为可编译的开发期配置
背景：模块缺 aidl_api/ 冻结 API，却声明 stability: "vintf" + versions -> Soong 编译必失败。
修正：
  1. 移除 stability: "vintf"（非 vendor/hal 接口，无需 vintf）
  2. 移除 versions / versions_with_info 块
  3. 加入 unstable: true（开发期不冻结 API）
用法: python3 tools/mainline2_fix_aidl_bp.py
"""
import os
import re

BASE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                     "overlay", "frameworks", "base", "core", "java", "com", "ainative"))


def fix_bp(path):
    with open(path, encoding="utf-8") as f:
        content = f.read()
    orig = content

    # 1. 移除 stability: "vintf",
    content = re.sub(r'\s*stability:\s*"vintf",', "", content)
    # 2. 移除 versions: [...] 块（含多行）
    content = re.sub(r'\s*versions:\s*\[[^\]]*\]\s*,?', "", content)
    # 3. 移除 versions_with_info: [...] 块
    content = re.sub(r'\s*versions_with_info:\s*\[.*?\]\s*,?', "", content, flags=re.S)
    # 4. 若已有 unstable: true 则跳过；否则在 aidl_interface { 的 name 后插入
    if "unstable: true" not in content:
        content = re.sub(r'(aidl_interface\s*\{\s*name:\s*"[^"]+",)',
                         r'\1\n    unstable: true,', content, count=1)

    if content != orig:
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        return True
    return False


def main():
    fixed = 0
    skipped = 0
    for root, dirs, files in os.walk(BASE):
        if "Android.bp" not in files:
            continue
        bp = os.path.join(root, "Android.bp")
        with open(bp, encoding="utf-8", errors="replace") as f:
            c = f.read()
        if "aidl_interface" not in c:
            continue  # 跳过 ainative.core 等非 AIDL 模块
        if fix_bp(bp):
            print(f"  [FIX] {os.path.relpath(bp, BASE)}")
            fixed += 1
        else:
            skipped += 1
    print(f"修正 {fixed} 个，无需修改 {skipped} 个")
    return 0


if __name__ == "__main__":
    main()
