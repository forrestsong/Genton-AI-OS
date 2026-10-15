#!/bin/bash
set +e
echo "=== A. how is from-text vs from-source decided in Soong? ==="
grep -rn "from-text\|from_text\|FromText\|from source" /root/aosp/build/soong/java/droidstubs.go 2>/dev/null | head -20
echo
echo "=== B. search for the flag/switch ==="
grep -rn "from-text" /root/aosp/build/soong/java/*.go 2>/dev/null | grep -i "config\|product\|env\|flag" | head -10
echo
echo "=== C. is there a bpconfig / product flag like USE_FROM_TEXT or api text switch? ==="
grep -rn "from_text\|FromText\|use.*text.*api\|api.*from.*text" /root/aosp/build/soong/ 2>/dev/null --include="*.go" | grep -iv "test\|module_lib\|stubs" | head -20
echo
echo "=== D. search for 'from-text' module naming logic (which modules get from-text variant) ==="
grep -rn "from-text" /root/aosp/build/soong/java/java_sdk_library.go /root/aosp/build/soong/java/sdk_library.go 2>/dev/null | head -10
echo
echo "=== E. check if there's a config to skip from-text (search all soong for the variant creation) ==="
grep -rn "\.from-text" /root/aosp/build/soong/java/*.go 2>/dev/null | grep -i "create\|register\|variant\|add" | head -10
echo
echo "=== F. look at how stubs module decides from-text: the CreateStubsVariation logic ==="
grep -rn -B5 -A20 "from-text" /root/aosp/build/soong/java/java_sdk_library.go 2>/dev/null | head -50
echo DONE
