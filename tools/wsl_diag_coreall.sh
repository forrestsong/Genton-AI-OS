#!/bin/bash
set +e
echo "=== A. core-all-system-modules definition ==="
grep -rn -B3 -A12 "core-all-system-modules" /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head -40
echo
echo "=== B. what maps sdk_version core_platform to system modules in soong? ==="
grep -rn "core_platform\|CorePlatform" /root/aosp/build/soong/java/sdk_versions.go 2>/dev/null | head -20
grep -rn "core_platform" /root/aosp/build/soong/java/api_levels.go 2>/dev/null | head -10
echo
echo "=== C. search soong for 'stable-core-platform-api-stubs-system-modules' reference (who sets it as system_modules) ==="
grep -rn "stable-core-platform-api-stubs-system-modules" /root/aosp/build/soong/ 2>/dev/null | head -10
echo
echo "=== D. system_modules for core_platform - check java/common or bootclasspath config ==="
grep -rn "core_platform" /root/aosp/build/soong/java/system_modules.go /root/aosp/build/soong/java/common.go 2>/dev/null | head
echo
echo "=== E. what system_modules do OTHER core_platform modules use in this tree? check a module that built OK ==="
grep -rn "system_modules:" /root/aosp/frameworks/base/AconfigFlags.bp 2>/dev/null | head
echo
echo "=== F. is core-all-system-modules complete? check its intermediates ==="
find /root/aosp/out/soong/.intermediates -path "*core-all-system-modules*" -name "modules" 2>/dev/null | head
for M in $(find /root/aosp/out/soong/.intermediates -path "*core-all-system-modules*" -name "modules" 2>/dev/null | head -2); do
  echo "--- $M ---"
  strings $M 2>/dev/null | grep -c "android/os/Binder"
done
echo DONE
