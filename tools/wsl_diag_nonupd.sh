#!/bin/bash
set +e
JDK=/root/aosp/prebuilts/jdk/jdk21/linux-x86/bin
echo "=== A. android-non-updatable.stubs (from-source) NonNull ==="
J1=/root/aosp/out/soong/.intermediates/frameworks/base/api/android-non-updatable.stubs/android_common/turbine-combined/android-non-updatable.stubs.jar
ls -la $J1 2>&1 | head -2
cd /tmp && rm -rf nu && mkdir nu && cd nu
unzip -o -q $J1 android/annotation/NonNull.class 2>&1 | head -2
$JDK/javap android.annotation.NonNull 2>&1 | head -3
echo
echo "=== B. framework jar NonNull (real framework, has @hide as public?) ==="
J2=$(find /root/aosp/out/soong/.intermediates/frameworks/base/framework -name "*.jar" -path "*turbine-combined*" 2>/dev/null | head -1)
echo "framework jar: $J2"
if [ -n "$J2" ]; then
  cd /tmp && rm -rf fw && mkdir fw && cd fw
  unzip -o -q "$J2" android/annotation/NonNull.class 2>&1 | head -2
  $JDK/javap android.annotation.NonNull 2>&1 | head -3
fi
echo
echo "=== C. does framework jar exist at all? ==="
ls -la /root/aosp/out/soong/.intermediates/frameworks/base/framework/ 2>/dev/null | head -5
echo
echo "=== D. what stubs does the from-text location module compile against? its 'libs' in bp ==="
grep -rn -B5 -A20 "framework-location.stubs.module_lib" /root/aosp/frameworks/base/location/Android.bp 2>/dev/null | head -40
echo
echo "=== E. the from-text stub compilation: does it use android-non-updatable.stubs or the prebuilt? check from-text base stubs def in StubLibraries.bp ==="
sed -n '605,660p' /root/aosp/frameworks/base/api/StubLibraries.bp 2>/dev/null
echo DONE
