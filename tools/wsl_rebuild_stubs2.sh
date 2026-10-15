#!/bin/bash
set +e
cd /root/aosp
echo "=== deleting system-modules intermediate ==="
rm -rf out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules
echo "deleted"
source build/envsetup.sh >/dev/null 2>&1
lunch aosp_cpday10-ap3a-eng >/dev/null 2>&1
echo "=== rebuilding (ninja regen + build) $(date) ==="
timeout 1800 m stable-core-platform-api-stubs-system-modules 2>&1 | tail -50
echo "=== EXIT: ${PIPESTATUS[0]} ==="
echo
echo "=== verify jimage Binder/DeviceConfig ==="
MODS=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/modules
ls -la $MODS 2>&1
strings $MODS 2>/dev/null | grep -c "android/os/Binder"
strings $MODS 2>/dev/null | grep -c "android/provider/DeviceConfig"
echo "=== jar entries check (no.annotations jar still java-only, image should now be bigger) ==="
ls -la /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/ 2>&1
echo DONE
