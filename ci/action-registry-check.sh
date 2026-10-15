#!/bin/bash
# 动作注册表 CI 校验脚本（Linux/macOS）
# 用于构建流程中的强制检查

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

REGISTRY_FILE="$PROJECT_ROOT/registry/action-registry.v1.json"
VALIDATOR_JAVA="$PROJECT_ROOT/tools/ActionRegistryValidator.java"
SCHEMA_FILE="$PROJECT_ROOT/registry/action-registry.schema.json"

echo "========================================"
echo "动作注册表 CI 校验"
echo "========================================"
echo ""
echo "注册表文件: $REGISTRY_FILE"
echo "校验器: $VALIDATOR_JAVA"
echo ""

# 检查文件存在
if [ ! -f "$REGISTRY_FILE" ]; then
    echo "❌ 错误: 找不到注册表文件: $REGISTRY_FILE"
    exit 1
fi

if [ ! -f "$VALIDATOR_JAVA" ]; then
    echo "❌ 错误: 找不到校验器: $VALIDATOR_JAVA"
    exit 1
fi

# 编译校验器（需要 Android SDK 环境）
echo "编译校验器..."
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# 简化编译：使用独立 JSON 库
javac -d "$TEMP_DIR" \
    -cp "$ANDROID_HOME/platforms/android-34/android.jar:$ANDROID_HOME/tools/lib/org.json.jar" \
    "$VALIDATOR_JAVA" 2>/dev/null || {
    echo "⚠️  警告: 无 Android SDK，尝试使用系统 Java 编译（可能缺少依赖）"
    javac -d "$TEMP_DIR" "$VALIDATOR_JAVA" || {
        echo "❌ 错误: 编译失败"
        exit 1
    }
}

echo "✅ 编译成功"
echo ""

# 运行校验
echo "运行校验..."
java -cp "$TEMP_DIR" com.android.server.ainative.ActionRegistryValidator "$REGISTRY_FILE"

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo "✅ 动作注册表校验通过"
    exit 0
else
    echo ""
    echo "❌ 动作注册表校验失败"
    exit 1
fi
