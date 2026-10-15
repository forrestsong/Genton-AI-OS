#!/bin/bash
# CI 脚本：策略 DSL 编译检查（A7.2）

set -e

echo "==============================================="
echo "Policy DSL Compilation & Conflict Matrix Check"
echo "==============================================="
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

JAVA_COMPILER="javac"
JAVA_RUNTIME="java"
SRC_DIR="$PROJECT_ROOT/overlay/frameworks/base/services/core/java/com/android/server/genton"
TESTS_DIR="$PROJECT_ROOT/tests"
BUILD_DIR="$PROJECT_ROOT/build"
REPORTS_DIR="$PROJECT_ROOT/reports/ci"

# 创建构建目录
mkdir -p "$BUILD_DIR"
mkdir -p "$REPORTS_DIR"

echo "Step 1: Compiling DSL compiler..."
$JAVA_COMPILER -d "$BUILD_DIR" \
    "$SRC_DIR/PolicyDslCompiler.java" \
    "$SRC_DIR/PolicyDslCiTool.java" \
    "$SRC_DIR/ConflictMatrixReporter.java"

if [ $? -eq 0 ]; then
    echo "✓ Compiler built successfully"
else
    echo "✗ Compiler build failed"
    exit 1
fi
echo ""

echo "Step 2: Testing golden policy pack..."
$JAVA_RUNTIME -cp "$BUILD_DIR" com.android.server.genton.PolicyDslCiTool \
    "$TESTS_DIR/policy-pack-golden.json" \
    "platform"

GOLDEN_EXIT_CODE=$?
echo ""

echo "Step 3: Generating conflict matrix report (golden)..."
$JAVA_RUNTIME -cp "$BUILD_DIR" com.android.server.genton.ConflictMatrixReporter \
    "$TESTS_DIR/policy-pack-golden.json" \
    "platform" \
    "$REPORTS_DIR/conflict-matrix-golden.md"
echo "✓ Report saved: $REPORTS_DIR/conflict-matrix-golden.md"
echo ""

echo "Step 4: Testing conflict policy pack (should fail)..."
$JAVA_RUNTIME -cp "$BUILD_DIR" com.android.server.genton.PolicyDslCiTool \
    "$TESTS_DIR/policy-pack-conflict.json" \
    "platform" || true

echo ""
echo "Step 5: Generating conflict matrix report (conflict test)..."
$JAVA_RUNTIME -cp "$BUILD_DIR" com.android.server.genton.ConflictMatrixReporter \
    "$TESTS_DIR/policy-pack-conflict.json" \
    "platform" \
    "$REPORTS_DIR/conflict-matrix-conflict.md"
echo "✓ Report saved: $REPORTS_DIR/conflict-matrix-conflict.md"
echo ""

echo "==============================================="
echo "CI Summary"
echo "==============================================="
if [ $GOLDEN_EXIT_CODE -eq 0 ]; then
    echo "✓ Golden policy pack: PASS"
else
    echo "✗ Golden policy pack: FAIL"
fi
echo ""
echo "Reports generated in: $REPORTS_DIR"
echo ""

exit $GOLDEN_EXIT_CODE
