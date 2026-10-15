#!/bin/bash
set +e
cd /root/aosp
source build/envsetup.sh >/dev/null 2>&1
lunch aosp_cpday10-ap3a-eng >/dev/null 2>&1
echo "=== FULL services.core.unboosted build start $(date) ==="
timeout 6000 m services.core.unboosted 2>&1 | tee /tmp/services_core_build2.log | tail -200
echo "=== EXIT: ${PIPESTATUS[0]} ==="
echo "=== done $(date) ==="
