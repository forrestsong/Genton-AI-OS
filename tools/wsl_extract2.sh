#!/bin/bash
set +e
NINJA=/root/aosp/out/build-aosp_cpday10.ninja
echo "=== A. all ninja lines mentioning framework-location (count) ==="
grep -c "framework-location" $NINJA 2>/dev/null
echo
echo "=== B. from-text variant mentions ==="
grep -n "framework-location.stubs.module_lib" $NINJA 2>/dev/null | head -10
echo
echo "=== C. does the ninja have from-text javac at all? search 'from-text' + 'javac' ==="
grep -c "from-text.*javac" $NINJA 2>/dev/null
echo
echo "=== D. how are from-text stubs built? find 'stubs.system.from-text' output jars ==="
grep -n "stubs\.system\.from-text" $NINJA 2>/dev/null | head -5
echo
echo "=== E. look for the metalava->stubs pipeline: find 'from-text' build stmts (sample) ==="
grep -n "from-text" $NINJA 2>/dev/null | grep -m3 "combined\|stubs.jar\|javac" | cut -c1-300
echo DONE
