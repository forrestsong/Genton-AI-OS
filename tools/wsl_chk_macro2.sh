#!/bin/bash
set +e
cd /root/aosp/out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/gen/aidl/
rm -rf /tmp/msj && mkdir -p /tmp/msj
unzip -o -q aidl28.srcjar "*/com/genton/macro/MacroStep.java" -d /tmp/msj 2>/dev/null
F=$(find /tmp/msj -name 'MacroStep.java' | head -1)
echo "FILE: $F"
echo "=== fields ==="
grep -n "public\|private" "$F" 2>/dev/null | head -30
echo "=== head ==="
head -40 "$F" 2>/dev/null
echo DONE
