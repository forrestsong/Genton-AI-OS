#!/bin/bash
set +e
SYS=/root/aosp/prebuilts/sdk/current/system/android.jar
JDK=/root/aosp/prebuilts/jdk/jdk21/linux-x86/bin
echo "=== A. NonNull in prebuilt system android.jar ==="
cd /tmp && rm -rf nn2 && mkdir nn2 && cd nn2
unzip -o -q $SYS android/annotation/NonNull.class 2>&1 | head
$JDK/javap android.annotation.NonNull 2>&1 | head -8
echo
echo "=== B. also check Nullable and public android.jar ==="
PUB=/root/aosp/prebuilts/sdk/current/public/android.jar
unzip -o -q $PUB android/annotation/NonNull.class -d pub 2>&1
$JDK/javap -cp pub android.annotation.NonNull 2>&1 | head -8
echo
echo "=== C. where is NonNull source (should be frameworks/base) ==="
find /root/aosp/frameworks -name "NonNull.java" -path "*android/annotation*" 2>/dev/null | head
grep -rn "public @interface NonNull\|interface NonNull" /root/aosp/frameworks/base/core/java/android/annotation/NonNull.java 2>/dev/null
echo
echo "=== D. which system modules does the ondeviceintelligence from-text stub use? find its def ==="
grep -rn -B3 -A10 "framework-ondeviceintelligence-platform.stubs.system" /root/aosp/frameworks/base/packages/NeuralNetworks/ 2>/dev/null --include="*.bp" | head -40
echo
echo "=== E. is android.annotation.NonNull in the from-text generated android-non-updatable stubs? ==="
FT=/root/aosp/out/soong/.intermediates/frameworks/base/api/android-non-updatable.stubs.from-text/android_common/metalava/stubs.jar
unzip -l $FT 2>/dev/null | grep "android/annotation/NonNull" | head
cd /tmp && rm -rf ft2 && mkdir ft2 && cd ft2
unzip -o -q $FT android/annotation/NonNull.class 2>&1 | head
$JDK/javap android.annotation.NonNull 2>&1 | head -8
echo DONE
