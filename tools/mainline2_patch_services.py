#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主线② W4b：patch AOSP services/core/Android.bp，让 services.core 依赖 ainative.* 模块
用法: python3 tools/mainline2_patch_services.py <aosp_dir>

逻辑：
  1. 定位 frameworks/base/services/core/Android.bp 中的 services.core 模块
  2. 在 static_libs 中加入所有 ainative.* AIDL 模块 + ainative.core（去重）
  3. 若 services.core 用了 jarjar/合并，确保 java_sdk_library 不受影响
"""
import os
import re
import sys


def find_services_core_block(content):
    """定位 services.core 模块块（从 name: "services.core" 到下一个模块开始）"""
    idx = content.find('name: "services.core"')
    if idx < 0:
        return None
    # 往回找模块起始（上一个顶层属性或 {）
    start = content.rfind("{", 0, idx)
    # 向后找匹配的闭合（粗略：同缩进的顶层 key 视为模块边界）
    lines = content.splitlines(keepends=True)
    line_idx = content[:idx].count("\n")
    start_line = line_idx
    # 向上找到包含 '{' 的模块声明行
    while start_line >= 0:
        if "{" in lines[start_line] and ("cc_defaults" in lines[start_line] or
                                          "java_library" in lines[start_line] or
                                          "java_sdk_library" in lines[start_line] or
                                          "filegroup" in lines[start_line] or
                                          "android_library" in lines[start_line]):
            break
        start_line -= 1
    if start_line < 0:
        return None
    return start_line, idx


def patch_services_core(bp_path, ainative_modules):
    with open(bp_path, encoding="utf-8") as f:
        content = f.read()

    # 找 services.core 的 static_libs 列表
    # 先定位 name: "services.core"
    m = re.search(r'name:\s*"services\.core"', content)
    if not m:
        print(f"[ERR] 未找到 services.core 模块: {bp_path}")
        return False

    # 找该模块后的 static_libs 块
    block_start = m.start()
    # 找到下一个 "}" 结束（粗略模块边界）
    module_end = content.find("\n}", block_start)
    if module_end < 0:
        module_end = len(content)
    module_text = content[block_start:module_end]

    static_libs_match = re.search(r'static_libs:\s*\[(.*?)\]', module_text, re.S)
    if not static_libs_match:
        print("[WARN] services.core 无 static_libs 块，需人工检查")
        # 尝试在 module_text 里找 libs:
        libs_match = re.search(r'\blibs:\s*\[(.*?)\]', module_text, re.S)
        if not libs_match:
            print("[ERR] services.core 无 libs/static_libs，无法 patch")
            return False
        target = libs_match
        target_name = "libs"
    else:
        target = static_libs_match
        target_name = "static_libs"

    existing = set(re.findall(r'"([^"]+)"', target.group(1)))
    additions = [mod for mod in ainative_modules if mod not in existing]
    if not additions:
        print(f"[OK] {target_name} 已包含全部 ainative 模块，无需修改")
        return True

    # 在列表末尾（] 前）插入 —— 注意：target 的偏移量属于 module_text，必须在 module_text 上操作
    insert_pos = target.end(1) - 1  # 在 ] 前
    indent = "        "
    add_text = "\n".join(f'{indent}"{mod}",' for mod in additions) + "\n"
    new_module_text = module_text[:insert_pos] + "\n" + add_text + module_text[insert_pos:]
    new_content = content[:block_start] + new_module_text + content[module_end:]

    with open(bp_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    print(f"[OK] services.core {target_name} 加入: {', '.join(additions)}")
    return True


def main():
    if len(sys.argv) < 2:
        print("用法: python3 mainline2_patch_services.py <aosp_dir>")
        return 2
    aosp = sys.argv[1]
    bp = os.path.join(aosp, "frameworks/base/services/core/Android.bp")
    if not os.path.exists(bp):
        print(f"[ERR] 未找到: {bp}")
        return 1

    # 收集 ainative AIDL 模块 + core（从 overlay Android.bp 提取模块名）
    overlay_base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                "overlay", "frameworks", "base", "core", "java", "com", "ainative")
    overlay_base = os.path.normpath(overlay_base)
    modules = ["ainative.core"]
    for root, dirs, files in os.walk(overlay_base):
        if "Android.bp" not in files:
            continue
        bp_path = os.path.join(root, "Android.bp")
        with open(bp_path, encoding="utf-8", errors="replace") as f:
            content = f.read()
        m = re.search(r'aidl_interface\s*{\s*name:\s*"([^"]+)"', content)
        if m:
            modules.append(m.group(1))
    modules = sorted(set(modules))
    print(f"将注入 {len(modules)} 个 ainative 模块: {', '.join(modules)}")

    return 0 if patch_services_core(bp, modules) else 1


if __name__ == "__main__":
    sys.exit(main())
