#!/bin/bash
set +e
echo "=== exact block in AOSP tree ==="
sed -n '408,427p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo "=== line numbers ==="
grep -n "stable-core-platform-api-stubs-system-modules" /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo "=== context 405-425 with line numbers ==="
awk 'NR>=405 && NR<=425 {printf "%d: %s\n", NR, $0}' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo DONE
