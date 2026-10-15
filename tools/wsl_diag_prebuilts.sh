#!/bin/bash
set +e
echo "=== A. prebuilts/sdk/current contents ==="
ls -la /root/aosp/prebuilts/sdk/current/ 2>/dev/null | head -40
echo
echo "=== B. android.jar in prebuilts ==="
find /root/aosp/prebuilts/sdk -name "android.jar" 2>/dev/null | head -10
echo
echo "=== C. system stubs prebuilt (system android.jar)? ==="
find /root/aosp/prebuilts/sdk -name "*.jar" 2>/dev/null | grep -iE "system|core" | head -10
echo
echo "=== D. check android.jar (latest) for Binder + DeviceConfig ==="
AJ=$(find /root/aosp/prebuilts/sdk -name "android.jar" 2>/dev/null | sort | tail -1)
echo "using: $AJ"
ls -la $AJ 2>&1
unzip -l $AJ 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $AJ 2>/dev/null | grep -c "android/provider/DeviceConfig"
unzip -l $AJ 2>/dev/null | tail -1
echo
echo "=== E. prebuilt system modules? look for java_system_modules_import / prebuilt system image ==="
find /root/aosp/prebuilts/sdk -name "*.jmod" 2>/dev/null | head
find /root/aosp/prebuilts/sdk -name "modules" -o -name "jrt-fs.jar" 2>/dev/null | head
echo
echo "=== F. does prebuilts/sdk/current have a 'core' dir with system modules? ==="
ls -la /root/aosp/prebuilts/sdk/current/core/ 2>/dev/null
find /root/aosp/prebuilts/sdk/current/core -maxdepth 3 2>/dev/null | head -20
echo
echo "=== G. look for 'system' prebuilt stubs jar ==="
find /root/aosp/prebuilts/sdk -path "*system*" -name "*.jar" 2>/dev/null | head
echo DONE
