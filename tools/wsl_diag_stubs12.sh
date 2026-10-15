#!/bin/bash
set +e
echo "=== A. framework android stubs jars - find them all ==="
find /root/aosp/out/soong/.intermediates/frameworks/base -name "*.jar" 2>/dev/null | grep -iE "stubs_current|stubs.*current|android_stubs" | head -20
echo
echo "=== B. android_stubs_current jar Binder check ==="
for J in $(find /root/aosp/out/soong/.intermediates/frameworks/base -path "*android_stubs_current*" -name "*.jar" 2>/dev/null | head -4); do
  echo "--- $J ---"
  ls -la $J 2>/dev/null | awk '{print $5, $9}'
  unzip -l $J 2>/dev/null | grep -c "android/os/Binder.class"
  unzip -l $J 2>/dev/null | grep -c "android/provider/DeviceConfig.class"
done
echo
echo "=== C. android_module_lib_stubs_current / system stubs Binder ==="
for J in $(find /root/aosp/out/soong/.intermediates/frameworks/base -path "*android_module_lib_stubs_current*" -o -path "*android_system_stubs_current*" -o -path "*android_test_stubs_current*" 2>/dev/null | grep "\.jar$" | head -6); do
  echo "--- $J ---"
  unzip -l $J 2>/dev/null | grep -c "android/os/Binder.class"
done
echo
echo "=== D. does android_stubs_current combined jar exist at all? ==="
find /root/aosp/out/soong/.intermediates/frameworks/base -path "*android_stubs_current*combined*" 2>/dev/null | head
echo
echo "=== E. metalava-generated framework stub sources (from-source) - check a jar with many android classes ==="
for J in $(find /root/aosp/out/soong/.intermediates/frameworks/base/api -name "*.jar" 2>/dev/null | head -8); do
  N=$(unzip -l $J 2>/dev/null | grep -c "\.class")
  B=$(unzip -l $J 2>/dev/null | grep -c "android/os/Binder.class")
  echo "$N classes, $B Binder -> $J"
done
echo
echo "=== F. android-non-updatable.stubs.from-text jar Binder ==="
FT=/root/aosp/out/soong/.intermediates/frameworks/base/api/android-non-updatable.stubs.from-text/android_common/metalava/stubs.jar
unzip -l $FT 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $FT 2>/dev/null | tail -1
echo
echo "=== G. what system_modules does system_current map to (reference for a complete image)? ==="
grep -rn "system_current" /root/aosp/build/soong/java/sdk_versions.go 2>/dev/null | grep -i "system_modules\|stubs" | head
echo
echo "=== H. list java_system_modules modules ==="
grep -rn "java_system_modules {" /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
grep -n -A6 "java_system_modules {" /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null | head -60
echo DONE
