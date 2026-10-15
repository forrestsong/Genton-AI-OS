#!/bin/bash
set +e
echo "=== A. git HEAD version of FeatureFlagsImpl.deviceConfig template: does it import Binder/DeviceConfig? ==="
cd /root/aosp/build/make
git show HEAD:tools/aconfig/aconfig/templates/FeatureFlagsImpl.deviceConfig.java.template 2>/dev/null | grep -n "import android" | head -20
echo "--- working tree template imports ---"
grep -n "import android" /root/aosp/build/make/tools/aconfig/aconfig/templates/FeatureFlagsImpl.deviceConfig.java.template 2>/dev/null | head -20
echo
echo "=== B. is template modified vs git? ==="
git -C /root/aosp/build/make diff HEAD -- tools/aconfig/aconfig/templates/ 2>/dev/null | head -20
echo
echo "=== C. is the generated source dir (aconfig codegen) modified? ==="
git -C /root/aosp/build/make diff HEAD -- tools/aconfig/aconfig/src/codegen/ 2>/dev/null | head -10
echo
echo "=== D. aconfig tool version / where does codegen live ==="
grep -rn "deviceConfig\|Binder" /root/aosp/build/make/tools/aconfig/aconfig/src/codegen/java.rs 2>/dev/null | head
echo
echo "=== E. git log of the deviceConfig template (recent) ==="
git -C /root/aosp/build/make log --oneline -5 -- tools/aconfig/aconfig/templates/FeatureFlagsImpl.deviceConfig.java.template 2>/dev/null | head
echo
echo "=== F. the actual template content (full imports section) ==="
sed -n '1,30p' /root/aosp/build/make/tools/aconfig/aconfig/templates/FeatureFlagsImpl.deviceConfig.java.template 2>/dev/null
echo DONE
