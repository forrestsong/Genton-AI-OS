#!/bin/bash
set +e
echo "=== A. config.go BuildFromTextStub implementation ==="
sed -n '2275,2295p' /root/aosp/build/soong/android/config.go 2>/dev/null
echo
echo "=== B. how product variable Build_from_text_stub gets set (from product config / make) ==="
grep -rn "Build_from_text_stub" /root/aosp/build/soong/ 2>/dev/null --include="*.go" | head
echo
echo "=== C. search for how it's wired from make/product config ==="
grep -rn "build_from_text_stub\|BUILD_FROM_TEXT_STUB\|from_text_stub" /root/aosp/build/make/ 2>/dev/null | head
echo
echo "=== D. is there a product_config / bpconfig mechanism? check if it can be set via PRODUCT ==="
grep -rn "Build_from_text_stub\|build_from_text_stub" /root/aosp/build/soong/ui/build/config.go 2>/dev/null | head
echo
echo "=== E. look at ui/build/config.go:1227 context (how the configImpl decides) ==="
sed -n '1220,1245p' /root/aosp/build/soong/ui/build/config.go 2>/dev/null
echo
echo "=== F. does it come from a make variable or soong config? check variable.go context ==="
sed -n '765,780p' /root/aosp/build/soong/android/variable.go 2>/dev/null
echo
echo "=== G. search for how OTHER bool product vars like this are set (e.g. via PRODUCT_SOONG_* or bpconfig) ==="
grep -rn "boolPtr(false)" /root/aosp/build/soong/android/variable.go 2>/dev/null | head -20
echo DONE
