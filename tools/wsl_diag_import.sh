#!/bin/bash
set +e
echo "=== A. prebuilts/sdk/current/public/android.jar ==="
PUB=/root/aosp/prebuilts/sdk/current/public/android.jar
ls -la $PUB 2>&1
unzip -l $PUB 2>/dev/null | grep -c "android/os/Binder.class"
unzip -l $PUB 2>/dev/null | grep -c "android/provider/DeviceConfig"
unzip -l $PUB 2>/dev/null | tail -1
echo
echo "=== B. does java_import work as static_lib? check how java_import is consumed (ImportSdkMemberType / JavaInfoProvider) ==="
grep -rn "func (m \*JavaImport)\|type JavaImport\|JavaInfoProvider" /root/aosp/build/soong/java/java.go 2>/dev/null | grep -i import | head
grep -rn "JavaImport" /root/aosp/build/soong/java/java.go 2>/dev/null | grep -i "provider\|combined\|header" | head
echo
echo "=== C. java_import provides StaticLibs? check base.go/import handling ==="
grep -rn "JavaImport\|import module" /root/aosp/build/soong/java/base.go 2>/dev/null | head
echo
echo "=== D. existing example: java_import used in static_libs anywhere ==="
grep -rln "static_libs:.*android_current\|android_current" /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head
grep -rn -A5 "android_current" /root/aosp/external/vogar/Android.bp 2>/dev/null | head -20
echo
echo "=== E. check: is java_import a *Library with StaticLibs? look at JavaImport struct ==="
grep -rn "type JavaImport struct" /root/aosp/build/soong/java/java.go 2>/dev/null
grep -rn -A15 "type JavaImport struct" /root/aosp/build/soong/java/java.go 2>/dev/null | head -25
echo DONE
