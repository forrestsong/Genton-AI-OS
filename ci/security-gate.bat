@echo off
REM =============================================================================
REM AI Native OS | E1.2 Red-line checklist tooling -- CI Security Gate (Windows)
REM =============================================================================
REM
REM QUICK START (one-page run instructions):
REM
REM   Usage:
REM     ci\security-gate.bat                 # full security gate (default)
REM     ci\security-gate.bat --json          # JSON output (for CI parsers)
REM     ci\security-gate.bat --quick         # static red-lines only (skip baseline audit)
REM     ci\security-gate.bat --self-check    # environment self-check (exit code only)
REM
REM   Exit codes:
REM     0 = PASS (no CRITICAL/HIGH violations, mergeable)
REM     1 = FAIL (CRITICAL or HIGH violations present, blocks merge)
REM     2 = ENV/TOOL ERROR (python missing, key inputs missing, etc.)
REM
REM   Checks performed (red-line mapping):
REM     A. E1.1 baseline audit  tools\security_audit.py (permissions/capability/deny)
REM     B. Permission manifest static audit R1.1/R1.2/R1.3/R1.5
REM        (overlay\frameworks\base\core\res\AndroidManifest_permissions.xml:
REM         protectionLevel / naming / description)
REM     C. privapp whitelist audit R1.9 (granted items declared, no dup, naming)
REM     D. sepolicy audit R2.8/R2.9/R10.7/R1.10 (neverallow / whitelist-style)
REM     E. service_contexts reconciliation R2.8 (service <-> domain mapping)
REM     (B~E checker core is shared in ci\security-gate-checks.py -- same logic
REM      as the .sh version, guaranteed consistent across platforms)
REM
REM   CI integration (Azure Pipelines / GitHub Actions Windows / Jenkins):
REM     - Azure Pipelines:
REM         - task: CmdLine
REM           inputs: { script: "ci\\security-gate.bat --json" }
REM     - GitHub Actions (windows-latest runner):
REM         - run: ci\security-gate.bat --json
REM     - Local pre-commit (Windows): hook ci\security-gate.bat
REM
REM   Reports written to: reports\ci\security-gate-<timestamp>.json + .log
REM   Integration: A1.2/A1.4 permission-convergence regression -- every change to
REM   permission / privapp / sepolicy MUST pass this gate.
REM
REM Version: 1.0 | Date: 2026-09-09 | Maintainer: Security & Compliance (E1.2)
REM =============================================================================

setlocal enabledelayedexpansion

REM --- make Python's UTF-8 output display correctly on this console ---
chcp 65001 >nul 2>nul

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

set "OUTPUT_MODE=console"
set "QUICK=0"
set "SELF_CHECK=0"

REM ---------- argument parsing ----------
for %%a in (%*) do (
    if "%%a"=="--json"       set "OUTPUT_MODE=json"
    if "%%a"=="--quick"      set "QUICK=1"
    if "%%a"=="--self-check" set "SELF_CHECK=1"
    if "%%a"=="-h"           goto :help
)

REM ---------- python detection ----------
set "PY="
where python >nul 2>nul && set "PY=python"
if not defined PY (
    where python3 >nul 2>nul && set "PY=python3"
)
if not defined PY (
    echo [security-gate][FATAL] python not found. Install Python 3.7+ and add to PATH.
    exit /b 2
)

REM ---------- self-check mode ----------
if "%SELF_CHECK%"=="1" (
    set "MISSING=0"
    for %%f in (
        "tools\security_audit.py"
        "ci\security-gate-checks.py"
        "overlay\frameworks\base\core\res\AndroidManifest_permissions.xml"
        "permissions\privapp-permissions-ainative.xml"
        "sepolicy\ainative.te"
        "sepolicy\service_contexts"
    ) do (
        if not exist "%PROJECT_ROOT%\%%~f" (
            echo [security-gate][SELF-CHECK] missing: %%~f
            set "MISSING=1"
        )
    )
    if "!MISSING!"=="1" (
        echo [security-gate] self-check FAILED: key inputs missing.
        exit /b 2
    )
    echo [security-gate] self-check PASSED: python=%PY%^, key inputs present.
    exit /b 0
)

REM ---------- main flow ----------
REM --- force UTF-8 for Python output (JSON consumed by CI is UTF-8) ---
set "PYTHONIOENCODING=utf-8"
REM --- locale-independent timestamp via PowerShell (guaranteed on Windows) ---
set "TIMESTAMP=unknown"
for /f "delims=" %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TIMESTAMP=%%i"
set "REPORT_DIR=%PROJECT_ROOT%\reports\ci"
if not exist "%REPORT_DIR%" mkdir "%REPORT_DIR%"
set "LOG_FILE=%REPORT_DIR%\security-gate-%TIMESTAMP%.log"
set "JSON_FILE=%REPORT_DIR%\security-gate-%TIMESTAMP%.json"

echo ==============================================
echo  AI Native OS -- E1.2 CI Security Gate
echo  Time: %date% %time%
echo ==============================================

REM 1) E1.1 baseline audit
echo.
echo [security-gate] A. E1.1 baseline audit (security_audit.py)...
set "AUDIT_RC=0"
if "%QUICK%"=="1" (
    echo     --quick mode: skipping baseline document audit.
) else (
    "%PY%" "%PROJECT_ROOT%\tools\security_audit.py" --checklist E1.1 > "%LOG_FILE%.audit" 2>&1
    set "AUDIT_RC=!errorlevel!"
    echo     security_audit.py exit code: !AUDIT_RC! ^(0=pass / 1=CRITICAL^|HIGH found^)
)

REM 2) static red-line audit B~E (shared checker)
echo.
echo [security-gate] B~E static audit (security-gate-checks.py)...
set "EXTRA_RC=0"
if "%OUTPUT_MODE%"=="json" (
    "%PY%" "%PROJECT_ROOT%\ci\security-gate-checks.py" "%PROJECT_ROOT%" json > "%JSON_FILE%" 2> "%LOG_FILE%.err"
    set "EXTRA_RC=!errorlevel!"
) else (
    "%PY%" "%PROJECT_ROOT%\ci\security-gate-checks.py" "%PROJECT_ROOT%" console > "%LOG_FILE%" 2>&1
    set "EXTRA_RC=!errorlevel!"
    type "%LOG_FILE%"
)

REM 3) final verdict
set "FINAL_RC=0"
if not "!EXTRA_RC!"=="0" set "FINAL_RC=1"
if not "%QUICK%"=="1" (
    if not "!AUDIT_RC!"=="0" (
        if "!AUDIT_RC!"=="1" (
            set "FINAL_RC=1"
        ) else (
            set "FINAL_RC=2"
        )
    )
)

echo.
echo ==============================================
if "!FINAL_RC!"=="0" (
    echo  Security gate result: PASS (0)
) else (
    echo  Security gate result: FAIL/ERROR
)
echo  Reports: %REPORT_DIR%\security-gate-%TIMESTAMP%.*
echo ==============================================
exit /b %FINAL_RC%

:help
REM print the ASCII header block above
for /f "tokens=1,* delims=]" %%a in ('findstr /n "^REM" "%~f0"') do (
    echo %%b
)
exit /b 0
