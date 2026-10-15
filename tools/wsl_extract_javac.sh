#!/bin/bash
set +e
NINJA=/root/aosp/out/build-aosp_cpday10.ninja
echo "=== A. find build stmts for framework-location.stubs.module_lib.from-text javac ==="
grep -n "framework-location.stubs.module_lib.from-text/android_common/javac" $NINJA 2>/dev/null | head -3
echo
echo "=== B. get rule id from first javac build stmt ==="
LINE=$(grep -n "framework-location.stubs.module_lib.from-text/android_common/javac" $NINJA 2>/dev/null | head -1 | cut -d: -f1)
echo "line=$LINE"
if [ -n "$LINE" ]; then
  STMT=$(sed -n "${LINE}p" $NINJA | cut -c1-300)
  echo "stmt: $STMT"
  RULE=$(echo "$STMT" | grep -oE "rule[0-9]+" | head -1)
  echo "rule=$RULE"
  echo
  echo "=== C. rule def + command ==="
  RL=$(grep -n "^rule $RULE$" $NINJA | head -1 | cut -d: -f1)
  echo "ruleline=$RL"
  if [ -n "$RL" ]; then
    sed -n "${RL},$((RL+10))p" $NINJA | cut -c1-1500
  fi
fi
echo
echo "=== D. find rspfile name in build stmt ==="
if [ -n "$LINE" ]; then
  sed -n "${LINE}p" $NINJA | grep -oE "rspfile[^ ]*" | head -3
  RSP=$(sed -n "${LINE}p" $NINJA | grep -oE "out/soong/[^ ]*\.rsp" | head -1)
  echo "rsp=$RSP"
  if [ -n "$RSP" ] && [ -f "$RSP" ]; then
    echo "--- rspfile content (truncated) ---"
    head -c 3000 "$RSP"
    echo
  fi
fi
echo DONE
