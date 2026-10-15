#!/bin/bash
set +e
cd /root/aosp
echo "=== deleting stubs chain intermediates ==="
rm -rf out/soong/.intermediates/build/soong/java/core-libraries/stable.core.platform.api.stubs
rm -rf out/soong/.intermediates/build/soong/java/core-libraries/stable.core.platform.api.no.annotations.stubs
rm -rf out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules
rm -rf out/soong/.intermediates/build/soong/java/core-libraries/core-public-stubs-system-modules
rm -rf out/soong/.intermediates/build/soong/java/core-libraries/legacy.core.platform.api.stubs
rm -rf out/soong/.intermediates/build/soong/java/core-libraries/legacy.core.platform.api.no.annotations.stubs
echo "deleted. Now rebuilding stable-core-platform-api-stubs-system-modules..."
source build/envsetup.sh >/dev/null 2>&1
lunch aosp_cpday10-ap3a-eng >/dev/null 2>&1
timeout 1200 m stable-core-platform-api-stubs-system-modules 2>&1 | tail -60
echo "=== EXIT: $? ==="
echo
echo "=== check regenerated stubs jar for Binder ==="
JAR=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable.core.platform.api.no.annotations.stubs/android_common/turbine-combined/stable.core.platform.api.no.annotations.stubs.jar
ls -la $JAR 2>&1
unzip -l $JAR 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $JAR 2>/dev/null | grep -c "android/provider/DeviceConfig.class"
echo "=== system modules jimage Binder ==="
strings /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/modules 2>/dev/null | grep -c "android/os/Binder"
echo "=== jar total entries ==="
unzip -l $JAR 2>/dev/null | tail -1
echo DONE
