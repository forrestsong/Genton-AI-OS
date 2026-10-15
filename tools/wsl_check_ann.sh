#!/bin/bash
set +e
# Restore missing android.annotation source files in frameworks/base
FB=/root/aosp/frameworks/base/core/java/android/annotation

echo "=== current dir contents ==="
ls -la $FB 2>&1

# Check if the files are truly missing
if [ -f "$FB/NonNull.java" ]; then
  echo "NonNull.java already exists"
else
  echo "creating NonNull.java"
fi
if [ -f "$FB/Nullable.java" ]; then
  echo "Nullable.java already exists"
else
  echo "creating Nullable.java"
fi
echo DONE
