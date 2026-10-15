#!/bin/bash
set +e
echo "=== A. core-libraries/Android.bp changes (git diff) ==="
git -C /root/aosp/build/soong diff java/core-libraries/Android.bp 2>/dev/null | head -50
echo
echo "=== B. prebuilts/sdk/current/Android.bp changes ==="
git -C /root/aosp/prebuilts/sdk diff current/Android.bp 2>/dev/null | head -30
echo
echo "=== C. annotation dir reverted? ==="
ls /root/aosp/frameworks/base/core/java/android/annotation/ 2>&1
echo
echo "=== D. system modules jimage still has Binder (fix intact)? ==="
strings /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/modules 2>/dev/null | grep -c "android/os/Binder"
echo
echo "=== E. frameworks/base git status (our genton changes + any others) ==="
git -C /root/aosp/frameworks/base status --short 2>/dev/null | head -20
echo
echo "=== F. build/soong git status ==="
git -C /root/aosp/build/soong status --short 2>/dev/null | head
echo DONE
