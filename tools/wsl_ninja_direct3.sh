#!/bin/bash
set +e
cd /root/aosp
echo "=== ninja direct build of services.core.unboosted $(date) ==="
timeout 5400 /root/aosp/prebuilts/build-tools/linux-x86/bin/ninja -f out/build-aosp_cpday10.ninja services.core.unboosted 2>&1 | tail -100
echo "=== EXIT: ${PIPESTATUS[0]} ==="
echo "=== done $(date) ==="
