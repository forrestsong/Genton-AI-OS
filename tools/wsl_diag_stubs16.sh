#!/bin/bash
set +e
echo "=== A. android_system_stubs_current module def (frameworks/base/api/Android.bp) ==="
sed -n '/name: "android_system_stubs_current"/,/^}/p' /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head -40
echo
echo "=== B. android_module_lib_stubs_current def ==="
sed -n '/name: "android_module_lib_stubs_current"/,/^}/p' /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head -40
echo
echo "=== C. what system modules / sdk do these framework stubs use? (check defaults they inherit) ==="
grep -n "system_modules\|sdk_version" /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head -30
echo
echo "=== D. android_system_stubs_current: Binder + DeviceConfig + class count ==="
SYS=/root/aosp/out/soong/.intermediates/frameworks/base/api/android_system_stubs_current/android_common/turbine-combined/android_system_stubs_current.jar
unzip -l $SYS 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $SYS 2>/dev/null | grep -c "android/provider/DeviceConfig"
unzip -l $SYS 2>/dev/null | tail -1
echo
echo "=== E. does android_system_stubs_current depend on stable-core-platform-api-stubs-system-modules (cycle risk)? ==="
grep -rn "stable-core-platform-api-stubs-system-modules\|core-for-system-modules" /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head
echo
echo "=== F. framework stubs module type + its system modules: look at android_system_stubs_current full def context ==="
grep -n -B5 -A25 "name: \"android_system_stubs_current\"" /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head -50
echo
echo "=== G. what sdk does android_system_stubs_current use to COMPILE (droiddoc doesn't need --system but check) ==="
grep -rn "android_system_stubs_current" /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head
echo DONE
