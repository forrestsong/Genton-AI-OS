@echo off
REM 动作注册表 CI 校验脚本（Windows）
REM 用于构建流程中的强制检查

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

set "REGISTRY_FILE=%PROJECT_ROOT%\registry\action-registry.v1.json"
set "VALIDATOR_JAVA=%PROJECT_ROOT%\tools\ActionRegistryValidator.java"
set "SCHEMA_FILE=%PROJECT_ROOT%\registry\action-registry.schema.json"

echo ========================================
echo 动作注册表 CI 校验
echo ========================================
echo.
echo 注册表文件: %REGISTRY_FILE%
echo 校验器: %VALIDATOR_JAVA%
echo.

REM 检查文件存在
if not exist "%REGISTRY_FILE%" (
    echo ❌ 错误: 找不到注册表文件: %REGISTRY_FILE%
    exit /b 1
)

if not exist "%VALIDATOR_JAVA%" (
    echo ❌ 错误: 找不到校验器: %VALIDATOR_JAVA%
    exit /b 1
)

REM 编译校验器
echo 编译校验器...
set "TEMP_DIR=%TEMP%\action-registry-validator-%RANDOM%"
mkdir "%TEMP_DIR%" 2>nul

javac -d "%TEMP_DIR%" "%VALIDATOR_JAVA%" 2>nul
if errorlevel 1 (
    echo ❌ 错误: 编译失败
    rmdir /s /q "%TEMP_DIR%" 2>nul
    exit /b 1
)

echo ✅ 编译成功
echo.

REM 运行校验
echo 运行校验...
java -cp "%TEMP_DIR%" com.android.server.genton.ActionRegistryValidator "%REGISTRY_FILE%"

set EXIT_CODE=%errorlevel%

REM 清理临时目录
rmdir /s /q "%TEMP_DIR%" 2>nul

if %EXIT_CODE% equ 0 (
    echo.
    echo ✅ 动作注册表校验通过
    exit /b 0
) else (
    echo.
    echo ❌ 动作注册表校验失败
    exit /b 1
)
