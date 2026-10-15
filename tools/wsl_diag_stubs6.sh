#!/bin/bash
set +e
echo "=== A. core.platform.api.stubs.defaults definition ==="
sed -n '/name: "core\.platform\.api\.stubs\.defaults"/,/^}/p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== B. system-modules-no-annotations defaults ==="
sed -n '/name: "system-modules-no-annotations"/,/^}/p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== C. ninja: build line for stable.core.platform.api.stubs combined jar ==="
grep -n "^build out/soong/.intermediates/build/soong/java/core-libraries/stable\.core\.platform\.api\.stubs/android_common/combined/stable\.core\.platform\.api\.stubs\.jar" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -5
echo
echo "=== D. ninja: rule def for that build (grab next 3 lines after match) ==="
grep -n -A3 "^build out/soong/.intermediates/build/soong/java/core-libraries/stable\.core\.platform\.api\.stubs/android_common/combined/stable\.core\.platform\.api\.stubs\.jar" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -20
echo
echo "=== E. what android.stubs droiddoc module exists for core-libraries? ==="
grep -n "android_stubs\|stubs.*defaults\|droiddoc" /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null | head -30
echo
echo "=== F. metalava binary present? ==="
ls /root/aosp/out/host/linux-x86/bin/metalava 2>&1
ls /root/aosp/prebuilts/build-tools/linux-x86/bin/metalava 2>&1
find /root/aosp/out/host -name "metalava*" 2>/dev/null | head -3
echo
echo "=== G. gen dir for the stubs droiddoc (source stub generation output) ==="
find /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries -maxdepth 2 -type d 2>/dev/null | head -40
echo
echo "=== H. is there an android.stubs / stable stubs gen dir with metalava outputs ==="
find /root/aosp/out/soong/.intermediates -path "*core-libraries*" -name "*.txt" 2>/dev/null | head
find /root/aosp/out/soong/.intermediates -path "*core-libraries*" -type d -name "gen*" 2>/dev/null | head
echo
echo "=== I. check ninja for the droiddoc/metalava rule producing android stable stubs (search '--mode stub' or '-m stub') ==="
grep -o "metalava[^ ]*" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | sort -u | head
echo
echo "=== J. any java stub jar near framework api intermediates (from-source android stubs)? ==="
ls -la /root/aosp/out/soong/.intermediates/frameworks/base/api/api-stubs-docs-non-updatable/ 2>/dev/null | head -20
find /root/aosp/out/soong/.intermediates/frameworks/base/api -name "*.jar" 2>/dev/null | head -10
echo DONE
