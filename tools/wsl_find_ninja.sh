#!/bin/bash
set +e
echo "=== locate ninja ==="
ls -la /root/aosp/prebuilts/build-tools/linux-x86/bin/ninja 2>&1 | head -2
ls -la /root/aosp/out/host/linux-x86/bin/ninja 2>&1 | head -2
which ninja 2>&1
echo DONE
