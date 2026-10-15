#!/bin/bash
set +e
echo "=== A. core-lambda-stubs-for-system-modules - search all bp ==="
grep -rln "core-lambda-stubs-for-system-modules" /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head
echo
echo "=== B. sdk33 Android.bp - prebuilt module types used ==="
grep -n "prebuilt_java_library\|java_import\|prebuilt_api" /root/aosp/prebuilts/sdk/33/Android.bp 2>/dev/null | head
echo
echo "=== C. how are prebuilt sdk jars exposed as modules? check prebuilts/sdk/common or soong prebuilt_apis ==="
grep -rln "prebuilt_apis\|sdk_jar\|PrebuiltSdk" /root/aosp/build/soong/ 2>/dev/null | head
echo
echo "=== D. does a 'system_android_jar' filegroup get consumed anywhere as a module? ==="
grep -rn "system_android_jar" /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head
echo
echo "=== E. try defining prebuilt_java_library - check its valid props (soong docs) ==="
grep -rn "prebuilt_java_library" /root/aosp/build/soong/java/java.go /root/aosp/build/soong/java/prebuilt.go 2>/dev/null | head
echo
echo "=== F. check for existing prebuilt_java_library usage anywhere ==="
grep -rln "prebuilt_java_library {" /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head -5
echo
echo "=== G. does anything already import prebuilts/sdk/current/system/android.jar? ==="
grep -rln "sdk/current/system/android.jar" /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head
echo DONE
