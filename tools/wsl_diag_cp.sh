#!/bin/bash
set +e
echo "=== A. build/soong/java/config/config.go full ==="
cat /root/aosp/build/soong/java/config/config.go 2>/dev/null | head -120
echo
echo "=== B. base.go:600-660 (IsStubsModule + context) ==="
sed -n '600,660p' /root/aosp/build/soong/java/base.go 2>/dev/null
echo
echo "=== C. search stubs module generation: 'is_stubs_module' usage in soong go ==="
grep -rn "IsStubsModule\|is_stubs_module" /root/aosp/build/soong/java/*.go 2>/dev/null | head -20
echo
echo "=== D. android_core_platform_stubs_current_contributions referenced in any go code? ==="
grep -rn "android_core_platform_stubs_current_contributions" /root/aosp/build/soong/ 2>/dev/null | head
echo
echo "=== E. soong config for core platform: search 'core_platform' in config.go / common.go ==="
grep -rn "core_platform" /root/aosp/build/soong/java/config/*.go 2>/dev/null | head
grep -rn "CorePlatform" /root/aosp/build/soong/java/*.go 2>/dev/null | head -20
echo DONE
