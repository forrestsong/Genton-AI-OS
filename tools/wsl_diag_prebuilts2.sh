#!/bin/bash
set +e
echo "=== A. current/system/android.jar ==="
SYS=/root/aosp/prebuilts/sdk/current/system/android.jar
ls -la $SYS 2>&1
unzip -l $SYS 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $SYS 2>/dev/null | grep -c "android/provider/DeviceConfig"
unzip -l $SYS 2>/dev/null | tail -1
echo
echo "=== B. current/module-lib/android.jar ==="
ML=/root/aosp/prebuilts/sdk/current/module-lib/android.jar
ls -la $ML 2>&1
unzip -l $ML 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $ML 2>/dev/null | grep -c "android/provider/DeviceConfig"
unzip -l $ML 2>/dev/null | tail -1
echo
echo "=== C. current/core/android.jar (1.4MB - core only?) ==="
CORE=/root/aosp/prebuilts/sdk/current/core/android.jar
unzip -l $CORE 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $CORE 2>/dev/null | tail -1
echo
echo "=== D. how is prebuilt android.jar referenced in bp (prebuilts/sdk/current/Android.bp)? ==="
grep -n -B3 -A10 "core/android.jar\|name: \"android_core\|java_import" /root/aosp/prebuilts/sdk/current/Android.bp 2>/dev/null | head -50
echo
echo "=== E. existing java_import examples for sdk jars ==="
grep -rn "java_import" /root/aosp/prebuilts/sdk/current/Android.bp 2>/dev/null | head
echo DONE
