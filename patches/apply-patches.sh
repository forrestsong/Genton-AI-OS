#!/bin/bash
# Genton 补丁部署入口（WBS 3.1a 重建版）
#
# 真正的逻辑在 apply_patches.py。要点：
#   * 目标路径由载荷自身推导（代码读 package 声明），不维护易漂移的映射表；
#   * 任何无法推导的载荷、任何空转的补丁目录 → 非零退出；
#   * 冲突副本按 build_config_baseline.json 的 conflict_policy 只部署权威版本；
#   * 默认 dry-run；真正写入必须显式 --apply --target <AOSP_ROOT>。
#
# 已废弃的旧实现（保留此说明以免有人再写回来）：
#   映射表指向 AOSP 上游目录（frameworks_base…）· 要求 *.patch 而实际是裸文件 ·
#   两条路径都静默 `return 0` · 最后无论如何都打印"✅ 补丁应用完成 (13/13)"。
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON3:-python3}"

if ! command -v "$PY" >/dev/null 2>&1; then
    echo "ERROR: 补丁部署器需要 python3（可用 PYTHON3 环境变量指定解释器）" >&2
    exit 127
fi

exec "$PY" "$SCRIPT_DIR/apply_patches.py" "$@"
