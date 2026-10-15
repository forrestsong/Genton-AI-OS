#!/bin/bash
set +e
JDK=/root/aosp/prebuilts/jdk/jdk21/linux-x86/bin
echo "=== A. prebuilt system/android.jar Context - extract properly ==="
cd /tmp && rm -rf b1 && mkdir b1 && cd b1
unzip -o -q /root/aosp/prebuilts/sdk/current/system/android.jar "android/content/Context.class" 2>&1
ls -la android/content/Context.class 2>&1
$JDK/javap -constants android.content.Context 2>/dev/null | grep -c "COUNTRY_DETECTOR"
$JDK/javap -constants android.content.Context 2>/dev/null | grep -c "BIND_AUTO_CREATE"
echo
echo "=== B. from-source android_system_stubs_current.jar - Context/Binder/DeviceConfig ==="
SJ=/root/aosp/out/soong/.intermediates/frameworks/base/api/android_system_stubs_current/android_common/turbine-combined/android_system_stubs_current.jar
ls -la $SJ 2>&1
cd /tmp && rm -rf b2 && mkdir b2 && cd b2
unzip -o -q $SJ "android/content/Context.class" "android/os/Binder.class" "android/provider/DeviceConfig.class" 2>&1
echo "extracted:"
ls -la android/content/Context.class android/os/Binder.class android/provider/DeviceConfig.class 2>&1
echo "Context COUNTRY_DETECTOR:"
$JDK/javap -constants -cp . android.content.Context 2>/dev/null | grep -c "COUNTRY_DETECTOR"
echo "Binder:"
$JDK/javap -cp . android.os.Binder 2>/dev/null | head -3
echo "DeviceConfig:"
$JDK/javap -cp . android.provider.DeviceConfig 2>/dev/null | head -3
echo
echo "=== C. is from-source jar complete for the core platform needs? count classes ==="
unzip -l $SJ 2>/dev/null | tail -1
echo
echo "=== D. does the from-source android_system_stubs_current have NON-public NonNull too? (if yes, from-text still can't use it) ==="
cd /tmp && rm -rf b3 && mkdir b3 && cd b3
unzip -o -q $SJ "android/annotation/NonNull.class" 2>&1
$JDK/javap android.annotation.NonNull 2>&1 | head -2
echo DONE
