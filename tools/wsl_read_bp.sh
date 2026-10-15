#!/bin/bash
set +e
echo "=== A. core-libraries/Android.bp 200-340 ==="
sed -n '200,340p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo
echo "=== B. 340-400 ==="
sed -n '340,400p' /root/aosp/build/soong/java/core-libraries/Android.bp 2>/dev/null
echo DONE
