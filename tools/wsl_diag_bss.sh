#!/bin/bash
set +e
echo "=== A. BuildFromSourceStub product variable - definition and usage ==="
grep -rn "BuildFromSourceStub\|build_from_source_stub\|Build_from_source_stub" /root/aosp/build/soong/ 2>/dev/null --include="*.go" | head -20
echo
echo "=== B. how is it wired from product config / make? search bpconfig / product config for it ==="
grep -rn "build_from_source_stub\|BuildFromSourceStub" /root/aosp/build/soong/ui/ 2>/dev/null --include="*.go" | head
echo
echo "=== C. search make files ==="
grep -rn "BUILD_FROM_SOURCE_STUB\|build_from_source_stub\|BuildFromSourceStub" /root/aosp/build/make/ 2>/dev/null | head
echo
echo "=== D. how do OTHER product variables get set from product config? look at product_config / bpconfig mechanism ==="
grep -rn "ProductVariable\|productVariables" /root/aosp/build/soong/java/sdk_library.go 2>/dev/null | head -5
echo
echo "=== E. find how 'BuildFromSourceStub' might be set via a soong config namespace ==="
grep -rn "from_source_stub\|from-source-stub" /root/aosp/build/soong/ 2>/dev/null --include="*.go" | grep -iv test | head
echo
echo "=== F. check sdk_library.go around 640-660 (Build_from_text_stub property) and 1735-1745 ==="
sed -n '640,660p' /root/aosp/build/soong/java/sdk_library.go 2>/dev/null
sed -n '1735,1745p' /root/aosp/build/soong/java/sdk_library.go 2>/dev/null
echo DONE
