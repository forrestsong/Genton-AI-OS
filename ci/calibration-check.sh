#!/bin/bash
# CI 脚本：机型校准库校验（F4.1）- Linux/macOS 版本
# 用途：在构建流程中强制检查机型校准档案合规性、DMI 可用性登记一致性

set -e

echo "==============================================="
echo "Machine Calibration Profile Validation"
echo "==============================================="
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLS_DIR="$PROJECT_ROOT/tools"
CALIBRATION_DIR="$PROJECT_ROOT/vendor/ainative/config/calibration"
REPORTS_DIR="$PROJECT_ROOT/reports/ci"

# 创建目录
mkdir -p "$REPORTS_DIR"

# 选择 Python 解释器
PYTHON_BIN=""
for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
        PYTHON_BIN="$candidate"
        break
    fi
done

if [ -z "$PYTHON_BIN" ]; then
    echo -e "\033[31m✗ 未找到 Python 解释器（需要 Python 3.8+）\033[0m"
    exit 1
fi

echo "Python: $($PYTHON_BIN --version 2>&1)"
echo ""

TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

echo "Step 1: Validating machine calibration profiles..."
TOTAL_TESTS=$((TOTAL_TESTS + 1))

if "$PYTHON_BIN" "$TOOLS_DIR/calibration_collect.py" validate \
        --dir "$CALIBRATION_DIR"; then
    echo -e "\033[32m✓ Calibration profile validation: PASS\033[0m"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo -e "\033[31m✗ Calibration profile validation: FAIL\033[0m"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi
echo ""

echo "Step 2: Exporting calibration matrix..."
TOTAL_TESTS=$((TOTAL_TESTS + 1))

if "$PYTHON_BIN" "$TOOLS_DIR/calibration_collect.py" matrix \
        --dir "$CALIBRATION_DIR" \
        --out "$REPORTS_DIR/F4.1_机型校准矩阵.md"; then
    echo -e "\033[32m✓ Calibration matrix export: PASS\033[0m"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo -e "\033[31m✗ Calibration matrix export: FAIL\033[0m"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi
echo ""

echo "Step 3: Validating calibration regression suites (F4.2)..."
TOTAL_TESTS=$((TOTAL_TESTS + 1))

if "$PYTHON_BIN" "$TOOLS_DIR/calibration_collect.py" regression-validate \
        --dir "$CALIBRATION_DIR"; then
    echo -e "\033[32m✓ Calibration regression validation: PASS\033[0m"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo -e "\033[31m✗ Calibration regression validation: FAIL\033[0m"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi
echo ""

echo "Step 4: Exporting calibration regression library (F4.2)..."
TOTAL_TESTS=$((TOTAL_TESTS + 1))

if "$PYTHON_BIN" "$TOOLS_DIR/calibration_collect.py" regression-matrix \
        --dir "$CALIBRATION_DIR" \
        --out "$REPORTS_DIR/F4.2_校准回归样本库.md"; then
    echo -e "\033[32m✓ Calibration regression export: PASS\033[0m"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo -e "\033[31m✗ Calibration regression export: FAIL\033[0m"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi
echo ""

echo "==============================================="
echo "CI Summary"
echo "==============================================="
echo "Total tests: $TOTAL_TESTS"
echo "Passed: $PASSED_TESTS"
echo "Failed: $FAILED_TESTS"
echo ""
echo "Reports generated in: $REPORTS_DIR"
echo ""

if [ $FAILED_TESTS -gt 0 ]; then
    echo -e "\033[31m✗ Calibration validation FAILED - 构建应该终止\033[0m"
    exit 1
else
    echo -e "\033[32m✓ All calibration validations PASSED\033[0m"
    exit 0
fi
