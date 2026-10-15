#!/bin/bash
set +e
SYS=/root/aosp/prebuilts/sdk/current/system/android.jar
JDK=/root/aosp/prebuilts/jdk/jdk21/linux-x86/bin
echo "=== A. control: javap Binder (should show public) ==="
cd /tmp && rm -rf ctrl && mkdir ctrl && cd ctrl
unzip -o -q $SYS android/os/Binder.class 2>&1 | head
$JDK/javap android.os.Binder 2>&1 | head -3
echo
echo "=== B. NonNull again with -public flag ==="
cd /tmp/nn2 2>/dev/null || cd /tmp && rm -rf nn3 && mkdir nn3 && cd nn3
unzip -o -q $SYS android/annotation/NonNull.class 2>&1 | head
$JDK/javap -public android.annotation.NonNull 2>&1 | head -5
echo
echo "=== C. modules-utils NonNull.java content ==="
cat /root/aosp/frameworks/libs/modules-utils/java/android/annotation/NonNull.java 2>&1 | head -30
echo
echo "=== D. frameworks/base NonNull.java exists? ==="
ls -la /root/aosp/frameworks/base/core/java/android/annotation/NonNull.java 2>&1
ls /root/aosp/frameworks/base/core/java/android/annotation/ 2>&1 | head -30
echo
echo "=== E. compare: does prebuilt have other android.annotation classes? ==="
unzip -l $SYS 2>/dev/null | grep "android/annotation/" | head -20
echo
echo "=== F. count android.annotation in prebuilt ==="
unzip -l $SYS 2>/dev/null | grep -c "android/annotation/"
echo DONE
