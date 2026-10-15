#!/bin/bash
set +e
echo "=== A. framework-minus-apex-aconfig-java-defaults def ==="
grep -rn "framework-minus-apex-aconfig-java-defaults" /root/aosp/frameworks/base/Android.bp 2>/dev/null | head
grep -rn -A20 "name: \"framework-minus-apex-aconfig-java-defaults\"" /root/aosp/frameworks/base/Android.bp 2>/dev/null | head -40
echo
echo "=== B. search anywhere for the defaults def ==="
grep -rln "framework-minus-apex-aconfig-java-defaults" /root/aosp/frameworks/base/ 2>/dev/null | head
echo
echo "=== C. what sdk/system do aconfig flag libraries use? check aconfig module type defaults ==="
grep -rn "aconfig_declarations" /root/aosp/build/soong/ 2>/dev/null | grep -i "default\|sdk" | head -10
echo
echo "=== D. does core.platform.api.stubs.defaults get android contributions via is_stubs_module? find is_stubs_module impl in soong ==="
grep -rn "is_stubs_module\|IsStubsModule" /root/aosp/build/soong/java/*.go 2>/dev/null | head -20
echo
echo "=== E. check android_core_platform_stubs_current_contributions usage (who uses it) ==="
grep -rn "android_core_platform_stubs_current_contributions" /root/aosp/ 2>/dev/null --include="*.bp" | head -20
echo
echo "=== F. is there a core_platform stubs source contribution from frameworks/base? ==="
grep -rn "core_platform" /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head -10
grep -rn "core_platform" /root/aosp/frameworks/base/core/api/Android.bp 2>/dev/null | head -10
echo
echo "=== G. list all *.api.contribution from frameworks/base (full) ==="
find /root/aosp/out/soong/.intermediates/frameworks/base -name "*.api.contribution" 2>/dev/null | grep -i "core" | head
echo
echo "=== H. what does core.platform.api.stubs defaults + is_stubs_module actually generate? check stubs.go ==="
sed -n '1,60p' /root/aosp/build/soong/java/stubs.go 2>/dev/null
echo DONE
