#!/bin/bash
set +e
echo "=== log file check ==="
ls -la /tmp/services_core_build2.log 2>&1
wc -l /tmp/services_core_build2.log 2>&1
echo
echo "=== grep error in log ==="
grep -c "error:" /tmp/services_core_build2.log 2>&1
echo
echo "=== failing module lines ==="
grep "FAILED:\|failed to build\|error:" /tmp/services_core_build2.log 2>&1 | head -20
echo
echo "=== any 'FAILED:' lines ==="
grep "FAILED:" /tmp/services_core_build2.log 2>&1 | head -20
echo DONE
