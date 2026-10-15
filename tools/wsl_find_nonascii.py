#!/usr/bin/env python3
import os

BASE = '/root/aosp/frameworks/base/core/java/com/ainative'
for root, dirs, files in os.walk(BASE):
    for f in files:
        if not f.endswith('.aidl'):
            continue
        p = os.path.join(root, f)
        with open(p, 'rb') as fh:
            data = fh.read()
        # check for non-ASCII bytes (UTF-8 Chinese chars)
        try:
            data.decode('ascii')
            has_nonascii = False
        except UnicodeDecodeError:
            has_nonascii = True
        if has_nonascii:
            rel = os.path.relpath(p, BASE)
            print(f"NON-ASCII: {rel}")
print("DONE")
