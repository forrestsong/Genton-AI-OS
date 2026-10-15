#!/bin/bash
set +e
echo "=== A. find build stmt producing OnDeviceIntelligenceManager.java classes (the from-text javac output) ==="
grep -n "OnDeviceIntelligenceManager.java" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -5
echo
echo "=== B. find the javac rule used by that module (search for the output jar) ==="
grep -n "framework-ondeviceintelligence-platform.stubs.system.from-text/android_common/javac" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -5
echo
echo "=== C. extract the rule + command for that javac (grep the ninja build stmt and following rule def) ==="
NINJA=/root/aosp/out/build-aosp_cpday10.ninja
LINE=$(grep -n "framework-ondeviceintelligence-platform.stubs.system.from-text/android_common/javac" $NINJA 2>/dev/null | head -1 | cut -d: -f1)
echo "first match line: $LINE"
if [ -n "$LINE" ]; then
  sed -n "${LINE},$((LINE+3))p" $NINJA 2>/dev/null | cut -c1-2000
fi
echo
echo "=== D. search the --system flag used across all javac rules (extract unique system module paths) ==="
grep -oE "stable-core-platform-api-stubs-system-modules/android_common/system|core-current-stubs-system-modules/android_common/system|android-system-stubs-system-modules[^ \"]*|core-for-system-modules/android_common/system" $NINJA 2>/dev/null | sort | uniq -c | sort -rn | head
echo
echo "=== E. is there an android-system-stubs-system-modules java_system_modules? ==="
grep -rn "android-system-stubs-system-modules" /root/aosp/ --include="*.bp" 2>/dev/null | grep -v out/ | head
echo
echo "=== F. what system modules do 'system' api stubs use - check the from-text stub module type config ==="
grep -rn "from-text" /root/aosp/build/soong/java/droidstubs.go 2>/dev/null | head
grep -n "from-text" /root/aosp/frameworks/base/api/StubLibraries.bp 2>/dev/null | head
echo DONE
