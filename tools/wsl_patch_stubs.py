#!/usr/bin/env python3
import sys

path = "/root/aosp/build/soong/java/core-libraries/Android.bp"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# Anchor on the full stable module block, including its unique name line.
old = '''java_system_modules {
    name: "stable-core-platform-api-stubs-system-modules",
    visibility: core_platform_visibility,
    libs: [
        "stable.core.platform.api.no.annotations.stubs",
        // This one is not on device but it's needed when javac compiles code
        // containing lambdas.
        "core-lambda-stubs-for-system-modules",
    ],
}'''

new = '''java_system_modules {
    name: "stable-core-platform-api-stubs-system-modules",
    visibility: core_platform_visibility,
    libs: [
        "stable.core.platform.api.no.annotations.stubs",
        // This one is not on device but it's needed when javac compiles code
        // containing lambdas.
        "core-lambda-stubs-for-system-modules",
        // AI Native OS fix: this tree's mirror is missing frameworks/base/api/*.txt,
        // so the from-source core_platform android.* generation never produced the
        // framework classes (android.os.Binder, android.provider.DeviceConfig, ...).
        // Merge the framework system stubs into the core_platform system image so
        // core_platform modules (e.g. aconfig flag libs) can resolve them at javac.
        "android_system_stubs_current",
    ],
}'''

if old not in content:
    print("ERROR: target block not found")
    sys.exit(1)

count = content.count(old)
if count != 1:
    print(f"ERROR: block matched {count} times, expected 1")
    sys.exit(1)

content = content.replace(old, new, 1)
with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("PATCHED OK")
lines = content.split("\n")
for i, line in enumerate(lines):
    if "stable-core-platform-api-stubs-system-modules" in line:
        start = max(0, i - 3)
        end = min(len(lines), i + 18)
        print("\n".join(f"{j+1}: {lines[j]}" for j in range(start, end)))
        break
