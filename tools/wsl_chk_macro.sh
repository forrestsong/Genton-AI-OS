#!/bin/bash
set +e
cd /root/aosp/out/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/gen/aidl/
for j in aidl*.srcjar; do
  if unzip -l "$j" 2>/dev/null | grep -q "MacroStep.java"; then
    echo "FOUND in $j"
    unzip -p "$j" "*/com/ainative/macro/MacroStep.java" 2>/dev/null | grep -oE "public (int|long|String|boolean) [a-zA-Z]+" | head -20
  fi
done
echo DONE
