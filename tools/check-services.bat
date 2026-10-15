@echo off
setlocal
title AI Native OS - Service Check

echo ============================================================
echo   AI Native OS - genton service check
echo ============================================================
echo.
echo   boot_completed :
wsl -d Ubuntu -u root -- /root/aosp/out/host/linux-x86/bin/adb shell getprop sys.boot_completed
echo.
echo   Registered genton services:
wsl -d Ubuntu -u root -- /root/aosp/out/host/linux-x86/bin/adb shell service list
wsl -d Ubuntu -u root -- bash -lc "/root/aosp/out/host/linux-x86/bin/adb shell service list 2>/dev/null | grep -c genton"
echo.
echo   Registry summary log:
wsl -d Ubuntu -u root -- bash -lc "/root/aosp/out/host/linux-x86/bin/adb logcat -d 2>/dev/null | grep -E \"services registered: ok=|GentonServiceRegistry: registered\" | tail -25"
echo.
echo ============================================================
echo   Press any key to close.
echo ============================================================
pause >nul
exit /b
