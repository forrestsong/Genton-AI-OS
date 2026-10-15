#!/bin/bash
set +e
echo "=== A. SystemModulesFactory + libs property type ==="
grep -n "SystemModulesFactory\|func.*SystemModules\|Libs\|libs" /root/aosp/build/soong/java/system_modules.go 2>/dev/null | head -40
echo
echo "=== B. module struct + property def ==="
sed -n '80,220p' /root/aosp/build/soong/java/system_modules.go 2>/dev/null
echo DONE
