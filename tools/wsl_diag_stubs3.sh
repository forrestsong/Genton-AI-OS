#!/bin/bash
set +e
echo "=== A. frameworks/base/api/ directory listing ==="
ls -la /root/aosp/frameworks/base/api/ 2>&1 | head -40
echo
echo "=== B. count of files in api dir ==="
ls /root/aosp/frameworks/base/api/ 2>/dev/null | wc -l
echo
echo "=== C. Binder source exists? ==="
ls -la /root/aosp/frameworks/base/core/java/android/os/Binder.java 2>&1
ls -la /root/aosp/frameworks/base/core/java/android/provider/DeviceConfig.java 2>&1
echo
echo "=== D. sample of what the stubs jar contains (first 20 android entries) ==="
JAR=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable.core.platform.api.no.annotations.stubs/android_common/turbine-combined/stable.core.platform.api.no.annotations.stubs.jar
unzip -l $JAR 2>/dev/null | grep "\.class" | head -20
echo
echo "=== E. count .class files by top-level pkg ==="
unzip -l $JAR 2>/dev/null | grep "\.class" | awk '{print $4}' | cut -d/ -f1-3 | sort | uniq -c | sort -rn | head -25
echo
echo "=== F. does stubs jar have java/lang/Object? ==="
unzip -l $JAR 2>/dev/null | grep -c "java/lang/Object.class"
echo
echo "=== G. other api-related missing? check for current.txt alternatives ==="
find /root/aosp/frameworks/base/api -name "*.txt" 2>/dev/null | head -20
echo
echo "=== H. Soong build of stubs: check for metalava inputs, is there a 'gen' with source? ==="
find /root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable.core.platform.api.no.annotations.stubs -maxdepth 2 2>/dev/null
echo
echo "=== I. api/module-lib-current.txt etc ==="
ls -la /root/aosp/frameworks/base/api/*current* 2>&1 | head
echo
echo "=== J. check build.log / other soong.log for metalava errors ==="
ls -la /root/aosp/out/soong/metalava.log /root/aosp/out/soong/soong.log 2>&1 | head
echo
echo "=== K. is there a missing API error somewhere in soong.log? ==="
grep -i "current.txt\|api dump\|missing api" /root/aosp/out/soong/soong.log 2>/dev/null | tail -10
echo DONE
