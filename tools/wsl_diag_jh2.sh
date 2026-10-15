#!/bin/bash
set +e
echo "=== A. librarySdkMemberType in java.go:95-160 ==="
sed -n '95,170p' /root/aosp/build/soong/java/java.go 2>/dev/null
echo
echo "=== B. java_import registration location ==="
grep -rn "java_import" /root/aosp/build/soong/java/*.go 2>/dev/null | grep -i "register\|moduletype\|factory" | head
grep -rn "func.*JavaImport\|JavaImportFactory" /root/aosp/build/soong/java/*.go 2>/dev/null | head
echo
echo "=== C. java_import.go / import.go files ==="
ls /root/aosp/build/soong/java/ | grep -i import
echo
echo "=== D. how java_import provides jars - check ImportSdkMemberType or similar ==="
grep -rn "HeaderJars\|JavaInfoProvider" /root/aosp/build/soong/java/import.go 2>/dev/null | head
echo DONE
