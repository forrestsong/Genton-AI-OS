#!/bin/bash
set +e
echo "=== A. full modules-utils NonNull.java (declaration part) ==="
sed -n '30,60p' /root/aosp/frameworks/libs/modules-utils/java/android/annotation/NonNull.java 2>/dev/null
echo
echo "=== B. full modules-utils Nullable.java declaration ==="
sed -n '25,60p' /root/aosp/frameworks/libs/modules-utils/java/android/annotation/Nullable.java 2>/dev/null
echo
echo "=== C. what --system does from-text stub javac use? check ninja for the ondeviceintelligence from-text rule ==="
grep -oE "\-\-system=[^ ]*system[^ ]*" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | sort -u | head -20
echo
echo "=== D. from-text stub compile: does it use core-current-stubs-system-modules or android system stubs? ==="
grep -oE "\-\-system=[^ ]*stubs[^ ]*system[^ ]*" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | sort -u | head
echo
echo "=== E. grep a from-text javac command for a stub module ==="
grep -oE "rule[0-9]+: .*javac.*from-text" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -2
grep -B2 "framework-ondeviceintelligence-platform.stubs.system.from-text/android_common/javac" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -5
echo DONE
