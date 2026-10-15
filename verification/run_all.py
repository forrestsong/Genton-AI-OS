# -*- coding: utf-8 -*-
"""统一验收入口（WBS 0.5）：运行 verification/ 下所有检查，任一失败即非零退出。"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HERE = Path(__file__).resolve().parent

CHECKS = [
    # 跨环境一致性放在最前：归档里修好了、构建树里没修，等于没修
    "check_build_tree_parity.py",
    # 编译前符号自洽：重复定义/依赖缺失只有编译才暴露，必须提前拦
    "check_precompile_symbols.py",
    # 权限声明一致性 + 未接线 manifest 片段（两次真实运行时事故的静态化）
    "check_permission_declarations.py",
    "check_1_1_no_prefix_bypass.py",
    "check_1_3_binder_authz.py",
    "check_1_4_sepolicy_consistency.py",
    "check_1_5_confirmation_chain.py",
    "check_1_6_audit_persistence.py",
    "check_1_8_sandbox_honesty.py",
    "check_1_9_memory_pii.py",
    "check_1_10_redteam_registry.py",
    "check_3_1_patch_stack.py",
    "check_3_2_build_config.py",
    "check_no_secrets_in_git.py",
]

results = []
for name in CHECKS:
    path = HERE / name
    print("=" * 72)
    print(f"RUN {name}")
    print("=" * 72)
    r = subprocess.run([sys.executable, str(path)], cwd=str(ROOT))
    results.append((name, r.returncode))

print()
print("=" * 72)
print("验收汇总")
print("=" * 72)
failed = 0
for name, code in results:
    status = "PASS" if code == 0 else f"FAIL (exit {code})"
    if code != 0:
        failed += 1
    print(f"  {status:<14} {name}")
print()
if failed:
    print(f"RESULT: FAIL —— {failed}/{len(results)} 项未通过")
    sys.exit(1)
print(f"RESULT: PASS —— {len(results)}/{len(results)} 项通过")
sys.exit(0)
