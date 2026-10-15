#!/bin/bash
set +e
echo "=== A. find android_core_platform_stubs_current module (exact name) ==="
grep -rn 'name: "android_core_platform_stubs_current"' /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head
echo
echo "=== B. any module named *core_platform*stubs* ==="
grep -rn "core_platform.*stubs\|stubs.*core_platform" /root/aosp/frameworks/base/ --include="*.bp" 2>/dev/null | head
echo
echo "=== C. search droidstubs.go for core_platform stubs module generation ==="
grep -n "core_platform\|CorePlatform\|core platform" /root/aosp/build/soong/java/droidstubs.go 2>/dev/null | head -30
echo
echo "=== D. the droidstubs.go:1530-1545 context (platform.api mapping) ==="
sed -n '1510,1550p' /root/aosp/build/soong/java/droidstubs.go 2>/dev/null
echo
echo "=== E. who sets api_surface core_platform - all bp ==="
grep -rn "api_surface: \"core_platform\"" /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head
echo
echo "=== F. droidstubs module for android core platform - check frameworks/base/api/StubLibraries.bp ==="
grep -n "core_platform\|CorePlatform" /root/aosp/frameworks/base/api/StubLibraries.bp 2>/dev/null | head
echo DONE
