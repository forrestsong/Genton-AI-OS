#!/usr/bin/env python3
import sys

p2 = "/root/aosp/prebuilts/sdk/current/Android.bp"
with open(p2, "r", encoding="utf-8") as f:
    c2 = f.read()

old = '''java_import {
    name: "genton.prebuilt_system_android_stubs",
    jars: ["system/android.jar"],
    sdk_version: "none",
    visibility: ["//visibility:public"],
}'''
new = '''java_import {
    name: "genton.prebuilt_system_android_stubs",
    jars: ["system/android.jar"],
    sdk_version: "none",
    host_supported: true,
    visibility: ["//visibility:public"],
}'''
if old not in c2:
    print("ERROR: java_import block not found")
    sys.exit(1)
c2 = c2.replace(old, new, 1)
with open(p2, "w", encoding="utf-8") as f:
    f.write(c2)
print("PATCHED - added host_supported: true")
