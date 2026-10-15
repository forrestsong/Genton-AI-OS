#!/bin/bash
set +e
echo "=== A. how sdk33 imports core-for-system-modules.jar ==="
grep -rn -B5 -A15 "core-for-system-modules" /root/aosp/prebuilts/sdk/33/Android.bp 2>/dev/null | head -60
echo
echo "=== B. prebuilt_java_library for system/android.jar anywhere ==="
grep -rn "prebuilt_java_library" /root/aosp/prebuilts/sdk/current/Android.bp 2>/dev/null | head
echo
echo "=== C. core-lambda-stubs-for-system-modules def ==="
grep -rn -B3 -A15 "core-lambda-stubs-for-system-modules" /root/aosp/libcore/Android.bp 2>/dev/null | head -40
echo
echo "=== D. find any prebuilt_java_library that wraps sdk android.jar ==="
grep -rln "prebuilt_java_library" /root/aosp/prebuilts/sdk/ 2>/dev/null | head
echo DONE
