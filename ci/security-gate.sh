#!/usr/bin/env bash
# =============================================================================
# AI Native OS · E1.2 红线检查单工具化 —— CI 安全门（Linux/macOS / WSL）
# =============================================================================
#
# ┌────────────────────────── 一页运行说明 ──────────────────────────┐
# │                                                                  │
# │ 用法:                                                             │
# │   ./ci/security-gate.sh                 # 全量安全门（默认）        │
# │   ./ci/security-gate.sh --json          # JSON 输出（CI 解析用）    │
# │   ./ci/security-gate.sh --quick         # 仅静态红线（跳过基线审计） │
# │   ./ci/security-gate.sh --self-check    # 环境自检（仅返回码）       │
# │   ./ci/security-gate.sh -h              # 本帮助                   │
# │                                                                  │
# │ 返回码约定:                                                       │
# │   0 = 通过（无 CRITICAL/HIGH 违规，可合入）                        │
# │   1 = 失败（存在 CRITICAL 或 HIGH 违规，阻断合并）                 │
# │   2 = 环境/工具错误（找不到 python3、关键输入缺失等）               │
# │                                                                  │
# │ 检查范围（对应红线）:                                              │
# │   A. E1.1 基线审计   tools/security_audit.py（权限/能力/deny 等）  │
# │   B. 权限清单静态审计 R1.1/R1.2/R1.3/R1.5（AndroidManifest_       │
# │      permissions.xml：protectionLevel/命名/说明）                  │
# │   C. privapp 白名单  R1.9（授予项已声明、无重复、命名规范）          │
# │   D. sepolicy 审计   R2.8/R2.9/R10.7/R1.10（neverallow/白名单式）  │
# │   E. service_contexts 对账 R2.8（服务↔域类型一一映射）             │
# │   （B~E 检查核实现共享于 ci/security-gate-checks.py，双平台一致）   │
# │                                                                  │
# │ 接入 CI（GitLab / GitHub Actions / Jenkins 任选其一）:             │
# │   - GitLab:  merge_request 流水线增加 job:                         │
# │       security-gate:                                              │
# │         script: ["./ci/security-gate.sh --json"]                  │
# │         rules:  [{ if: '$CI_PIPELINE_SOURCE == "merge_request_event"' }] │
# │   - GitHub Actions: 在 .github/workflows/ci.yml 加 step:          │
# │       - run: bash ci/security-gate.sh --json                       │
# │   - 本地 pre-commit:  ln -s ../../ci/security-gate.sh .git/hooks/pre-commit │
# │                                                                  │
# │ 报告落盘: reports/ci/security-gate-<时间戳>.json + .log             │
# │ 联动: A1.2/A1.4 权限收敛回归 —— 每次权限/privapp/sepolicy 变更都须过本门 │
# └──────────────────────────────────────────────────────────────────┘
#
# 版本: 1.0  |  日期: 2026-09-09  |  维护: 安全合规部（E1.2）
# =============================================================================

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_MODE="console"
QUICK=0
SELF_CHECK=0

for arg in "$@"; do
    case "$arg" in
        --json)   OUTPUT_MODE="json" ;;
        --quick)  QUICK=1 ;;
        --self-check) SELF_CHECK=1 ;;
        -h|--help) sed -n '/一页运行说明/,/维护: 安全合规部/p' "$0" | head -n 42; exit 0 ;;
        *) echo "未知参数: $arg （--json / --quick / --self-check / -h）" >&2; exit 2 ;;
    esac
done

# ---------- 环境探测 ----------
PY=""
for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
if [ -z "$PY" ]; then
    echo "[security-gate][FATAL] 未找到 python3/python，请先安装 Python 3.7+" >&2
    exit 2
fi

# ---------- 自检模式 ----------
if [ "$SELF_CHECK" -eq 1 ]; then
    MISSING=0
    for f in \
        "tools/security_audit.py" \
        "ci/security-gate-checks.py" \
        "overlay/frameworks/base/core/res/AndroidManifest_permissions.xml" \
        "permissions/privapp-permissions-genton.xml" \
        "sepolicy/genton.te" \
        "sepolicy/service_contexts"; do
        if [ ! -f "$PROJECT_ROOT/$f" ]; then
            echo "[security-gate][SELF-CHECK] 缺失: $f" >&2
            MISSING=1
        fi
    done
    if [ "$MISSING" -eq 1 ]; then
        echo "[security-gate] 自检失败：关键输入缺失（部分检查将降级为 INFO）" >&2
        exit 2
    fi
    echo "[security-gate] 自检通过：python=$PY, 关键输入齐备"
    exit 0
fi

# ---------- 主流程 ----------
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_DIR="$PROJECT_ROOT/reports/ci"
mkdir -p "$REPORT_DIR"
LOG_FILE="$REPORT_DIR/security-gate-$TIMESTAMP.log"
JSON_FILE="$REPORT_DIR/security-gate-$TIMESTAMP.json"

echo "=============================================="
echo " AI Native OS · E1.2 CI 安全门"
echo " 时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="

# 1) E1.1 基线审计（tools/security_audit.py）
echo ""
echo "[security-gate] A. E1.1 基线审计（security_audit.py）..."
AUDIT_RC=0
if [ "$QUICK" -eq 1 ]; then
    echo "    --quick 模式：跳过基线文档审计"
else
    "$PY" "$PROJECT_ROOT/tools/security_audit.py" --checklist E1.1 > "$LOG_FILE.audit" 2>&1 || AUDIT_RC=$?
    echo "    security_audit.py 返回码: $AUDIT_RC（0=通过 / 1=发现 CRITICAL|HIGH）"
    if [ "$AUDIT_RC" -ne 0 ]; then
        grep -E "严重|高:|检查结果|违规项" "$LOG_FILE.audit" | head -n 10 || true
    fi
fi

# 2) 附加静态审计 B~E（共享检查核 ci/security-gate-checks.py）
echo ""
echo "[security-gate] B~E 静态红线审计（security-gate-checks.py）..."
if [ "$OUTPUT_MODE" = "json" ]; then
    "$PY" "$PROJECT_ROOT/ci/security-gate-checks.py" "$PROJECT_ROOT" json > "$JSON_FILE" 2> "$LOG_FILE.err"
    EXTRA_RC=$?
else
    "$PY" "$PROJECT_ROOT/ci/security-gate-checks.py" "$PROJECT_ROOT" console 2>&1 | tee "$LOG_FILE"
    EXTRA_RC=${PIPESTATUS[0]}
fi

# 3) 汇总判定
FINAL_RC=0
if [ "$EXTRA_RC" -ne 0 ]; then
    FINAL_RC=1
fi
if [ "$QUICK" -eq 0 ] && [ "$AUDIT_RC" -ne 0 ]; then
    if [ "$AUDIT_RC" -eq 1 ]; then
        FINAL_RC=1
    elif [ "$AUDIT_RC" -ge 2 ]; then
        FINAL_RC=2
    fi
fi

echo ""
echo "=============================================="
echo " 安全门结果: $([ "$FINAL_RC" -eq 0 ] && echo '✅ 通过（0）' || echo '❌ 失败/异常' )"
echo " 报告: $REPORT_DIR/security-gate-$TIMESTAMP.*"
echo "=============================================="
exit "$FINAL_RC"
