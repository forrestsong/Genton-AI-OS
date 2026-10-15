#!/bin/bash
set +e
cd /root/aosp
source build/envsetup.sh >/dev/null 2>&1
lunch aosp_cpday10-ap3a-eng >/dev/null 2>&1
echo "=== direct ninja target for services.core.unboosted ==="
# find the ninja target name
NINJA_TARGET=$(grep -oE "services\.core\.unboosted[^ :]*" out/build-aosp_cpday10.ninja 2>/dev/null | head -3)
echo "candidates: $NINJA_TARGET"
echo
echo "=== try building the phony/all-services target directly with ninja ==="
cd out 2>/dev/null || cd /root/aosp
timeout 3000 ninja -f build-aosp_cpday10.ninja services.core.unboosted 2>&1 | tail -60
echo "=== EXIT: $? ==="
