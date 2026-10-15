#!/bin/bash
set +e
cd /root/aosp
echo "=== A. is core-libraries/Android.bp modified vs git HEAD? ==="
git -C build/soong diff HEAD -- java/core-libraries/Android.bp 2>&1 | head -40
echo
echo "=== B. git log of that file (recent) ==="
git -C build/soong log --oneline -5 -- java/core-libraries/Android.bp 2>&1 | head
echo
echo "=== C. is AconfigFlags.bp modified vs git? ==="
git -C frameworks/base diff HEAD -- AconfigFlags.bp 2>&1 | head -30
echo
echo "=== D. git status of build/soong and frameworks/base (any modified files?) ==="
git -C build/soong status --short 2>&1 | head -20
git -C frameworks/base status --short 2>&1 | head -30
echo
echo "=== E. check upstream core.platform.api.stubs in git HEAD (is it same as working?) ==="
git -C build/soong show HEAD:java/core-libraries/Android.bp 2>/dev/null | sed -n '/name: "stable\.core\.platform\.api\.stubs"/,/^}/p' | head -20
echo
echo "=== F. does git HEAD have android_core_platform_stubs_current_contributions wired anywhere? ==="
git -C build/soong grep "android_core_platform_stubs_current_contributions" HEAD -- java/ 2>/dev/null | head
echo
echo "=== G. check android_core_platform_stubs_current module in git HEAD frameworks/base ==="
git -C frameworks/base grep "core_platform" HEAD -- api/Android.bp 2>/dev/null | head
echo
echo "=== H. is there android_core_platform_stubs_current module at all? (search whole tree bp) ==="
grep -rln "android_core_platform_stubs_current" /root/aosp/ 2>/dev/null --include="*.bp" | head
grep -rln "name: \"android_core_platform_stubs_current\"" /root/aosp/ 2>/dev/null | head
echo
echo "=== I. soong config: what generates core platform stubs from framework? search for core_platform api_surface modules ==="
grep -rln "api_surface" /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head
grep -rn "api_surface" /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head -20
echo DONE
