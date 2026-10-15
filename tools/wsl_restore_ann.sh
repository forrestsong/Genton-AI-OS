#!/bin/bash
set +e
FB=/root/aosp/frameworks/base/core/java/android/annotation
MU=/root/aosp/frameworks/libs/modules-utils/java/android/annotation
echo "=== copying NonNull.java and Nullable.java ==="
cp -v $MU/NonNull.java $FB/NonNull.java 2>&1
cp -v $MU/Nullable.java $FB/Nullable.java 2>&1
echo
echo "=== verify ==="
ls -la $FB/ 2>&1
grep -c "public @interface NonNull" $FB/NonNull.java 2>&1
grep -c "public @interface Nullable" $FB/Nullable.java 2>&1
echo DONE
