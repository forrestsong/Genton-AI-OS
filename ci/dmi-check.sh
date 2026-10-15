#!/bin/bash
# CI 脚本：DMI 资产声明校验（F2.3 + A7.2 编译联动）- Linux/macOS 版本
# 用途：在构建流程中强制检查 DMI 声明合规性

set -e

echo "==============================================="
echo "DMI Asset Declaration Validation"
echo "==============================================="
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLS_DIR="$PROJECT_ROOT/tools"
EXAMPLES_DIR="$PROJECT_ROOT/docs/examples"
REPORTS_DIR="$PROJECT_ROOT/reports/ci"
BUILD_DIR="$PROJECT_ROOT/build"

# 创建目录
mkdir -p "$BUILD_DIR"
mkdir -p "$REPORTS_DIR"

echo "Step 1: Compiling DMI validator..."
javac -encoding UTF-8 -d "$BUILD_DIR" "$TOOLS_DIR/DmiValidator.java"

if [ $? -eq 0 ]; then
    echo -e "\033[32m✓ Validator compiled successfully\033[0m"
else
    echo -e "\033[31m✗ Validator compilation failed\033[0m"
    echo ""
    echo "提示：请确保已安装 Java 8+ 和依赖库："
    echo "  - SnakeYAML: snakeyaml-1.29.jar"
    echo "  - JSON: org.json-20210307.jar"
    echo ""
    echo "下载依赖："
    echo "  curl -L -o $TOOLS_DIR/snakeyaml.jar https://repo1.maven.org/maven2/org/yaml/snakeyaml/1.29/snakeyaml-1.29.jar"
    echo "  curl -L -o $TOOLS_DIR/json.jar https://repo1.maven.org/maven2/org/json/json/20210307/json-20210307.jar"
    exit 1
fi
echo ""

TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

echo "Step 2: Validating golden DMI example..."
TOTAL_TESTS=$((TOTAL_TESTS + 1))

java -cp "$BUILD_DIR:$TOOLS_DIR/snakeyaml.jar:$TOOLS_DIR/json.jar" \
    com.genton.tools.DmiValidator \
    "$EXAMPLES_DIR/genton-dmi-weather-example.yaml" \
    --report "$REPORTS_DIR/dmi-weather-validation.md"

if [ $? -eq 0 ]; then
    echo -e "\033[32m✓ Golden DMI validation: PASS\033[0m"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo -e "\033[31m✗ Golden DMI validation: FAIL\033[0m"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi
echo ""

# 扫描 packages/apps/*/assets/ 中的 DMI 文件
echo "Step 3: Scanning application DMI assets..."
if [ -d "$PROJECT_ROOT/packages/apps" ]; then
    while IFS= read -r -d '' DMI_FILE; do
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
        echo "校验: $DMI_FILE"
        
        java -cp "$BUILD_DIR:$TOOLS_DIR/snakeyaml.jar:$TOOLS_DIR/json.jar" \
            com.genton.tools.DmiValidator "$DMI_FILE"
        
        if [ $? -eq 0 ]; then
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
        echo ""
    done < <(find "$PROJECT_ROOT/packages/apps" -type f \( -name "genton-dmi*.yaml" -o -name "genton-dmi*.json" \) -print0)
fi

# 扫描 overlay/frameworks/base/res/genton/ 中的系统 DMI
echo "Step 4: Scanning system DMI assets..."
if [ -d "$PROJECT_ROOT/overlay/frameworks/base/res/genton" ]; then
    while IFS= read -r -d '' DMI_FILE; do
        TOTAL_TESTS=$((TOTAL_TESTS + 1))
        echo "校验: $DMI_FILE"
        
        java -cp "$BUILD_DIR:$TOOLS_DIR/snakeyaml.jar:$TOOLS_DIR/json.jar" \
            com.genton.tools.DmiValidator "$DMI_FILE"
        
        if [ $? -eq 0 ]; then
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
        echo ""
    done < <(find "$PROJECT_ROOT/overlay/frameworks/base/res/genton" -type f \( -name "genton-dmi*.yaml" -o -name "genton-dmi*.json" \) -print0)
fi

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
    echo -e "\033[31m✗ DMI validation FAILED - 构建应该终止\033[0m"
    exit 1
else
    echo -e "\033[32m✓ All DMI validations PASSED\033[0m"
    exit 0
fi
