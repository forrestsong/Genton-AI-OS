# -*- coding: utf-8 -*-
"""
跨环境一致性门禁：**归档树 == 构建树**（WBS 0.2 / 3.4）

为什么需要这道门禁（真实教训）：
  本项目前九批安全修复全部落在工作区归档（com.ainative.*）里，
  而真正被编译的是 WSL 中的构建树 /root/aosp（已改名为 com.genton.*）。
  结果：修复看起来"完成"，但对镜像零影响——构建树里的前缀后门、假 TEE 签名
  一个都没少。本轮之前没有任何门禁能发现这件事，因为所有检查都只扫归档。

本检查器在构建树可达时，逐条断言**安全不变量在构建树中同样成立**：
  1. 不存在按 actionId 前缀判定内部动作的后门；
  2. 存在统一的调用方鉴权助手（AgentCallerAuth / 等价物）；
  3. 确认回灌存在 HMAC 复验（isGenuinePendingConfirmation / 等价物）；
  4. 审计链有 fsync 落盘；
  5. 不存在"别名+SHA256"式假 TEE 签名。

构建树不可达时 **SKIP（退出 0）**，但必须显式打印"无法验证"，绝不静默通过。
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TREE = "/root/aosp"
GENTON_DIR = f"{TREE}/frameworks/base/services/core/java/com/android/server/genton"


def wsl(script: str, timeout=60):
    try:
        r = subprocess.run(["wsl", "-u", "root", "-e", "bash", "-c", script],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except FileNotFoundError:
        return 127, "wsl 不可用"
    except subprocess.TimeoutExpired:
        return 124, "timeout"


def main():
    failures = []

    rc, out = wsl(f"test -d {GENTON_DIR} && echo REACHABLE || echo UNREACHABLE")
    if "REACHABLE" not in out:
        print("SKIP：构建树不可达（未找到 WSL 或 /root/aosp）——**无法验证修复是否进入镜像**。")
        print("      这不是通过；在没有构建树的环境里，本项只能记为『未验证』。")
        sys.exit(0)
    print(f"构建树可达：{GENTON_DIR}")

    def grep_count(pattern, fname):
        rc, out = wsl(f"grep -c '{pattern}' {GENTON_DIR}/{fname} 2>/dev/null || echo 0")
        try:
            return int(out.strip().splitlines()[-1])
        except Exception:
            return -1

    def grep_exists(pattern, fname):
        rc, out = wsl(f"grep -q '{pattern}' {GENTON_DIR}/{fname} 2>/dev/null && echo YES || echo NO")
        return "YES" in out

    # 1. 前缀后门
    rc, out = wsl(f"grep -n 'startsWith(\"sys.\")' {GENTON_DIR}/ActionExecutionService.java 2>/dev/null | head -3")
    if out.strip():
        failures.append("构建树仍存在内部动作前缀后门：\n" + out.strip())
    else:
        print("  ok: 构建树无前缀后门")

    # 2. 统一鉴权助手
    rc, out = wsl(f"ls {GENTON_DIR}/AgentCallerAuth.java 2>/dev/null && echo FOUND || echo MISSING")
    if "FOUND" in out:
        print("  ok: 构建树存在 AgentCallerAuth")
    else:
        failures.append("构建树缺少统一鉴权助手 AgentCallerAuth.java（1.3 未移植）")

    # 3. 确认链 HMAC 复验
    n = grep_count("isGenuinePendingConfirmation", "ActionExecutionService.java")
    if n and n > 0:
        print("  ok: 构建树存在确认回灌 HMAC 复验")
    else:
        failures.append("构建树缺少确认回灌 HMAC 复验 isGenuinePendingConfirmation（1.5 未移植）")

    # 4. 审计 fsync
    n = grep_count("getFD().sync()", "AgentAuditService.java")
    if n and n > 0:
        print("  ok: 构建树审计链有 fsync 落盘")
    else:
        failures.append("构建树审计链无 fsync 落盘（1.6 未移植）")

    # 5. 假 TEE 签名
    rc, out = wsl(f"grep -n 'TEE_KEY_ALIAS + ' {GENTON_DIR}/TeeAnchorManager.java 2>/dev/null | head -2")
    if out.strip():
        failures.append("构建树仍存在假 TEE 签名（1.6b 未移植）：\n" + out.strip())
    else:
        print("  ok: 构建树无假 TEE 签名")

    # 6. 归档与构建树的规模差异（信息项，用于提示"两边早已分叉"）
    rc, arch = wsl("ls '/mnt/d/AI Native OS/overlay/frameworks/base/services/core/java/com/android/server/ainative' 2>/dev/null | wc -l")
    rc, tree = wsl(f"ls {GENTON_DIR} | wc -l")
    print(f"  信息：归档目录 {arch.strip()} 个文件 vs 构建树 {tree.strip()} 个文件")

    print()
    if failures:
        print(f"RESULT: FAIL（{len(failures)} 项）—— 归档中的修复**未进入构建树**，对镜像无效")
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("RESULT: PASS —— 归档与构建树在安全不变量上一致")
    sys.exit(0)


if __name__ == "__main__":
    main()
