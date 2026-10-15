#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/core/java/com/genton/Android.bp'
with open(p) as f:
    c = f.read()

old = '''java_library {
    name: "genton.core",
    srcs: [
        "learn/**/*.kt",
        "semantics/**/*.kt",
    ],
    sdk_version: "core_platform",
    platform_apis: true,
    libs: [
        "framework",
    ],
    visibility: [
        "//frameworks/base/services:__subpackages__",
        "//packages/apps:__subpackages__",
    ],
}'''
new = '''java_library {
    name: "genton.core",
    srcs: [
        "learn/**/*.kt",
        "semantics/**/*.kt",
    ],
    sdk_version: "core_platform",
    platform_apis: true,
    libs: [
        "framework",
    ],
    static_libs: [
        "kotlin-parcelize-runtime",
    ],
    kotlin_plugins: [
        "kotlin-parcelize-compiler-plugin",
    ],
    visibility: [
        "//frameworks/base/services:__subpackages__",
        "//packages/apps:__subpackages__",
    ],
}'''
if old not in c:
    print("ERROR: bp block not found")
    sys.exit(1)
c = c.replace(old, new, 1)
with open(p, 'w') as f:
    f.write(c)
print("PATCHED genton.core bp OK (aosp tree)")
