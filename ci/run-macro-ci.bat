@echo off
REM ============================================================
REM AI Native OS - Macro Asset CI Validation Pipeline (F1.2) - Windows
REM ============================================================
REM Features: golden-sample replay / auto-disable with A8.1
REM   feedback (call placeholder) / version baseline management /
REM   multi-format reports
REM Deps: python 3.8+ (JSON/state/baseline). Device mode needs adb.
REM Aligned with: ci/macro-ci-config.yaml (thresholds),
REM   docs/macro-testing-specification.md (golden format),
REM   reports/A8.1_completion_report.md (setAssetStatus / staged-enable)
REM
REM Usage:
REM   run-macro-ci.bat [--offline-validate] [--update-baseline]
REM                    [--device <serial>] [--golden-dir <dir>]
REM                    [--out-dir <dir>] [--help]
REM
REM Exit codes:
REM   0   PASS
REM   1   TEST FAIL or baseline drift (needs review)
REM   2   AUTO-DISABLED: >=1 macro disabled, A8.1 feedback queued
REM   3   ENV ERROR (no device / no adb / replay infra unavailable)
REM   4   CONFIG or schema error
REM   130 INTERRUPTED
REM ============================================================

setlocal enabledelayedexpansion
set PYTHONIOENCODING=utf-8

set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set TOOLS_DIR=%PROJECT_ROOT%\tools
set CONFIG_FILE=%PROJECT_ROOT%\ci\macro-ci-config.yaml
set GOLDEN_DIR=%PROJECT_ROOT%\tests\macro\golden
set OUT_DIR=%PROJECT_ROOT%\reports\ci\macro
set STATE_DIR=%PROJECT_ROOT%\ci\state
set STATE_FILE=%STATE_DIR%\macro-ci-state.json
set QUEUE_FILE=%STATE_DIR%\auto-disable-queue.json
set BASELINE_FILE=%STATE_DIR%\macro-baseline.json
set PYTHON=python
set ADB=adb
set DEVICE=
set OFFLINE=0
set UPDATE_BASELINE=0
set FINAL_EXIT=0

REM ---------- arg parsing ----------
:argloop
if "%~1"=="" goto argsdone
if /i "%~1"=="--offline-validate" set OFFLINE=1
if /i "%~1"=="--update-baseline" set UPDATE_BASELINE=1
if /i "%~1"=="--help" goto usage
if /i "%~1"=="--device" (
    set DEVICE=%~2
    shift
)
if /i "%~1"=="--golden-dir" (
    set GOLDEN_DIR=%~2
    shift
)
if /i "%~1"=="--out-dir" (
    set OUT_DIR=%~2
    shift
)
shift
goto argloop
:argsdone

echo ===============================================
echo AI Native OS - Macro Asset CI (F1.2)
echo ===============================================
echo Project root : %PROJECT_ROOT%
echo Golden dir   : %GOLDEN_DIR%
echo Output dir   : %OUT_DIR%
echo State dir    : %STATE_DIR%
if "%OFFLINE%"=="1" echo Mode         : offline-validate (static checks + baseline dry-run, no device)
if "%DEVICE%"=="" (echo Device       : auto - adb default) else (echo Device       : %DEVICE%)
echo.

REM ---------- python check ----------
"%PYTHON%" -c "import sys" >nul 2>nul
if errorlevel 1 (
    echo [CONFIG-ERROR] python not found: %PYTHON%. Macro CI needs python 3.8+.
    exit /b 4
)

REM ---------- dirs ----------
if not exist "%STATE_DIR%" mkdir "%STATE_DIR%"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

REM ===============================================
REM Stage 1/5: golden schema static validation
REM ===============================================
echo.
echo [Stage 1/5] Golden sample static validation...
"%PYTHON%" "%TOOLS_DIR%\macro_ci_core.py" validate --golden-dir "%GOLDEN_DIR%" --config "%CONFIG_FILE%"
set STAGE_EXIT=!ERRORLEVEL!
if !STAGE_EXIT! GTR !FINAL_EXIT! set FINAL_EXIT=!STAGE_EXIT!
if !STAGE_EXIT! NEQ 0 (
    echo [Stage 1] Static validation failed, pipeline aborted.
    goto done
)

REM ===============================================
REM Stage 2/5: device check (skipped in offline mode)
REM ===============================================
echo.
echo [Stage 2/5] Device check...
if "%OFFLINE%"=="1" (
    echo  offline mode: skipping device check and replay.
    goto stage3
)
where adb >nul 2>nul
if errorlevel 1 (
    echo [ENV-ERROR] adb not found. Add ANDROID_HOME\platform-tools to PATH.
    set FINAL_EXIT=3
    goto stage3
)
"%ADB%" devices >nul 2>nul
if errorlevel 1 (
    echo [ENV-ERROR] adb is not working.
    set FINAL_EXIT=3
    goto stage3
)

REM ===============================================
REM Stage 3/5: golden replay
REM ===============================================
:stage3
echo.
echo [Stage 3/5] Golden replay...
if "%OFFLINE%"=="1" (
    "%PYTHON%" "%TOOLS_DIR%\macro_ci_core.py" replay --offline ^
        --golden-dir "%GOLDEN_DIR%" --config "%CONFIG_FILE%" --out-dir "%OUT_DIR%"
) else (
    if "%DEVICE%"=="" (
        "%PYTHON%" "%TOOLS_DIR%\macro_ci_core.py" replay ^
            --golden-dir "%GOLDEN_DIR%" --config "%CONFIG_FILE%" --out-dir "%OUT_DIR%"
    ) else (
        "%PYTHON%" "%TOOLS_DIR%\macro_ci_core.py" replay --device "%DEVICE%" ^
            --golden-dir "%GOLDEN_DIR%" --config "%CONFIG_FILE%" --out-dir "%OUT_DIR%"
    )
)
set STAGE_EXIT=!ERRORLEVEL!
if !STAGE_EXIT! GTR !FINAL_EXIT! set FINAL_EXIT=!STAGE_EXIT!

REM ===============================================
REM Stage 4/5: failure-rate check + auto-disable (A8.1)
REM ===============================================
echo.
echo [Stage 4/5] Failure-rate check and auto-disable...
"%PYTHON%" "%TOOLS_DIR%\macro_ci_core.py" gatedisable ^
    --report "%OUT_DIR%\report.json" --config "%CONFIG_FILE%" ^
    --state-file "%STATE_FILE%" --queue-file "%QUEUE_FILE%"
set STAGE_EXIT=!ERRORLEVEL!
if !STAGE_EXIT! GTR !FINAL_EXIT! set FINAL_EXIT=!STAGE_EXIT!
if !STAGE_EXIT! EQU 2 echo [Stage 4] A8.1 feedback queue written: %QUEUE_FILE%

REM ===============================================
REM Stage 5/5: version baseline management
REM ===============================================
echo.
echo [Stage 5/5] Version baseline management...
if "%UPDATE_BASELINE%"=="1" (
    "%PYTHON%" "%TOOLS_DIR%\macro_ci_core.py" baseline --update ^
        --report "%OUT_DIR%\report.json" --baseline-file "%BASELINE_FILE%"
) else (
    "%PYTHON%" "%TOOLS_DIR%\macro_ci_core.py" baseline ^
        --report "%OUT_DIR%\report.json" --baseline-file "%BASELINE_FILE%"
)
set STAGE_EXIT=!ERRORLEVEL!
if !STAGE_EXIT! GTR !FINAL_EXIT! set FINAL_EXIT=!STAGE_EXIT!

:done
echo.
echo ===============================================
echo Macro CI finished: exit code !FINAL_EXIT!
echo   (0=PASS 1=FAIL/drift 2=AUTO-DISABLED 3=ENV 4=CONFIG)
echo   Report: %OUT_DIR%\report.json / junit.xml
echo   A8.1 queue: %QUEUE_FILE%
echo ===============================================
exit /b !FINAL_EXIT!

:usage
echo.
echo Usage: run-macro-ci.bat [options]
echo.
echo   --offline-validate   static checks + baseline dry-run only (no device)
echo   --update-baseline    accept current versions as baseline (fix drift)
echo   --device ^<serial^>    adb device serial
echo   --golden-dir ^<dir^>   golden samples dir (default tests\macro\golden)
echo   --out-dir ^<dir^>      report output dir (default reports\ci\macro)
echo   --help               show this help
echo.
exit /b 0
