#!/usr/bin/env python3
import os
import shutil
import sys

SRC_ROOT = '/mnt/d/AI Native OS/aidl/com/ainative'
DST_ROOT = '/root/aosp/frameworks/base/core/java/com/ainative'

copied = []
skipped = []
errors = []

for pkg in os.listdir(SRC_ROOT):
    src_pkg = os.path.join(SRC_ROOT, pkg)
    if not os.path.isdir(src_pkg):
        continue
    dst_pkg = os.path.join(DST_ROOT, pkg)
    os.makedirs(dst_pkg, exist_ok=True)
    for f in sorted(os.listdir(src_pkg)):
        if not f.endswith('.aidl'):
            continue
        src = os.path.join(src_pkg, f)
        dst = os.path.join(dst_pkg, f)
        if os.path.exists(dst):
            skipped.append(f"{pkg}/{f}")
            continue
        try:
            shutil.copy2(src, dst)
            copied.append(f"{pkg}/{f}")
        except Exception as e:
            errors.append(f"{pkg}/{f}: {e}")

print("=== COPIED ===")
for c in copied:
    print(f"  + {c}")
print(f"\n=== SKIPPED (already exist) ===")
for s in skipped:
    print(f"  = {s}")
print(f"\n=== ERRORS ===")
for e in errors:
    print(f"  ! {e}")
print(f"\nTotal: copied={len(copied)} skipped={len(skipped)} errors={len(errors)}")
