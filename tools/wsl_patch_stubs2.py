#!/usr/bin/env python3
import sys

path = "/root/aosp/build/soong/java/core-libraries/Android.bp"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Step 1: revert the cycle-causing block (remove android_system_stubs_current from libs)
broken = '''        "core-lambda-stubs-for-system-modules",
        // AI Native OS fix: this tree's mirror is missing frameworks/base/api/*.txt,
        // so the from-source core_platform android.* generation never produced the
        // framework classes (android.os.Binder, android.provider.DeviceConfig, ...).
        // Merge the framework system stubs into the core_platform system image so
        // core_platform modules (e.g. aconfig flag libs) can resolve them at javac.
        "android_system_stubs_current",
    ],
}'''
reverted = '''        "core-lambda-stubs-for-system-modules",
    ],
}'''
if broken not in content:
    print("ERROR: broken block not found (already reverted?)")
else:
    content = content.replace(broken, reverted, 1)
    print("STEP1 reverted cycle-causing block OK")

# Step 2: add a java_import for the prebuilt system android.jar
# Insert it just before the "java_system_modules { name: stable-core-platform-api..." block.
anchor = 'java_system_modules {\n    name: "stable-core-platform-api-stubs-system-modules",'
import_block = '''// AI Native OS fix: this tree's mirror is missing frameworks/base/api/*.txt,
// so the from-source core_platform android.* generation never produced the
// framework classes (android.os.Binder, android.provider.DeviceConfig, ...),
// and aconfig flag libs / core_platform modules cannot resolve them at javac.
// Use the prebuilt system SDK stubs jar (complete, no aconfig dependency) to
// backfill the core_platform system image.
java_import {
    name: "ainative.prebuilt_system_android_stubs",
    jars: ["prebuilts/sdk/current/system/android.jar"],
    sdk_version: "none",
    system_modules: "none",
    visibility: ["//visibility:public"],
}

java_system_modules {
    name: "stable-core-platform-api-stubs-system-modules",'''
if anchor not in content:
    print("ERROR: anchor not found")
    sys.exit(1)
content = content.replace(anchor, import_block, 1)
print("STEP2 java_import added OK")

# Step 3: add the import to the stable system modules libs
old_libs = '''java_system_modules {
    name: "stable-core-platform-api-stubs-system-modules",
    visibility: core_platform_visibility,
    libs: [
        "stable.core.platform.api.no.annotations.stubs",
        // This one is not on device but it's needed when javac compiles code
        // containing lambdas.
        "core-lambda-stubs-for-system-modules",
    ],
}'''
new_libs = '''java_system_modules {
    name: "stable-core-platform-api-stubs-system-modules",
    visibility: core_platform_visibility,
    libs: [
        "stable.core.platform.api.no.annotations.stubs",
        // This one is not on device but it's needed when javac compiles code
        // containing lambdas.
        "core-lambda-stubs-for-system-modules",
        // AI Native OS fix: backfill framework android.* into the core_platform image.
        "ainative.prebuilt_system_android_stubs",
    ],
}'''
if old_libs not in content:
    print("ERROR: system modules libs block not found")
    sys.exit(1)
content = content.replace(old_libs, new_libs, 1)
print("STEP3 libs updated OK")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

# Show final stable block
lines = content.split("\n")
for i, line in enumerate(lines):
    if "stable-core-platform-api-stubs-system-modules" in line and line.strip().startswith('name'):
        start = max(0, i - 14)
        end = min(len(lines), i + 18)
        print("--- final block ---")
        print("\n".join(f"{j+1}: {lines[j]}" for j in range(start, end)))
        break
print("DONE")
