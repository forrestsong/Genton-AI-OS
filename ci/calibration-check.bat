@echo off
REM CI 脚本：机型校准库校验（F4.1 / F4.2）- Windows 版本
REM 用途：在构建流程中强制检查机型校准档案合规性、DMI 可用性登记一致性、校准回归样本集

setlocal enabledelayedexpansion

echo ===============================================
echo Machine Calibration Profile Validation
echo ===============================================
echo.

set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set TOOLS_DIR=%PROJECT_ROOT%\tools
set CALIBRATION_DIR=%PROJECT_ROOT%\vendor\ainative\config\calibration
set REPORTS_DIR=%PROJECT_ROOT%\reports\ci

REM 创建目录
if not exist "%REPORTS_DIR%" mkdir "%REPORTS_DIR%"

REM 选择 Python 解释器
set PYTHON_BIN=
for %%P in (python py) do (
    if "!PYTHON_BIN!"=="" (
        %%P --version >nul 2>&1
        if !ERRORLEVEL! EQU 0 set PYTHON_BIN=%%P
    )
)

if "!PYTHON_BIN!"=="" (
    echo [31m✗ 未找到 Python 解释器（需要 Python 3.8+）[0m
    exit /b 1
)

echo Python: !PYTHON_BIN!
echo.

set TOTAL_TESTS=0
set PASSED_TESTS=0
set FAILED_TESTS=0

echo Step 1: Validating machine calibration profiles...
set /a TOTAL_TESTS+=1

!PYTHON_BIN! "%TOOLS_DIR%\calibration_collect.py" validate --dir "%CALIBRATION_DIR%"

if !ERRORLEVEL! EQU 0 (
    echo [32m✓ Calibration profile validation: PASS[0m
    set /a PASSED_TESTS+=1
) else (
    echo [31m✗ Calibration profile validation: FAIL[0m
    set /a FAILED_TESTS+=1
)
echo.

echo Step 2: Exporting calibration matrix...
set /a TOTAL_TESTS+=1

!PYTHON_BIN! "%TOOLS_DIR%\calibration_collect.py" matrix --dir "%CALIBRATION_DIR%" --out "%REPORTS_DIR%\F4.1_机型校准矩阵.md"

if !ERRORLEVEL! EQU 0 (
    echo [32m✓ Calibration matrix export: PASS[0m
    set /a PASSED_TESTS+=1
) else (
    echo [31m✗ Calibration matrix export: FAIL[0m
    set /a FAILED_TESTS+=1
)
echo.

echo Step 3: Validating calibration regression suites (F4.2)...
set /a TOTAL_TESTS+=1

!PYTHON_BIN! "%TOOLS_DIR%\calibration_collect.py" regression-validate --dir "%CALIBRATION_DIR%"

if !ERRORLEVEL! EQU 0 (
    echo [32m✓ Calibration regression validation: PASS[0m
    set /a PASSED_TESTS+=1
) else (
    echo [31m✗ Calibration regression validation: FAIL[0m
    set /a FAILED_TESTS+=1
)
echo.

echo Step 4: Exporting calibration regression library (F4.2)...
set /a TOTAL_TESTS+=1

!PYTHON_BIN! "%TOOLS_DIR%\calibration_collect.py" regression-matrix --dir "%CALIBRATION_DIR%" --out "%REPORTS_DIR%\F4.2_校准回归样本库.md"

if !ERRORLEVEL! EQU 0 (
    echo [32m✓ Calibration regression export: PASS[0m
    set /a PASSED_TESTS+=1
) else (
    echo [31m✗ Calibration regression export: FAIL[0m
    set /a FAILED_TESTS+=1
)
echo.

echo ===============================================
echo CI Summary
echo ===============================================
echo Total tests: %TOTAL_TESTS%
echo Passed: %PASSED_TESTS%
echo Failed: %FAILED_TESTS%
echo.
echo Reports generated in: %REPORTS_DIR%
echo.

if %FAILED_TESTS% GTR 0 (
    echo [31m✗ Calibration validation FAILED - 构建应该终止[0m
    exit /b 1
) else (
    echo [32m✓ All calibration validations PASSED[0m
    exit /b 0
)
