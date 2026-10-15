#!/bin/bash
set +e
echo "=== A. core-lambda-stubs-for-system-modules in libcore/JavaLibrary.bp ==="
grep -n -B5 -A20 "core-lambda-stubs-for-system-modules" /root/aosp/libcore/JavaLibrary.bp 2>/dev/null | head -50
echo
echo "=== B. in frameworks/base/api/StubLibraries.bp ==="
grep -n -B5 -A15 "core-lambda-stubs-for-system-modules" /root/aosp/frameworks/base/api/StubLibraries.bp 2>/dev/null | head -30
echo
echo "=== C. prebuilt_java_library in soong source (does it exist?) ==="
grep -rn "prebuilt_java_library\|prebuilt.*java.*library" /root/aosp/build/soong/java/prebuilt.go 2>/dev/null | head -10
grep -rn "RegisterModuleType.*prebuilt" /root/aosp/build/soong/java/*.go 2>/dev/null | head -10
echo
echo "=== D. java_import vs prebuilt - check java_import registration ==="
grep -rn "java_import" /root/aosp/build/soong/java/*.go 2>/dev/null | grep -i "register\|moduletype" | head
echo
echo "=== E. does java_import provide JavaInfoProvider? check java_import.go ==="
ls /root/aosp/build/soong/java/*import* 2>/dev/null
grep -n "JavaInfoProvider\|HeaderJars" /root/aosp/build/soong/java/java_import.go 2>/dev/null | head
echo DONE
