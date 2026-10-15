#!/bin/bash
set +e
echo "=== A. api dump files exist? ==="
ls -la /root/aosp/frameworks/base/api/current.txt /root/aosp/frameworks/base/api/system-current.txt 2>&1 | head
echo
echo "=== B. Binder in current.txt (count lines) ==="
grep -c "android.os.Binder" /root/aosp/frameworks/base/api/current.txt 2>&1
grep -n "class Binder" /root/aosp/frameworks/base/api/current.txt 2>&1 | head -3
echo
echo "=== C. Binder in system-current.txt ==="
grep -c "android.os.Binder" /root/aosp/frameworks/base/api/system-current.txt 2>&1
echo
echo "=== D. stubs jar: does it contain android/os/Binder.class? ==="
JAR=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable.core.platform.api.no.annotations.stubs/android_common/turbine-combined/stable.core.platform.api.no.annotations.stubs.jar
ls -la $JAR 2>&1
unzip -l $JAR 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $JAR 2>/dev/null | grep "android/os/Binder" | head -3
echo
echo "=== E. total entries in stubs jar ==="
unzip -l $JAR 2>/dev/null | tail -1
echo
echo "=== F. DeviceConfig in stubs jar ==="
unzip -l $JAR 2>/dev/null | grep -c "android/provider/DeviceConfig.class"
echo
echo "=== G. Binder in modules image (jimage extract attempt) ==="
cd /tmp && rm -rf jrt_check && mkdir jrt_check && cd jrt_check
JRT=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/jrt-fs.jar
unzip -l $JRT 2>/dev/null | head -5
echo "--- jrt entries count ---"
unzip -l $JRT 2>/dev/null | tail -1
echo
echo "=== H. stable.core.platform.api.stubs.jar (full) Binder? ==="
JAR2=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable.core.platform.api.stubs/android_common/combined/stable.core.platform.api.stubs.jar
ls -la $JAR2 2>&1
unzip -l $JAR2 2>/dev/null | grep -c "android/os/Binder.class"
echo
echo "=== I. core-lambda-stubs for system modules ==="
find /root/aosp/out/soong/.intermediates -path "*core-lambda-stubs-for-system-modules*" -name "*.jar" 2>/dev/null | head
echo
echo "=== J. metalava generated stubs source? check gen dir ==="
find /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable.core.platform.api.no.annotations.stubs -maxdepth 3 -type d 2>/dev/null | head -20
echo DONE
