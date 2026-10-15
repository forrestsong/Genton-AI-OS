#!/bin/bash
set +e
FB=/root/aosp/frameworks/base/core/java/android/annotation
echo "=== A. revert restored files ==="
rm -v $FB/NonNull.java $FB/Nullable.java 2>&1
ls -la $FB/ 2>&1
echo
echo "=== B. confirm: from-source android_system_stubs_current has PUBLIC NonNull? ==="
SYSJAR=/root/aosp/out/soong/.intermediates/frameworks/base/api/android_system_stubs_current/android_common/turbine-combined/android_system_stubs_current.jar
JDK=/root/aosp/prebuilts/jdk/jdk21/linux-x86/bin
cd /tmp && rm -rf sysnn && mkdir sysnn && cd sysnn
unzip -o -q $SYSJAR android/annotation/NonNull.class 2>&1 | head -2
$JDK/javap android.annotation.NonNull 2>&1 | head -4
echo
echo "=== C. prebuilt NonNull re-verify with -v (verbose) ==="
cd /tmp && rm -rf pnn && mkdir pnn && cd pnn
unzip -o -q /root/aosp/prebuilts/sdk/current/system/android.jar android/annotation/NonNull.class 2>&1 | head -2
$JDK/javap -v android.annotation.NonNull 2>&1 | grep -E "flags:|ACC_PUBLIC|interface" | head -6
echo
echo "=== D. sdk33 prebuilt NonNull (older official, intact?) ==="
cd /tmp && rm -rf s33 && mkdir s33 && cd s33
unzip -o -q /root/aosp/prebuilts/sdk/33/system/android.jar android/annotation/NonNull.class 2>&1 | head -2
$JDK/javap android.annotation.NonNull 2>&1 | head -4
echo
echo "=== E. which android.jar does the from-text stub javac classpath include? search ninja for from-text stub classpath ==="
grep -n "framework-location.stubs.module_lib.from-text" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | grep -oE "classpath[^ ]*android\.jar[^ ]*" | head -5
grep -n "framework-location.stubs.module_lib.from-text/android_common/javac" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -2
echo DONE
