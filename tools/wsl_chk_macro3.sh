#!/bin/bash
set +e
cd /root/aosp/out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/gen/aidl/
unzip -l aidl28.srcjar 2>/dev/null | grep MacroStep | head -3
echo "=== content ==="
unzip -p aidl28.srcjar | grep -A40 "class MacroStep" 2>/dev/null | head -50
echo DONE
