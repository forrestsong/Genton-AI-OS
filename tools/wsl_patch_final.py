#!/usr/bin/env python3
import sys

# --- Part 1: revert core-libraries/Android.bp backfill ---
p1 = "/root/aosp/build/soong/java/core-libraries/Android.bp"
with open(p1, "r", encoding="utf-8") as f:
    c1 = f.read()

old1 = '''    static_libs: [
        "stable.core.platform.api.stubs",
        // AI Native OS fix: this tree's mirror is missing frameworks/base/api/*.txt,
        // so the from-source core_platform android.* generation never produced the
        // framework classes (android.os.Binder, android.provider.DeviceConfig, ...).
        // Backfill the prebuilt system SDK stubs so core_platform modules (e.g. aconfig
        // flag libs) can resolve android.* classes at javac.
        "genton.prebuilt_system_android_stubs",
    ],
    patch_module: "java.base",
    is_stubs_module: true,
}'''
new1 = '''    static_libs: [
        "stable.core.platform.api.stubs",
    ],
    patch_module: "java.base",
    is_stubs_module: true,
}'''
if old1 not in c1:
    print("ERROR: core-libraries backfill block not found")
    sys.exit(1)
c1 = c1.replace(old1, new1, 1)
with open(p1, "w", encoding="utf-8") as f:
    f.write(c1)
print("STEP1 reverted core-libraries backfill OK")

# --- Part 2: revert prebuilts/sdk/current/Android.bp java_import ---
p2 = "/root/aosp/prebuilts/sdk/current/Android.bp"
with open(p2, "r", encoding="utf-8") as f:
    c2 = f.read()

old2 = '''java_import {
    name: "genton.prebuilt_system_android_stubs",
    jars: ["system/android.jar"],
    sdk_version: "none",
    visibility: ["//visibility:public"],
}

'''
if old2 not in c2:
    print("WARN: prebuilts java_import not found (already removed?)")
else:
    c2 = c2.replace(old2, "", 1)
    with open(p2, "w", encoding="utf-8") as f:
        f.write(c2)
    print("STEP2 removed prebuilts java_import OK")

# --- Part 3: add the java_import to framework-minus-apex-aconfig-java-defaults libs in AconfigFlags.bp ---
p3 = "/root/aosp/frameworks/base/AconfigFlags.bp"
with open(p3, "r", encoding="utf-8") as f:
    c3 = f.read()

# First add the java_import def back (we need it) - add to prebuilts again but keep for classpath use
# Actually re-add the import to prebuilts (needed as a module for the classpath reference)
with open(p2, "r", encoding="utf-8") as f:
    c2 = f.read()
anchor = '''java_import {
    name: "android_current",'''
add = '''java_import {
    name: "genton.prebuilt_system_android_stubs",
    jars: ["system/android.jar"],
    sdk_version: "none",
    visibility: ["//visibility:public"],
}

java_import {
    name: "android_current",'''
if anchor not in c2:
    print("ERROR: prebuilts anchor not found for re-add")
    sys.exit(1)
c2 = c2.replace(anchor, add, 1)
with open(p2, "w", encoding="utf-8") as f:
    f.write(c2)
print("STEP2b re-added java_import to prebuilts OK")

# Now modify the aconfig java defaults libs
old3 = '''java_defaults {
    name: "framework-minus-apex-aconfig-java-defaults",
    sdk_version: "core_platform",
    libs: [
        "fake_device_config",
    ],
}'''
new3 = '''java_defaults {
    name: "framework-minus-apex-aconfig-java-defaults",
    sdk_version: "core_platform",
    libs: [
        "fake_device_config",
        // AI Native OS fix: this tree's mirror is missing frameworks/base/api/*.txt,
        // so the core_platform system image lacks android.* classes. Provide the
        // prebuilt system SDK stubs on the classpath so aconfig flag libs can
        // resolve android.os.Binder / android.provider.DeviceConfig at javac,
        // WITHOUT shadowing the per-module framework classpath (avoids stale
        // prebuilt Context etc. masking the real framework classes).
        "genton.prebuilt_system_android_stubs",
    ],
}'''
if old3 not in c3:
    print("ERROR: AconfigFlags.bp defaults block not found")
    # try to show the actual block
    import re
    m = re.search(r'name: "framework-minus-apex-aconfig-java-defaults".*?\n\}', c3, re.S)
    if m:
        print("actual block:", m.group(0)[:500])
    sys.exit(1)
c3 = c3.replace(old3, new3, 1)
with open(p3, "w", encoding="utf-8") as f:
    f.write(c3)
print("STEP3 added java_import to aconfig defaults OK")
print("DONE")
