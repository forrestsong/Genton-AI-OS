@echo off
rem ============================================================
rem AI Native OS CI pipeline orchestrator (H1.1)
rem ASCII-only messages; thin wrapper around python steps.
rem Exit code: 0 all pass, 1 some fail, 2 env error.
rem ============================================================
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set REPORTS_DIR=%PROJECT_ROOT%\reports\ci
if not exist "%REPORTS_DIR%" mkdir "%REPORTS_DIR%"

where python >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] python not found
    exit /b 2
)
set PY=python

rem Force UTF-8 output for python subprocesses (GBK console compatibility)
set PYTHONIOENCODING=utf-8

set PASS_COUNT=0
set FAIL_COUNT=0

echo ============================================================
echo AI Native OS - CI main pipeline (H1.1)
echo Project root: %PROJECT_ROOT%
echo ============================================================

echo.
echo [Step 1] AIDL / Soong compile placeholder
set AIDL_DIR=%PROJECT_ROOT%\overlay\frameworks\base\core\java\com\ainative
set AIDL_COUNT=0
if exist "%AIDL_DIR%" (
    for /r "%AIDL_DIR%" %%f in (*.aidl) do set /a AIDL_COUNT+=1
)
if %AIDL_COUNT% GTR 0 (
    echo   Found %AIDL_COUNT% AIDL files. Full Soong build requires Android env.
) else (
    echo   No AIDL files found. Placeholder PASS.
)
set /a PASS_COUNT+=1
echo   [PASS] AIDL/Soong compile placeholder

echo.
echo [Step 2] Schema / contract static validation
set STEP2_EXIT=0
"%PY%" "%PROJECT_ROOT%\tools\validate_action_registry.py" "%PROJECT_ROOT%\vendor\ainative\config\action_registry.json" "%PROJECT_ROOT%\vendor\ainative\config\action-registry.schema.json" > "%REPORTS_DIR%\action-registry-check.log" 2>&1
if !ERRORLEVEL! NEQ 0 (
    echo   [FAIL] action registry validation failed. See action-registry-check.log
    set STEP2_EXIT=1
) else (
    echo   [PASS] action registry validation
)

if exist "%PROJECT_ROOT%\tools\test_contract_golden.py" (
    "%PY%" "%PROJECT_ROOT%\tools\test_contract_golden.py" > "%REPORTS_DIR%\contract-golden.log" 2>&1
    if !ERRORLEVEL! NEQ 0 (
        echo   [FAIL] contract golden regression failed. See contract-golden.log
        set STEP2_EXIT=1
    ) else (
        echo   [PASS] contract golden regression
    )
)

if %STEP2_EXIT% EQU 0 (
    set /a PASS_COUNT+=1
) else (
    set /a FAIL_COUNT+=1
)

echo.
echo [Step 3] Policy DSL C1-C8 compile check
"%PY%" "%PROJECT_ROOT%\tools\dsl_c1_c8_check.py" "%PROJECT_ROOT%\vendor\ainative\config\policy_baseline.dsl" --report "%REPORTS_DIR%\dsl-c1c8-check.md" > "%REPORTS_DIR%\dsl-c1c8.log" 2>&1
set STEP3_EXIT=%ERRORLEVEL%
if %STEP3_EXIT% EQU 0 (
    echo   [PASS] DSL C1-C8 check
    set /a PASS_COUNT+=1
) else (
    echo   [FAIL] DSL C1-C8 check failed. See dsl-c1c8.log
    set /a FAIL_COUNT+=1
)

echo.
echo [Step 4] sepolicy / permission audit
if "%1"=="--skip-audit" (
    echo   [SKIP] security audit skipped
) else (
    "%PY%" "%PROJECT_ROOT%\tools\security_audit.py" --all > "%REPORTS_DIR%\security-audit.log" 2>&1
    set STEP4_EXIT=%ERRORLEVEL%
    if !STEP4_EXIT! EQU 0 (
        echo   [PASS] security audit
        set /a PASS_COUNT+=1
    ) else (
        echo   [FAIL] security audit violations. See security-audit.log
        set /a FAIL_COUNT+=1
    )
)

echo.
echo ============================================================
echo CI summary (H1.1)
echo ============================================================
echo PASS: %PASS_COUNT%   FAIL: %FAIL_COUNT%
if %FAIL_COUNT% GTR 0 (
    echo [FAIL] pipeline has %FAIL_COUNT% failing steps
    exit /b 1
)
echo [PASS] pipeline all green
exit /b 0
