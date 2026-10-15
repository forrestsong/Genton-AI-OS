#!/bin/bash
set +e
echo "=== A. fake_device_config jar - find and inspect ==="
find /root/aosp/out/soong/.intermediates/build/make/tools/aconfig/fake_device_config -name "*.jar" 2>/dev/null | head
for J in $(find /root/aosp/out/soong/.intermediates/build/make/tools/aconfig/fake_device_config -name "*.jar" 2>/dev/null | head -4); do
  echo "--- $J ---"
  ls -la $J 2>/dev/null | awk '{print $5, $9}'
  unzip -l $J 2>/dev/null | tail -1
  echo "  Binder:" $(unzip -l $J 2>/dev/null | grep -c "android/os/Binder")
  echo "  DeviceConfig:" $(unzip -l $J 2>/dev/null | grep -c "android/provider/DeviceConfig")
  echo "  all entries sample:"
  unzip -l $J 2>/dev/null | grep "\.class" | head -10
done
echo
echo "=== B. fake_device_config source ==="
find /root/aosp/build/make/tools/aconfig/fake_device_config -type f 2>/dev/null | head -20
echo
echo "=== C. fake_device_config module def (bp) ==="
grep -rn -A15 "fake_device_config" /root/aosp/build/make/tools/aconfig/*.bp /root/aosp/build/make/tools/aconfig/**/*.bp 2>/dev/null | head -40
echo
echo "=== D. what does fake_device_config provide - read its java sources ==="
find /root/aosp/build/make/tools/aconfig/fake_device_config -name "*.java" 2>/dev/null | head
echo DONE
