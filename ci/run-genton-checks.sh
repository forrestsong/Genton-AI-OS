#!/bin/bash
# ============================================================
# AI Native OS - CI 主流水线编排（run-genton-checks）
#
# 任务编号：H1.1（质量工程部 · CI 基建）
# 验收项  ：AIDL/Soong 编译占位 + schema/契约静态校验 + DSL C1-C8
#           编译检查 + sepolicy/权限审计脚本，四合一流水线，各步骤
#           独立退出码，最终聚合门禁。
#
# 用法：
#   ./ci/run-genton-checks.sh                # 全部步骤
#   ./ci/run-genton-checks.sh --quick        # 跳过 AIDL/Soong 占位
#   ./ci/run-genton-checks.sh --skip-audit   # 跳过 sepolicy/权限审计
#
# 退出码：
#   0 = 全部通过；1 = 存在失败步骤；
#   2 = 环境错误（python3 缺失等）
# ============================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPORTS_DIR="$PROJECT_ROOT/reports/ci"
mkdir -p "$REPORTS_DIR"

QUICK=0
SKIP_AUDIT=0
for arg in "$@"; do
    case "$arg" in
        --quick) QUICK=1 ;;
        --skip-audit) SKIP_AUDIT=1 ;;
    esac
done

# 颜色
GREEN='\033[32m'; RED='\033[31m'; YELLOW='\033[33m'; NC='\033[0m'

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

record() { # record <步骤名> <退出码> <跳过?>
    local name="$1" code="$2" skipped="${3:-0}"
    if [ "$skipped" = "1" ]; then
        SKIP_COUNT=$((SKIP_COUNT + 1))
        printf "${YELLOW}  [SKIP] %s${NC}\n" "$name"
    elif [ "$code" -eq 0 ]; then
        PASS_COUNT=$((PASS_COUNT + 1))
        printf "${GREEN}  [PASS] %s${NC}\n" "$name"
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        printf "${RED}  [FAIL] %s (exit=%s)${NC}\n" "$name" "$code"
    fi
}

echo "============================================================"
echo " AI Native OS - CI 主流水线 (H1.1)"
echo " 时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo " 根目录: $PROJECT_ROOT"
echo "============================================================"

# ------------------------------------------------------------
# Step 0: 环境检查
# ------------------------------------------------------------
echo ""
echo "[Step 0] 环境检查 (python3)"
if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    echo "❌ 找不到 python3/python"
    exit 2
fi
PY="python3"
command -v python3 >/dev/null 2>&1 || PY="python"
echo "✓ Python: $($PY --version 2>&1)"

# Windows 控制台 GBK 兼容：所有 Python 子进程强制 UTF-8 输出
export PYTHONIOENCODING=utf-8

# ------------------------------------------------------------
# Step 1: AIDL / Soong 编译占位
# ------------------------------------------------------------
echo ""
echo "[Step 1] AIDL / Soong 编译（占位）"
AIDL_DIR="$PROJECT_ROOT/overlay/frameworks/base/core/java/com/genton"
AIDL_COUNT=$(find "$AIDL_DIR" -name "*.aidl" 2>/dev/null | wc -l)
if [ "$QUICK" = "1" ]; then
    echo "  --quick 模式：跳过 AIDL/Soong 编译占位"
    record "AIDL/Soong 编译占位" 0 1
elif [ "$AIDL_COUNT" -gt 0 ]; then
    echo "  发现 $AIDL_COUNT 个 AIDL 接口文件"
    echo "  ⚠ 完整 Soong 编译需要 Android 构建环境（source build/envsetup.sh && m）"
    echo "    本步骤为占位：AIDL 文件存在性 = PASS，真实编译接入点见 ci-genton-pipeline.md"
    record "AIDL/Soong 编译占位" 0 0
else
    echo "  ⚠ 未发现 AIDL 文件（$AIDL_DIR），按占位通过"
    record "AIDL/Soong 编译占位" 0 0
fi

# ------------------------------------------------------------
# Step 2: Schema / 契约静态校验
# ------------------------------------------------------------
echo ""
echo "[Step 2] Schema / 契约静态校验"
STEP2_EXIT=0

# 2a. 动作注册表校验（JSON Schema + 执行器存在性 + 能力映射）
python3 "$PROJECT_ROOT/tools/validate_action_registry.py" \
    "$PROJECT_ROOT/vendor/genton/config/action_registry.json" \
    "$PROJECT_ROOT/vendor/genton/config/action-registry.schema.json" \
    > "$REPORTS_DIR/action-registry-check.log" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
    echo "  ✗ 动作注册表校验失败（见 $REPORTS_DIR/action-registry-check.log）"
    STEP2_EXIT=1
else
    echo "  ✓ 动作注册表校验通过"
fi

# 2b. 动作注册表 schema 对照校验（registry/ 目录版本）
if [ -f "$PROJECT_ROOT/registry/action-registry.schema.json" ] && \
   [ -f "$PROJECT_ROOT/registry/action-registry.v1.json" ]; then
    python3 "$PROJECT_ROOT/tools/validate_registry_with_schema.py" \
        > "$REPORTS_DIR/registry-schema-check.log" 2>&1
    RC=$?
    if [ $RC -ne 0 ]; then
        echo "  ✗ registry/ schema 对照校验失败（见日志）"
        STEP2_EXIT=1
    else
        echo "  ✓ registry/ schema 对照校验通过"
    fi
else
    echo "  ⚠ registry/ 目录无 schema 或注册表（跳过对照校验）"
fi

# 2c. 契约收敛校验（A1.2 契约黄金样例，若存在）
if [ -f "$PROJECT_ROOT/tools/test_contract_golden.py" ]; then
    python3 "$PROJECT_ROOT/tools/test_contract_golden.py" \
        > "$REPORTS_DIR/contract-golden.log" 2>&1
    RC=$?
    if [ $RC -ne 0 ]; then
        echo "  ✗ 契约黄金样例回归失败（见 $REPORTS_DIR/contract-golden.log）"
        STEP2_EXIT=1
    else
        echo "  ✓ 契约黄金样例回归通过"
    fi
fi

record "Schema/契约静态校验" $STEP2_EXIT

# ------------------------------------------------------------
# Step 3: DSL C1-C8 编译检查
# ------------------------------------------------------------
echo ""
echo "[Step 3] 策略 DSL C1-C8 编译检查"
python3 "$PROJECT_ROOT/tools/dsl_c1_c8_check.py" \
    "$PROJECT_ROOT/vendor/genton/config/policy_baseline.dsl" \
    --report "$REPORTS_DIR/dsl-c1c8-check.md" \
    > "$REPORTS_DIR/dsl-c1c8.log" 2>&1
RC=$?
if [ $RC -ne 0 ]; then
    echo "  ✗ DSL C1-C8 检查失败（见 $REPORTS_DIR/dsl-c1c8.log）"
else
    echo "  ✓ DSL C1-C8 检查通过"
fi
record "DSL C1-C8 编译检查" $RC

# ------------------------------------------------------------
# Step 4: sepolicy / 权限审计脚本
# ------------------------------------------------------------
echo ""
echo "[Step 4] sepolicy / 权限审计"
if [ "$SKIP_AUDIT" = "1" ]; then
    echo "  --skip-audit：跳过"
    record "sepolicy/权限审计" 0 1
else
    python3 "$PROJECT_ROOT/tools/security_audit.py" --all \
        > "$REPORTS_DIR/security-audit.log" 2>&1
    RC=$?
    if [ $RC -ne 0 ]; then
        echo "  ✗ 安全审计存在违规（见 $REPORTS_DIR/security-audit.log）"
    else
        echo "  ✓ 安全审计通过"
    fi
    record "sepolicy/权限审计" $RC
fi

# ------------------------------------------------------------
# 汇总
# ------------------------------------------------------------
echo ""
echo "============================================================"
echo " CI 汇总 (H1.1)"
echo "============================================================"
echo "  PASS: $PASS_COUNT   FAIL: $FAIL_COUNT   SKIP: $SKIP_COUNT"
if [ "$FAIL_COUNT" -gt 0 ]; then
    printf "${RED}✗ 流水线未通过，存在 $FAIL_COUNT 个失败步骤${NC}\n"
    exit 1
fi
printf "${GREEN}✓ 流水线全部通过${NC}\n"
exit 0
