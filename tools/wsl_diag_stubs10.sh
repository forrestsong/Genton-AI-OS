#!/bin/bash
set +e
echo "=== A. AconfigFlags.bp full (grep for defaults def) ==="
grep -n -A25 "framework-minus-apex-aconfig-java-defaults" /root/aosp/frameworks/base/AconfigFlags.bp 2>/dev/null | head -60
echo
echo "=== B. whole AconfigFlags.bp (first 120 lines) ==="
sed -n '1,120p' /root/aosp/frameworks/base/AconfigFlags.bp 2>/dev/null
echo
echo "=== C. frameworks/base/api/Android.bp line 460-500 (core_platform usage) ==="
sed -n '460,500p' /root/aosp/frameworks/base/api/Android.bp 2>/dev/null
echo
echo "=== D. what system_modules does 'core_platform' sdk_version map to? check soong sdk version mapping ==="
grep -rn "core_platform" /root/aosp/build/soong/java/sdk_versions.go 2>/dev/null | head -20
echo DONE
