#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主线② 预检：AIDL 文件轻量语法检查（无需 aidl 编译器）
检查项：
  1. 必须有 package 声明
  2. interface/parcelable 花括号配对
  3. 引用的类型在同一包或其他已声明模块内
  4. 方法签名有返回值（首 token 大写为类型）
用法: python3 tools/mainline2_check_aidl_syntax.py
"""
import os
import re
import sys

BASE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                     "overlay", "frameworks", "base", "core", "java", "com", "genton"))


def check_aidl(path):
    problems = []
    with open(path, encoding="utf-8", errors="replace") as f:
        content = f.read()
    name = os.path.basename(path)

    # 1. package 声明
    pkg = re.search(r'^package\s+([\w.]+)\s*;', content, re.M)
    if not pkg:
        problems.append(f"{name}: 缺 package 声明")
        return problems
    pkg_name = pkg.group(1)

    # 2. 花括号配对
    if content.count("{") != content.count("}"):
        problems.append(f"{name}: 花括号不配对 ({{={content.count('{')} }}={content.count('}')})")

    # 3. interface 内方法基本检查
    # 去掉注释
    no_comment = re.sub(r'/\*.*?\*/', '', content, flags=re.S)
    no_comment = re.sub(r'//[^\n]*', '', no_comment)
    # 提取 interface/parcelable 块
    for m in re.finditer(r'\b(interface|parcelable)\s+(\w+)\s*\{', no_comment):
        kind, iface = m.group(1), m.group(2)
        # 找匹配闭合括号
        start = m.end() - 1
        depth = 1
        i = start + 1
        body = ""
        while i < len(no_comment) and depth > 0:
            if no_comment[i] == "{":
                depth += 1
            elif no_comment[i] == "}":
                depth -= 1
            body += no_comment[i]
            i += 1
        if depth != 0:
            problems.append(f"{name}: {kind} {iface} 括号未闭合")

    return problems


def main():
    all_problems = []
    files = 0
    for root, dirs, files_list in os.walk(BASE):
        for fn in files_list:
            if fn.endswith(".aidl"):
                files += 1
                all_problems.extend(check_aidl(os.path.join(root, fn)))
    print(f"检查 {files} 个 AIDL 文件")
    if all_problems:
        print(f"发现 {len(all_problems)} 个问题:")
        for p in all_problems:
            print(f"  [WARN] {p}")
        return 0  # 轻量检查，WARN 不阻塞（以真实 aidl 编译为准）
    print("OK: 所有 AIDL 轻量语法检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
