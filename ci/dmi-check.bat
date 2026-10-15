@echo off
REM CI 脚本：DMI 资产声明校验（F2.3 + A7.2 编译联动）- Windows 版本
REM 用途：在构建流程中强制检查 DMI 声明合规性

setlocal enabledelayedexpansion

echo ===============================================
echo DMI Asset Declaration Validation
echo ===============================================
echo.

set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..
set TOOLS_DIR=%PROJECT_ROOT%\tools
set EXAMPLES_DIR=%PROJECT_ROOT%\docs\examples
set REPORTS_DIR=%PROJECT_ROOT%\reports\ci
set BUILD_DIR=%PROJECT_ROOT%\build

REM 创建目录
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
if not exist "%REPORTS_DIR%" mkdir "%REPORTS_DIR%"

echo Step 1: Compiling DMI validator...
javac -encoding UTF-8 -d "%BUILD_DIR%" "%TOOLS_DIR%\DmiValidator.java"

if %ERRORLEVEL% EQU 0 (
    echo [32m✓ Validator compiled successfully[0m
) else (
    echo [31m✗ Validator compilation failed[0m
    echo.
    echo 提示：请确保已安装 Java 8+ 和依赖库：
    echo   - SnakeYAML: snakeyaml-1.29.jar
    echo   - JSON: org.json-20210307.jar
    echo.
    echo 下载依赖：
    echo   curl -L -o snakeyaml.jar https://repo1.maven.org/maven2/org/yaml/snakeyaml/1.29/snakeyaml-1.29.jar
    echo   curl -L -o json.jar https://repo1.maven.org/maven2/org/json/json/20210307/json-20210307.jar
    exit /b 1
)
echo.

set TOTAL_TESTS=0
set PASSED_TESTS=0
set FAILED_TESTS=0

echo Step 2: Validating golden DMI example...
set /a TOTAL_TESTS+=1

java -cp "%BUILD_DIR%;%TOOLS_DIR%\snakeyaml.jar;%TOOLS_DIR%\json.jar" ^
    com.ainative.tools.DmiValidator ^
    "%EXAMPLES_DIR%\ainative-dmi-weather-example.yaml" ^
    --report "%REPORTS_DIR%\dmi-weather-validation.md"

if %ERRORLEVEL% EQU 0 (
    echo [32m✓ Golden DMI validation: PASS[0m
    set /a PASSED_TESTS+=1
) else (
    echo [31m✗ Golden DMI validation: FAIL[0m
    set /a FAILED_TESTS+=1
)
echo.

REM 扫描 packages/apps/*/assets/ 中的 DMI 文件
echo Step 3: Scanning application DMI assets...
set DMI_PATTERN=ainative-dmi*.yaml ainative-dmi*.json

for /r "%PROJECT_ROOT%\packages\apps" %%F in (%DMI_PATTERN%) do (
    set /a TOTAL_TESTS+=1
    echo 校验: %%F
    
    java -cp "%BUILD_DIR%;%TOOLS_DIR%\snakeyaml.jar;%TOOLS_DIR%\json.jar" ^
        com.ainative.tools.DmiValidator "%%F"
    
    if !ERRORLEVEL! EQU 0 (
        set /a PASSED_TESTS+=1
    ) else (
        set /a FAILED_TESTS+=1
    )
    echo.
)

REM 扫描 overlay/frameworks/base/res/ainative/ 中的系统 DMI
echo Step 4: Scanning system DMI assets...
if exist "%PROJECT_ROOT%\overlay\frameworks\base\res\ainative" (
    for /r "%PROJECT_ROOT%\overlay\frameworks\base\res\ainative" %%F in (%DMI_PATTERN%) do (
        set /a TOTAL_TESTS+=1
        echo 校验: %%F
        
        java -cp "%BUILD_DIR%;%TOOLS_DIR%\snakeyaml.jar;%TOOLS_DIR%\json.jar" ^
            com.ainative.tools.DmiValidator "%%F"
        
        if !ERRORLEVEL! EQU 0 (
            set /a PASSED_TESTS+=1
        ) else (
            set /a FAILED_TESTS+=1
        )
        echo.
    )
)

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
    echo [31m✗ DMI validation FAILED - 构建应该终止[0m
    exit /b 1
) else (
    echo [32m✓ All DMI validations PASSED[0m
    exit /b 0
)
