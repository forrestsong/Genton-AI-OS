#!/bin/bash
# Diagnose stable-core-platform-api-stubs-system-modules generation
set +e
echo "=== 1. regenerated system dir ==="
ls -la /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/ 2>/dev/null
echo
echo "=== 2. lib/modules detail ==="
ls -la /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/ 2>/dev/null
echo
echo "=== 3. timestamp of system ==="
stat -c "%y %n" /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system 2>/dev/null
echo
echo "=== 4. stable-core-platform-api-stubs intermediates ==="
ls -la /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/ 2>/dev/null | grep -i "stable-core" | head -20
echo
echo "=== 4b. find stubs jars ==="
find /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries -name "*.jar" 2>/dev/null | grep -i "stable" | head -20
echo
echo "=== 5. api dump has Binder ==="
grep -c "class Binder" /root/aosp/frameworks/base/api/current.txt 2>/dev/null
grep -c "android.os.Binder" /root/aosp/frameworks/base/api/current.txt 2>/dev/null
echo
echo "=== 6. system_modules def in soong ==="
grep -n "stable-core-platform-api-stubs-system-modules" /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== 7. system_modules builder: what libs feed it ==="
sed -n '/stable-core-platform-api-stubs-system-modules/,/^}/p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== 8. profiling_flags_lib bp (what sdk/system it uses) ==="
grep -rn "sdk_version\|system_modules\|platform_apis" /root/aosp/packages/modules/Profiling/framework/Android.bp 2>/dev/null | head -20
echo
echo "=== 9. was system dir regenerated at 17:13? ==="
find /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules -newermt "2026-10-06 17:13:00" -type f 2>/dev/null | head -30
echo
echo "=== 10. jrt-fs.jar or modules file? ==="
find /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules -name "jrt-fs.jar" -o -name "modules" 2>/dev/null | head
echo DONE
