#!/bin/bash
set +e
echo "=== A. does the system modules jimage contain android/os/Binder? ==="
MODS=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/modules
ls -la $MODS 2>&1
# jimage strings check
strings $MODS 2>/dev/null | grep -c "android/os/Binder" 
strings $MODS 2>/dev/null | grep -m3 "android/os/Binder"
echo "--- jrt-fs.jar Binder check ---"
JRT=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/jrt-fs.jar
unzip -l $JRT 2>/dev/null | grep -c "Binder"
echo
echo "=== B. profiling_flags_lib module def ==="
sed -n '/name: "profiling_flags_lib"/,/^}/p' /root/aosp/packages/modules/Profiling/framework/Android.bp 2>/dev/null
echo
echo "=== C. combined stubs jar ninja rule search ==="
grep -n "stable\.core\.platform\.api\.stubs/android_common/combined" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -3
echo
echo "=== D. search ninja build stmt for the stubs combined jar (any form) ==="
grep -n "combined/stable.core.platform.api.stubs.jar" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -5
echo
echo "=== E. does jimage tool exist ==="
ls /root/aosp/out/host/linux-x86/bin/jimage 2>&1
echo
echo "=== F. art module public api stubs module_lib jar - does it have android? ==="
find /root/aosp/out/soong/.intermediates -path "*art.module.public.api.stubs.module_lib*" -name "*.jar" 2>/dev/null | head -3
echo
echo "=== G. core platform api contribution from frameworks/base: does framework have a stubs.api.contribution for core_platform? ==="
find /root/aosp/out/soong/.intermediates/frameworks/base -name "*.api.contribution" 2>/dev/null | head -20
echo
echo "=== H. is there an android_core_platform_stubs / framework core platform stubs jar? ==="
find /root/aosp/out/soong/.intermediates/frameworks/base -path "*core*stubs*" -name "*.jar" 2>/dev/null | head -10
echo DONE
