#!/usr/bin/env python3
import sys

path = "/root/aosp/build/soong/java/core-libraries/Android.bp"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

old = '''java_import {
    name: "ainative.prebuilt_system_android_stubs",
    jars: ["prebuilts/sdk/current/system/android.jar"],
    sdk_version: "none",
    visibility: ["//visibility:public"],
}'''
new = '''java_import {
    name: "ainative.prebuilt_system_android_stubs",
    jars: ["//prebuilts/sdk/current/system/android.jar"],
    sdk_version: "none",
    visibility: ["//visibility:public"],
}'''
if old not in content:
    print("ERROR: java_import block not found")
    sys.exit(1)
content = content.replace(old, new, 1)
with open(path, "w", encoding="utf-8") as f:
    f.write(content)
print("PATCHED OK - root-relative path")
