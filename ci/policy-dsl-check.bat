@echo off
REM CI 脚本：策略 DSL 编译检查（A7.2）- Windows 版本

setlocal enabledelayedexpansion

echo ===============================================
echo Policy DSL Compilation ^& Conflict Matrix Check
echo ===============================================
echo.

set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set SRC_DIR=%PROJECT_ROOT%\overlay\frameworks\base\services\core\java\com\android\server\genton
set TESTS_DIR=%PROJECT_ROOT%\tests
set BUILD_DIR=%PROJECT_ROOT%\build
set REPORTS_DIR=%PROJECT_ROOT%\reports\ci

REM 创建构建目录
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
if not exist "%REPORTS_DIR%" mkdir "%REPORTS_DIR%"

echo Step 1: Compiling DSL compiler...
javac -d "%BUILD_DIR%" ^
    "%SRC_DIR%\PolicyDslCompiler.java" ^
    "%SRC_DIR%\PolicyDslCiTool.java" ^
    "%SRC_DIR%\ConflictMatrixReporter.java"

if %ERRORLEVEL% EQU 0 (
    echo [32m✓ Compiler built successfully[0m
) else (
    echo [31m✗ Compiler build failed[0m
    exit /b 1
)
echo.

echo Step 2: Testing golden policy pack...
java -cp "%BUILD_DIR%" com.android.server.genton.PolicyDslCiTool ^
    "%TESTS_DIR%\policy-pack-golden.json" ^
    platform

set GOLDEN_EXIT_CODE=%ERRORLEVEL%
echo.

echo Step 3: Generating conflict matrix report (golden)...
java -cp "%BUILD_DIR%" com.android.server.genton.ConflictMatrixReporter ^
    "%TESTS_DIR%\policy-pack-golden.json" ^
    platform ^
    "%REPORTS_DIR%\conflict-matrix-golden.md"
echo [32m✓ Report saved: %REPORTS_DIR%\conflict-matrix-golden.md[0m
echo.

echo Step 4: Testing conflict policy pack (should fail)...
java -cp "%BUILD_DIR%" com.android.server.genton.PolicyDslCiTool ^
    "%TESTS_DIR%\policy-pack-conflict.json" ^
    platform
echo.

echo Step 5: Generating conflict matrix report (conflict test)...
java -cp "%BUILD_DIR%" com.android.server.genton.ConflictMatrixReporter ^
    "%TESTS_DIR%\policy-pack-conflict.json" ^
    platform ^
    "%REPORTS_DIR%\conflict-matrix-conflict.md"
echo [32m✓ Report saved: %REPORTS_DIR%\conflict-matrix-conflict.md[0m
echo.

echo ===============================================
echo CI Summary
echo ===============================================
if %GOLDEN_EXIT_CODE% EQU 0 (
    echo [32m✓ Golden policy pack: PASS[0m
) else (
    echo [31m✗ Golden policy pack: FAIL[0m
)
echo.
echo Reports generated in: %REPORTS_DIR%
echo.

exit /b %GOLDEN_EXIT_CODE%
