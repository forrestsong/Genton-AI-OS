#!/bin/bash
set +e
cd /root/aosp
export BUILD_FROM_SOURCE_STUB=true
source build/envsetup.sh >/dev/null 2>&1
lunch aosp_cpday10-ap3a-eng >/dev/null 2>&1
echo "=== BUILD_FROM_SOURCE_STUB=$BUILD_FROM_SOURCE_STUB ==="
echo "=== full build with from-source stubs $(date) ==="
timeout 6000 m services.core.unboosted 2>&1 | tail -150
echo "=== EXIT: ${PIPESTATUS[0]} ==="
echo "=== done $(date) ==="
