#!/bin/bash
set +e
cd /root/aosp
rm -rf out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules
echo "deleted intermediate"
source build/envsetup.sh >/dev/null 2>&1
lunch aosp_cpday10-ap3a-eng >/dev/null 2>&1
echo "=== rebuild $(date) ==="
timeout 1800 m stable-core-platform-api-stubs-system-modules 2>&1 | tail -40
echo "=== EXIT: ${PIPESTATUS[0]} ==="
MODS=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/modules
echo "=== verify ==="
ls -la $MODS 2>&1
strings $MODS 2>/dev/null | grep -c "android/os/Binder"
strings $MODS 2>/dev/null | grep -c "android/provider/DeviceConfig"
ls -la /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/ 2>&1
echo DONE
