#!/usr/bin/env python3
import sys

p3 = "/root/aosp/frameworks/base/AconfigFlags.bp"
with open(p3, "r", encoding="utf-8") as f:
    c3 = f.read()

old3 = '''name: "framework-minus-apex-aconfig-java-defaults",
    sdk_version: "core_platform",
    libs: ["fake_device_config"],
}'''
new3 = '''name: "framework-minus-apex-aconfig-java-defaults",
    sdk_version: "core_platform",
    libs: [
        "fake_device_config",
        // AI Native OS fix: this tree's mirror is missing frameworks/base/api/*.txt,
        // so the core_platform system image lacks android.* classes. Provide the
        // prebuilt system SDK stubs on the classpath so aconfig flag libs can
        // resolve android.os.Binder / android.provider.DeviceConfig at javac,
        // WITHOUT shadowing the per-module framework classpath.
        "genton.prebuilt_system_android_stubs",
    ],
}'''
if old3 not in c3:
    print("ERROR: AconfigFlags.bp block still not found")
    sys.exit(1)
c3 = c3.replace(old3, new3, 1)
with open(p3, "w", encoding="utf-8") as f:
    f.write(c3)
print("PATCHED AconfigFlags.bp OK")
# verify
lines = c3.split("\n")
for i, line in enumerate(lines):
    if "framework-minus-apex-aconfig-java-defaults" in line:
        print("\n".join(f"{j+1}: {lines[j]}" for j in range(i, min(i+14, len(lines)))))
        break
