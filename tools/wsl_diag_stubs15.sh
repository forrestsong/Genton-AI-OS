#!/bin/bash
set +e
echo "=== A. android_core_platform_stubs_current module in build/soong bp? ==="
grep -rn "android_core_platform_stubs_current" /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== B. in frameworks/base api/bp? ==="
grep -rn "android_core_platform_stubs_current" /root/aosp/frameworks/base/ 2>/dev/null --include="*.bp" | head
echo
echo "=== C. how does 'core_platform' contribution from framework get into stable stubs - check soong java stubs sources for core platform ==="
grep -rn "core_platform" /root/aosp/build/soong/java/droiddoc.go /root/aosp/build/soong/java/api_levels.go /root/aosp/build/soong/java/system_modules.go 2>/dev/null | head
echo
echo "=== D. does java_system_modules builder merge libs into one image - read system_modules.go ==="
sed -n '1,80p' /root/aosp/build/soong/java/system_modules.go 2>/dev/null
echo DONE
