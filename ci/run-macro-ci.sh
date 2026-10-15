#!/bin/bash
# ============================================================
# AI Native OS - 宏资产 CI 校验流水线（F1.2）- Linux/macOS 版本
# ============================================================
# 功能：黄金样例重放 / 失败自动停用回灌 A8.1（调用占位）/
#       版本基线管理 / 多格式报告
# 依赖：python3 3.8+；设备模式另需 adb。
# 对齐：ci/macro-ci-config.yaml、docs/macro-testing-specification.md、
#       reports/A8.1_完成报告.md
#
# 用法：
#   ./run-macro-ci.sh [--offline-validate] [--update-baseline]
#                     [--device <serial>] [--golden-dir <dir>]
#                     [--out-dir <dir>] [--help]
#
# 退出码约定：
#   0   通过
#   1   测试失败或基线漂移（需 review）
#   2   有宏被自动停用并写入 A8.1 回灌清单（需 review）
#   3   环境错误（无设备 / 无 adb / 重放基础设施不可用）
#   4   配置或 schema 错误
#   130 用户中断
# ============================================================

set -u
export PYTHONIOENCODING=utf-8

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLS_DIR="$PROJECT_ROOT/tools"
CONFIG_FILE="$PROJECT_ROOT/ci/macro-ci-config.yaml"
GOLDEN_DIR="$PROJECT_ROOT/tests/macro/golden"
OUT_DIR="$PROJECT_ROOT/reports/ci/macro"
STATE_DIR="$PROJECT_ROOT/ci/state"
STATE_FILE="$STATE_DIR/macro-ci-state.json"
QUEUE_FILE="$STATE_DIR/auto-disable-queue.json"
BASELINE_FILE="$STATE_DIR/macro-baseline.json"
PYTHON="${PYTHON:-python3}"
ADB="${ADB:-adb}"
DEVICE=""
OFFLINE=0
UPDATE_BASELINE=0
FINAL_EXIT=0

usage() {
    cat <<EOF
用法: run-macro-ci.sh [选项]

  --offline-validate   仅静态校验 + 基线 dry-run（无需设备）
  --update-baseline    采纳当前版本为基线（修复版本漂移）
  --device <serial>    指定 adb 设备序列号
  --golden-dir <dir>   黄金样例目录（默认 tests/macro/golden）
  --out-dir <dir>      报告输出目录（默认 reports/ci/macro）
  --help               显示本帮助
EOF
    exit 0
}

# ---------- 参数解析 ----------
while [ $# -gt 0 ]; do
    case "$1" in
        --offline-validate) OFFLINE=1 ;;
        --update-baseline)  UPDATE_BASELINE=1 ;;
        --help)             usage ;;
        --device)           shift; DEVICE="${1:-}" ;;
        --golden-dir)       shift; GOLDEN_DIR="${1:-}" ;;
        --out-dir)          shift; OUT_DIR="${1:-}" ;;
        *) echo "未知参数: $1（--help 查看用法）" >&2; exit 4 ;;
    esac
    shift
done

echo "==============================================="
echo "AI Native OS - 宏资产 CI 校验流水线（F1.2）"
echo "==============================================="
echo "项目根目录 : $PROJECT_ROOT"
echo "黄金样例目录: $GOLDEN_DIR"
echo "输出目录   : $OUT_DIR"
echo "状态目录   : $STATE_DIR"
if [ "$OFFLINE" = "1" ]; then echo "模式       : offline-validate（静态校验 + 基线 dry-run，不要求设备）"; fi
if [ -n "$DEVICE" ]; then echo "目标设备   : $DEVICE"; else echo "目标设备   : auto（adb 默认设备）"; fi
echo ""

# ---------- 前置检查：python3 ----------
if ! command -v "$PYTHON" >/dev/null 2>&1; then
    echo "[CONFIG-ERROR] 未找到 python3（$PYTHON）。宏资产 CI 依赖 python3 处理 JSON/状态/基线。" >&2
    exit 4
fi

mkdir -p "$STATE_DIR" "$OUT_DIR"

# ---------- Stage 1: 静态校验 ----------
echo "[Stage 1/5] 黄金样例静态校验..."
"$PYTHON" "$TOOLS_DIR/macro_ci_core.py" validate --golden-dir "$GOLDEN_DIR" --config "$CONFIG_FILE"
STAGE_EXIT=$?
[ "$STAGE_EXIT" -gt "$FINAL_EXIT" ] && FINAL_EXIT=$STAGE_EXIT
if [ "$STAGE_EXIT" -ne 0 ]; then
    echo "[Stage 1] 静态校验未通过，流水线终止。"
    exit $FINAL_EXIT
fi

# ---------- Stage 2: 设备检查（offline 跳过） ----------
echo ""
echo "[Stage 2/5] 设备检查..."
if [ "$OFFLINE" = "1" ]; then
    echo "  offline 模式：跳过设备检查与重放。"
elif ! command -v "$ADB" >/dev/null 2>&1; then
    echo "[ENV-ERROR] 未找到 adb（请将 ANDROID_HOME/platform-tools 加入 PATH）。" >&2
    FINAL_EXIT=3
else
    if ! "$ADB" devices >/dev/null 2>&1; then
        echo "[ENV-ERROR] adb 不可用。" >&2
        FINAL_EXIT=3
    fi
fi

# ---------- Stage 3: 黄金样例重放 ----------
echo ""
echo "[Stage 3/5] 黄金样例重放..."
if [ "$OFFLINE" = "1" ]; then
    "$PYTHON" "$TOOLS_DIR/macro_ci_core.py" replay --offline \
        --golden-dir "$GOLDEN_DIR" --config "$CONFIG_FILE" --out-dir "$OUT_DIR"
else
    ARGS=""
    [ -n "$DEVICE" ] && ARGS="--device $DEVICE"
    "$PYTHON" "$TOOLS_DIR/macro_ci_core.py" replay $ARGS \
        --golden-dir "$GOLDEN_DIR" --config "$CONFIG_FILE" --out-dir "$OUT_DIR"
fi
STAGE_EXIT=$?
[ "$STAGE_EXIT" -gt "$FINAL_EXIT" ] && FINAL_EXIT=$STAGE_EXIT

# ---------- Stage 4: 失败率检查 + 自动停用回灌 A8.1 ----------
echo ""
echo "[Stage 4/5] 失败率检查与自动停用判定..."
"$PYTHON" "$TOOLS_DIR/macro_ci_core.py" gatedisable \
    --report "$OUT_DIR/report.json" --config "$CONFIG_FILE" \
    --state-file "$STATE_FILE" --queue-file "$QUEUE_FILE"
STAGE_EXIT=$?
[ "$STAGE_EXIT" -gt "$FINAL_EXIT" ] && FINAL_EXIT=$STAGE_EXIT
if [ "$STAGE_EXIT" -eq 2 ]; then echo "[Stage 4] 已写入 A8.1 回灌清单: $QUEUE_FILE"; fi

# ---------- Stage 5: 版本基线管理 ----------
echo ""
echo "[Stage 5/5] 版本基线管理..."
if [ "$UPDATE_BASELINE" = "1" ]; then
    "$PYTHON" "$TOOLS_DIR/macro_ci_core.py" baseline --update \
        --report "$OUT_DIR/report.json" --baseline-file "$BASELINE_FILE"
else
    "$PYTHON" "$TOOLS_DIR/macro_ci_core.py" baseline \
        --report "$OUT_DIR/report.json" --baseline-file "$BASELINE_FILE"
fi
STAGE_EXIT=$?
[ "$STAGE_EXIT" -gt "$FINAL_EXIT" ] && FINAL_EXIT=$STAGE_EXIT

echo ""
echo "==============================================="
echo "宏资产 CI 完成：退出码 $FINAL_EXIT"
echo "  (0=通过 1=测试失败/基线漂移 2=自动停用 3=环境错误 4=配置错误)"
echo "  报告: $OUT_DIR/report.json / junit.xml"
echo "  回灌清单: $QUEUE_FILE"
echo "==============================================="
exit $FINAL_EXIT
