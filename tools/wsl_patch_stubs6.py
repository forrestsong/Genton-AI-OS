#!/usr/bin/env python3
import sys

# --- Part 1: remove the java_import from core-libraries/Android.bp ---
p1 = "/root/aosp/build/soong/java/core-libraries/Android.bp"
with open(p1, "r", encoding="utf-8") as f:
    c1 = f.read()

imp_block = '''// AI Native OS fix: this tree's mirror is missing frameworks/base/api/*.txt,
// so the from-source core_platform android.* generation never produced the
// framework classes (android.os.Binder, android.provider.DeviceConfig, ...),
// and aconfig flag libs / core_platform modules cannot resolve them at javac.
// Use the prebuilt system SDK stubs jar (complete, no aconfig dependency) to
// backfill the core_platform system image.
java_import {
    name: "ainative.prebuilt_system_android_stubs",
    jars: ["//prebuilts/sdk/current/system/android.jar"],
    sdk_version: "none",
    visibility: ["//visibility:public"],
}

'''
if imp_block not in c1:
    print("WARN: import block not found in core-libraries (checking alt)")
    # try without the comment header
    alt = '''java_import {
    name: "ainative.prebuilt_system_android_stubs",
    jars: ["//prebuilts/sdk/current/system/android.jar"],
    sdk_version: "none",
    visibility: ["//visibility:public"],
}

'''
    if alt in c1:
        c1 = c1.replace(alt, "", 1)
        print("REMOVED import (alt) from core-libraries")
    else:
        print("ERROR: cannot find import block in core-libraries")
        sys.exit(1)
else:
    c1 = c1.replace(imp_block, "", 1)
    print("REMOVED import from core-libraries")

with open(p1, "w", encoding="utf-8") as f:
    f.write(c1)

# --- Part 2: add java_import to prebuilts/sdk/current/Android.bp ---
p2 = "/root/aosp/prebuilts/sdk/current/Android.bp"
with open(p2, "r", encoding="utf-8") as f:
    c2 = f.read()

anchor = '''java_import {
    name: "android_current",'''
add = '''java_import {
    name: "ainative.prebuilt_system_android_stubs",
    jars: ["system/android.jar"],
    sdk_version: "none",
    visibility: ["//visibility:public"],
}

java_import {
    name: "android_current",'''
if anchor not in c2:
    print("ERROR: anchor not found in prebuilts bp")
    sys.exit(1)
c2 = c2.replace(anchor, add, 1)
with open(p2, "w", encoding="utf-8") as f:
    f.write(c2)
print("ADDED import to prebuilts/sdk/current/Android.bp")
print("DONE")
