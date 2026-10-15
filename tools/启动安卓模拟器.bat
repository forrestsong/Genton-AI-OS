@echo off
setlocal
title AI Native OS - Emulator Launcher

echo ============================================================
echo   AI Native OS - AOSP build / emulator launcher
echo ============================================================
echo.
echo   Image dir  : /root/aosp/out/target/product/emu64x
echo   Local copy : D:\AI Native OS\artifacts\emu64x
echo.
echo   The Android UI appears as a separate window.
echo   Close this window to stop the emulator.
echo.
echo ------------------------------------------------------------
echo   [1/3] Checking WSL ...
echo ------------------------------------------------------------
wsl -l -q >nul 2>&1
if errorlevel 1 goto NOWSL

wsl -d Ubuntu -u root -- true >nul 2>&1
if errorlevel 1 goto NODISTRO
echo   OK: WSL is available.

echo.
echo ------------------------------------------------------------
echo   [2/3] Checking images ...
echo ------------------------------------------------------------
wsl -d Ubuntu -u root -- bash -lc "ls -1 /root/aosp/out/target/product/emu64x/kernel-ranchu /root/aosp/out/target/product/emu64x/system-qemu.img 2>/dev/null || echo MISSING"
wsl -d Ubuntu -u root -- bash -lc "test -f /root/aosp/out/target/product/emu64x/kernel-ranchu && test -f /root/aosp/out/target/product/emu64x/system-qemu.img"
if errorlevel 1 goto NOIMAGE
echo   OK: images found.

echo.
echo ------------------------------------------------------------
echo   [3/3] Starting emulator (first boot about 20 seconds) ...
echo ------------------------------------------------------------
echo.
echo   Open another window to check services:
echo     wsl -d Ubuntu -u root -- /root/aosp/out/host/linux-x86/bin/adb shell service list
echo.
echo   Or use: tools\check-services.bat
echo.
echo ------------------------------------------------------------
echo.

wsl -d Ubuntu -u root -- bash -lc "cd /root/aosp && source build/envsetup.sh >/dev/null 2>&1 && lunch sdk_phone64_x86_64-bp2a-userdebug >/dev/null 2>&1 && exec /root/aosp/prebuilts/android-emulator/linux-x86_64/emulator -no-audio -no-boot-anim -gpu swiftshader_indirect -no-metrics"

echo.
echo Emulator process finished.
goto END

:NOWSL
echo.
echo [ERROR] WSL is not available or returned an error.
echo         Install WSL2 and an Ubuntu distro, then retry.
echo.
goto END

:NODISTRO
echo.
echo [ERROR] Cannot start the Ubuntu distro.
echo         Try running this first:  wsl -d Ubuntu -u root -- true
echo.
goto END

:NOIMAGE
echo.
echo [ERROR] Required images are missing in the WSL build tree.
echo         Expected under: /root/aosp/out/target/product/emu64x/
echo         A local copy is at: D:\AI Native OS\artifacts\emu64x\
echo.
goto END

:END
echo.
echo ============================================================
echo   Finished. Press any key to close this window.
echo ============================================================
pause >nul
exit /b
