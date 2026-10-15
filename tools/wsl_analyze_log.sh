#!/bin/bash
set +e
echo "=== A. all distinct failing modules in build log (lines with 'error:' before 'ninja: build stopped') ==="
grep -B0 "error:" /tmp/services_core_build2.log 2>/dev/null | grep -oE "\.intermediates/[^/]+/[^/]+/[^/]+/[^/]+" | sort -u | head -40
echo
echo "=== B. count of error lines by module path prefix ==="
grep "error:" /tmp/services_core_build2.log 2>/dev/null | grep -oE "out/soong/.intermediates/[^/]+/[^/]+" | sort | uniq -c | sort -rn | head -20
echo
echo "=== C. was framework or services.core failing? ==="
grep "error:" /tmp/services_core_build2.log 2>/dev/null | grep -c "services/core\|frameworks/base/services"
grep "error:" /tmp/services_core_build2.log 2>/dev/null | grep -c "genton"
echo
echo "=== D. the first 'error:' line's full module path (sample 2) ==="
grep "error:" /tmp/services_core_build2.log 2>/dev/null | head -2
echo
echo "=== E. total error count ==="
grep -c "error:" /tmp/services_core_build2.log 2>/dev/null
echo
echo "=== F. does services.core.unboosted depend on those from-text stubs? check ninja deps ==="
grep -c "ondeviceintelligence-platform.stubs.system.from-text" /tmp/services_core_build2.log 2>/dev/null
echo DONE
