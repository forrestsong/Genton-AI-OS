#!/bin/bash
set +e
echo "=== A. build/soong/java/core-libraries/Android.bp lines 280-400 ==="
sed -n '280,400p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== B. first 120 lines (core.current.stubs.defaults context) ==="
sed -n '1,130p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== C. android_stubs_current in frameworks/base/api/Android.bp ==="
grep -n "android_stubs_current\|name: \"android.stubs\|android_system_stubs_current\|android_module_lib_stubs_current" /root/aosp/frameworks/base/api/Android.bp 2>/dev/null | head -20
echo DONE
