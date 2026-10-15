#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主线2 W8（B-M2）：patch AOSP SystemServer.java，调用 GentonServiceRegistry.start()
用法: python3 tools/mainline2_patch_systemserver.py <aosp_dir>

策略：
  1. 定位 frameworks/base/services/java/com/android/server/SystemServer.java
  2. 找 startOtherServices() 方法体，用花括号匹配定位方法末尾
  3. 在末尾前插入 GentonServiceRegistry.start(context) 调用（含 try/catch）
  幂等：已插入则跳过。
"""
import os
import re
import sys

MARK = "GentonServiceRegistry.start("


def find_method_body(content, signature_regex):
    m = re.search(signature_regex, content)
    if not m:
        return None, None
    # 找签名后的第一个 '{'
    brace = content.find("{", m.end())
    if brace < 0:
        return None, None
    depth = 0
    i = brace
    while i < len(content):
        if content[i] == "{":
            depth += 1
        elif content[i] == "}":
            depth -= 1
            if depth == 0:
                return brace, i
        i += 1
    return None, None


def main():
    if len(sys.argv) < 2:
        print("用法: python3 mainline2_patch_systemserver.py <aosp_dir>")
        return 2
    aosp = sys.argv[1]
    # AOSP 15 路径
    candidates = [
        os.path.join(aosp, "frameworks/base/services/java/com/android/server/SystemServer.java"),
        os.path.join(aosp, "frameworks/base/services/core/java/com/android/server/SystemServer.java"),
    ]
    path = next((p for p in candidates if os.path.exists(p)), None)
    if not path:
        print("[ERR] 未找到 SystemServer.java")
        return 1

    with open(path, encoding="utf-8") as f:
        content = f.read()

    if MARK in content:
        print("[OK] SystemServer.java 已包含 genton 注册调用，跳过")
        return 0

    start, end = find_method_body(content, r"private\s+void\s+startOtherServices\s*\(")
    if start is None:
        print("[ERR] 未定位 startOtherServices() 方法体")
        return 1

    indent = "        "
    # 自适应 tracing 风格：AOSP 13+ 用 t.traceBeginAndSlog；旧版用 traceBeginAndSlog
    uses_t = "t.traceBeginAndSlog(" in content
    trace_begin = 't.traceBeginAndSlog("StartGentonServices");' if uses_t \
        else 'traceBeginAndSlog("StartGentonServices");'
    trace_end = "t.traceEnd();" if uses_t else "traceEnd();"
    # Context 变量：AOSP 13+ 在 startOtherServices 内定义 final Context context = mSystemContext;
    ctx = "context" if re.search(r"\bContext\s+context\s*=", content) else "mSystemContext"

    snippet = (
        f"{indent}// AI Native OS: 注册 genton 系统服务（GentonServiceRegistry）\n"
        f"{indent}try {{\n"
        f"{indent}    {trace_begin}\n"
        f"{indent}    GentonServiceRegistry.start({ctx});\n"
        f"{indent}}} catch (Throwable e) {{\n"
        f"{indent}    reportWtf(\"starting Genton Services\", e);\n"
        f"{indent}}} finally {{\n"
        f"{indent}    {trace_end}\n"
        f"{indent}}}\n\n"
    )
    new_content = content[:end] + snippet + content[end:]
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)
    print(f"[OK] 已 patch {path}")
    print("     插入 GentonServiceRegistry.start(context) 于 startOtherServices() 末尾")
    return 0


if __name__ == "__main__":
    sys.exit(main())
