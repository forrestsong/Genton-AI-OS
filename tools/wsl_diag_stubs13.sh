#!/bin/bash
set +e
echo "=== A. find DeviceConfig source anywhere in tree ==="
find /root/aosp/frameworks -name "DeviceConfig.java" 2>/dev/null | head
find /root/aosp -path "*/android/provider/DeviceConfig.java" 2>/dev/null | grep -v out/ | head
echo
echo "=== B. which framework stub jars contain DeviceConfig ==="
for J in $(find /root/aosp/out/soong/.intermediates/frameworks/base/api -name "*.jar" 2>/dev/null | head -12); do
  D=$(unzip -l $J 2>/dev/null | grep -c "android/provider/DeviceConfig")
  B=$(unzip -l $J 2>/dev/null | grep -c "android/os/Binder.class")
  echo "DeviceConfig=$D Binder=$B :: $(basename $J)"
done
echo
echo "=== C. the failing generated FeatureFlagsImpl.java - imports ==="
FF=/root/aosp/out/soong/.intermediates/packages/modules/Profiling/framework/profiling_flags_lib/android_common/javac/srcjars/android/os/profiling/FeatureFlagsImpl.java
ls -la $FF 2>&1
sed -n '1,40p' $FF 2>/dev/null
echo
echo "=== D. aconfig version / tool ==="
ls /root/aosp/out/host/linux-x86/bin/acontext 2>&1
find /root/aosp/out/host/linux-x86/bin -name "aconfig*" 2>/dev/null | head
grep -rn "DeviceConfig" /root/aosp/build/make/tools/aconfig/templates/ 2>/dev/null | head
echo
echo "=== E. aconfig templates for FeatureFlagsImpl ==="
find /root/aosp/build/make/tools/aconfig -name "*.stg" -o -name "*FlagsImpl*" 2>/dev/null | head
grep -rln "import android.os.Binder" /root/aosp/build/make/tools/aconfig/ 2>/dev/null | head
echo
echo "=== F. android_system_server_stubs_current Binder+DeviceConfig ==="
for J in /root/aosp/out/soong/.intermediates/frameworks/base/api/android_system_server_stubs_current/android_common/combined/android_system_server_stubs_current.jar /root/aosp/out/soong/.intermediates/frameworks/base/api/android_system_server_stubs_current/android_common/turbine-combined/android_system_server_stubs_current.jar; do
  echo "--- $(basename $J) ---"
  unzip -l $J 2>/dev/null | grep -c "android/provider/DeviceConfig"
  unzip -l $J 2>/dev/null | grep -c "android/os/Binder.class"
  unzip -l $J 2>/dev/null | tail -1
done
echo
echo "=== G. android_system_stubs_current detail ==="
SYS=/root/aosp/out/soong/.intermediates/frameworks/base/api/android_system_stubs_current/android_common/turbine-combined/android_system_stubs_current.jar
unzip -l $SYS 2>/dev/null | grep -c "android/provider/DeviceConfig"
unzip -l $SYS 2>/dev/null | tail -1
echo DONE
