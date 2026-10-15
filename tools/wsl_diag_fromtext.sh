#!/bin/bash
set +e
echo "=== A. from-text stub javac command for the failing module (from build log) ==="
grep -A3 "framework-ondeviceintelligence-platform.stubs.system.from-text/android_common/javac" /tmp/services_core_build2.log 2>/dev/null | head -30
echo
echo "=== B. find the ninja rule for the from-text stubs javac ==="
grep -n "framework-ondeviceintelligence-platform.stubs.system.from-text" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | grep "javac\|build out" | head -5
echo
echo "=== C. is NonNull public in the prebuilt system android.jar? ==="
SYS=/root/aosp/prebuilts/sdk/current/system/android.jar
cd /tmp && rm -rf nn_check && mkdir nn_check && cd nn_check
unzip -o -q $SYS android/annotation/NonNull.class 2>/dev/null
javap -p android/annotation/NonNull.class 2>/dev/null | head -10
javap android/annotation/NonNull.class 2>/dev/null | head -5
echo
echo "=== D. javap available? ==="
which javap 2>/dev/null
ls /root/aosp/prebuilts/jdk/jdk21/linux-x86/bin/javap 2>/dev/null
echo
echo "=== E. what classpath does the from-text stub javac use - check the failing module rule in ninja ==="
grep -n "OnDeviceIntelligenceManager.java" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -3
echo DONE
