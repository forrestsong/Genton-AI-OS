#!/bin/bash
set +e
cd /root/aosp/frameworks/base
echo "=== A. git status of api/ ==="
git status --short api/ 2>&1 | head -30
echo
echo "=== B. are api txt files tracked in git? (sample) ==="
git ls-files api/ 2>&1 | head -30
echo
echo "=== C. count tracked files in api/ ==="
git ls-files api/ 2>&1 | wc -l
echo
echo "=== D. current HEAD ==="
git log --oneline -1 2>&1
git describe --tags 2>&1 | head -1
echo
echo "=== E. stubs jar build rule: find rule number & inputs ==="
cd /root/aosp
NINJA=/root/aosp/out/build-aosp_cpday10.ninja
grep -n "stable\.core\.platform\.api\.no\.annotations\.stubs/android_common/turbine-combined/stable\.core\.platform\.api\.no\.annotations\.stubs\.jar" $NINJA 2>/dev/null | head -10
echo
echo "=== F. how is stable.core.platform.api.no.annotations.stubs defined in soong bp ==="
sed -n '/name: "stable\.core\.platform\.api\.no\.annotations\.stubs"/,/^}/p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== G. the stable.core.platform.api.stubs def (parent) ==="
sed -n '/name: "stable\.core\.platform\.api\.stubs"/,/^}/p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== H. what metalava/droiddoc rule generates the stubs? search rule103xxx near the combined jar ==="
grep -n "rule10[0-9]*:" $NINJA 2>/dev/null | head -1
echo "--- find rules mentioning metalava + stubs source dirs ---"
grep -n "metalava.*--api\|metalava.*--mode stub" $NINJA 2>/dev/null | head -3
echo
echo "=== I. api-dir related soong module names ==="
grep -o '"[a-z_]*\.api\.[a-z_]*"' /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | sort -u | head -20
echo
echo "=== J. android_current.txt / sdk prebuilt stubs? check prebuilts/sdk ==="
ls /root/aosp/prebuilts/sdk/ 2>/dev/null | head
ls -la /root/aosp/prebuilts/sdk/current/ 2>/dev/null | head
echo DONE
