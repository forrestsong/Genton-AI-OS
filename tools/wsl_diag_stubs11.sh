#!/bin/bash
set +e
echo "=== A. sdk_version core_platform -> system modules mapping in soong ==="
grep -rn "core_platform" /root/aosp/build/soong/java/sdk_versions.go 2>/dev/null | head
echo
echo "=== B. where core_platform system modules name is decided ==="
grep -rn "core-for-system-modules\|core-system-stubs\|stable-core-platform\|core_platform.*system_modules" /root/aosp/build/soong/java/*.go 2>/dev/null | head -20
echo
echo "=== C. framework core_platform contribution module? search frameworks/base for 'core_platform' in bp ==="
grep -rln "api_surface: \"core_platform\"\|core_platform" /root/aosp/frameworks/base/api/Android.bp /root/aosp/frameworks/base/api/*.bp 2>/dev/null
echo
echo "=== D. api contributions list: what modules provide core platform contributions ==="
grep -rn "core_platform" /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null | head
echo
echo "=== E. is there android_core_platform_stubs_current module (from frameworks/base)? ==="
grep -rn "android_core_platform_stubs_current\|core_platform.*stubs" /root/aosp/frameworks/base/Android.bp /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head
echo
echo "=== F. check if core.platform.api.stubs has a hidden srcs via api_contributions in stubs.go ==="
grep -n "api_contributions\|ApiContributions" /root/aosp/build/soong/java/stubs.go 2>/dev/null | head
grep -n "api_contributions\|ApiContributions" /root/aosp/build/soong/java/java_library.go 2>/dev/null | head
echo
echo "=== G. What does sdk_version core_platform mean - read the api surface doc ==="
grep -rn "core_platform" /root/aosp/build/soong/java/sdk_constants.go 2>/dev/null | head
echo
echo "=== H. metalava - is it fully built? ==="
ls -la /root/aosp/out/host/linux-x86/bin/metalava 2>&1
echo
echo "=== I. does prebuilt sdk core contain Binder (reference for what core platform should have)? ==="
ls /root/aosp/prebuilts/sdk/current/core/ 2>/dev/null | head
find /root/aosp/prebuilts/sdk/current/core -name "*.jar" 2>/dev/null | head
echo DONE
