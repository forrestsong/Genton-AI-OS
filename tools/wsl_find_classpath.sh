#!/bin/bash
set +e
NINJA=/root/aosp/out/build-aosp_cpday10.ninja
echo "=== A. find the ninja build stmt for framework-location.stubs.module_lib.from-text javac output ==="
grep -n "framework-location.stubs.module_lib.from-text/android_common/javac/shard" $NINJA 2>/dev/null | head -3
echo
echo "=== B. extract the rule command (javac line) for that module - get rule id ==="
LINE=$(grep -n "framework-location.stubs.module_lib.from-text/android_common/javac/shard" $NINJA 2>/dev/null | head -1 | cut -d: -f1)
echo "line: $LINE"
if [ -n "$LINE" ]; then
  # the build stmt: build <out>: <rule> <inputs...>
  STMT=$(sed -n "${LINE}p" $NINJA)
  RULE=$(echo "$STMT" | grep -oE "rule[0-9]+" | head -1)
  echo "rule: $RULE"
  echo
  echo "=== C. find rule definition and its command ==="
  RULELINE=$(grep -n "^rule $RULE" $NINJA 2>/dev/null | head -1 | cut -d: -f1)
  echo "rule line: $RULELINE"
  if [ -n "$RULELINE" ]; then
    # command is a few lines after 'rule X' -> 'command = ...'
    sed -n "${RULELINE},$((RULELINE+8))p" $NINJA | grep -m1 "^  command = " | cut -c1-4000
  fi
fi
echo
echo "=== D. fallback: search javac commands containing framework-location stubs ==="
grep -n "framework-location" $NINJA 2>/dev/null | grep -m1 "javac" | cut -c1-4000
echo
echo "=== E. grep any javac command with android.jar in classpath (sample) ==="
grep -m3 "android.jar" $NINJA 2>/dev/null | cut -c1-800
echo DONE
