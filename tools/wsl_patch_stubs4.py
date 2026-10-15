#!/usr/bin/env python3
import sys

path = "/root/aosp/build/soong/java/core-libraries/Android.bp"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Step 1: revert system_modules libs change (remove the prebuilt import from there)
old_sm = '''java_system_modules {
    name: "stable-core-platform-api-stubs-system-modules",
    visibility: core_platform_visibility,
    libs: [
        "stable.core.platform.api.no.annotations.stubs",
        // This one is not on device but it's needed when javac compiles code
        // containing lambdas.
        "core-lambda-stubs-for-system-modules",
        // AI Native OS fix: backfill framework android.* into the core_platform image.
        "genton.prebuilt_system_android_stubs",
    ],
}'''
new_sm = '''java_system_modules {
    name: "stable-core-platform-api-stubs-system-modules",
    visibility: core_platform_visibility,
    libs: [
        "stable.core.platform.api.no.annotations.stubs",
        // This one is not on device but it's needed when javac compiles code
        // containing lambdas.
        "core-lambda-stubs-for-system-modules",
    ],
}'''
if old_sm not in content:
    print("ERROR: system_modules block not found (different state)")
    sys.exit(1)
content = content.replace(old_sm, new_sm, 1)
print("STEP1 reverted system_modules libs OK")

# Step 2: add the prebuilt import to stable.core.platform.api.no.annotations.stubs static_libs
old_st = '''java_library {
    name: "stable.core.platform.api.no.annotations.stubs",
    visibility: core_platform_visibility,
    defaults: [
        "system-modules-no-annotations",
    ],
    hostdex: true,
    compile_dex: true,

    static_libs: [
        "stable.core.platform.api.stubs",
    ],
    patch_module: "java.base",
    is_stubs_module: true,
}'''
new_st = '''java_library {
    name: "stable.core.platform.api.no.annotations.stubs",
    visibility: core_platform_visibility,
    defaults: [
        "system-modules-no-annotations",
    ],
    hostdex: true,
    compile_dex: true,

    static_libs: [
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
if old_st not in content:
    print("ERROR: no.annotations.stubs block not found")
    sys.exit(1)
content = content.replace(old_st, new_st, 1)
print("STEP2 static_libs updated OK")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
print("PATCHED OK")
lines = content.split("\n")
for i, line in enumerate(lines):
    if "stable.core.platform.api.no.annotations.stubs" in line and line.strip().startswith('name'):
        start = max(0, i - 3)
        end = min(len(lines), i + 20)
        print("\n".join(f"{j+1}: {lines[j]}" for j in range(start, end)))
        break
