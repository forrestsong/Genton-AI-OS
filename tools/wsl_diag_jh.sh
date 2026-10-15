#!/bin/bash
set +e
echo "=== A. javaHeaderLibsSdkMemberType definition ==="
grep -rn "javaHeaderLibsSdkMemberType" /root/aosp/build/soong/java/*.go 2>/dev/null | head
echo
echo "=== B. which module types register as javaHeaderLibs sdk member ==="
grep -rn "javaHeaderLibs\|JavaHeaderLibs" /root/aosp/build/soong/java/*.go 2>/dev/null | head -20
echo
echo "=== C. prebuilt_java_library module type exists? ==="
grep -rn "RegisterPrebuiltJavaLibrary\|prebuilt_java_library\|PrebuiltJavaLibrary" /root/aosp/build/soong/java/prebuilt.go 2>/dev/null | head
echo
echo "=== D. java_import registration and whether it provides JavaInfo ==="
grep -rn "java_import\|JavaImport" /root/aosp/build/soong/java/java_import.go 2>/dev/null | head
ls /root/aosp/build/soong/java/java_import.go 2>/dev/null
echo
echo "=== E. how does java_import provide HeaderJars / JavaInfoProvider ==="
grep -n "HeaderJars\|JavaInfoProvider\|combined\|HeaderJars" /root/aosp/build/soong/java/java_import.go 2>/dev/null | head
echo DONE
