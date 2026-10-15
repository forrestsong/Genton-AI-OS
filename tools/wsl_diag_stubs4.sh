#!/bin/bash
set +e
echo "=== A. ninja rule inputs for stable.core.platform.api.no.annotations.stubs jar ==="
grep -o "stable\.core\.platform\.api\.no\.annotations\.stubs/android_common/turbine-combined/stable\.core\.platform\.api\.no\.annotations\.stubs\.jar[^\"]*" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -3
echo
echo "=== B. find the build rule that produces the stubs jar (show rule name) ==="
grep -n "stable\.core\.platform\.api\.no\.annotations\.stubs/android_common/turbine-combined/stable\.core\.platform\.api\.no\.annotations\.stubs\.jar" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -5
echo
echo "=== C. rule inputs: grep for metalava gen of this module ==="
grep -n "metalava" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | grep -i "stable.core.platform.api" | head -5
echo
echo "=== D. what .jar inputs feed the stubs jar (check combined rule) ==="
grep -n "stable\.core\.platform\.api\.stubs/android_common/combined/stable\.core\.platform\.api\.stubs\.jar" /root/aosp/out/build-aosp_cpday10.ninja 2>/dev/null | head -5
echo
echo "=== E. is there a metalava stubs gen dir for framework? ==="
find /root/aosp/out/soong/.intermediates/frameworks/base -maxdepth 1 -name "*stubs*" 2>/dev/null | head
find /root/aosp/out/soong/.intermediates/frameworks/base -maxdepth 2 -name "*stubs*" -type d 2>/dev/null | head -15
echo
echo "=== F. how big is a proper current.txt in git? check if git available ==="
cd /root/aosp && git -C frameworks/base log --oneline -1 2>&1 | head -2
git -C frameworks/base status --short frameworks/base/api 2>&1 | head -10
echo
echo "=== G. was api dir ever populated? check coverage subdir ==="
ls /root/aosp/frameworks/base/api/coverage/ 2>/dev/null | head
echo DONE
