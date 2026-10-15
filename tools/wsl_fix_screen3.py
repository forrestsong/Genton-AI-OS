#!/usr/bin/env python3
import re
import sys

p = '/root/aosp/frameworks/base/services/core/java/com/android/server/genton/AgentScreenService.java'
with open(p, encoding='utf-8') as f:
    c = f.read()

# Replace the whole try{...} return null block with a placeholder
pattern = re.compile(
    r'        // 捕获\n        try \{.*?return null;\n    \}',
    re.S
)
new = '''        // 捕获（占位实现）
        // TODO: 实际实现需要系统权限（本树 SurfaceControl 无 captureDisplay API，
        //       接入时使用 DisplayManager/系统截图服务完成）
        return null;
    }'''
m = pattern.search(c)
if not m:
    print("ERROR: pattern not found")
    sys.exit(1)
c = pattern.sub(new, c, count=1)
with open(p, 'w', encoding='utf-8') as f:
    f.write(c)
print("PATCHED AgentScreenService captureScreenBitmap OK")
