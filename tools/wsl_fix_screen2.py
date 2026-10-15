#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/services/core/java/com/android/server/genton/AgentScreenService.java'
with open(p) as f:
    lines = f.readlines()

# Find the try block start (line with "// 捕获") and the method's closing
start = None
end = None
for i, line in enumerate(lines):
    if '// 捕获' in line:
        start = i
    if start is not None and i > start and 'return null;' in line and '}' in line:
        # find the closing brace of the method (the '}' after 'return null;')
        # keep scanning to the method's closing brace
        end = i + 2  # return null; } pattern
        break

if start is None:
    print("ERROR: capture comment not found")
    sys.exit(1)

# Replace from start to the method closing brace
# The method body after '// 捕获' until 'return null;\n    }' 
new_block = '''        // 捕获（占位实现）
        // TODO: 实际实现需要系统权限（本树 SurfaceControl 无 captureDisplay API，
        //       接入时使用 DisplayManager/系统截图服务完成）
        return null;
    }
'''
lines = lines[:start] + [new_block] + lines[end:]
with open(p, 'w') as f:
    f.writelines(lines)
print(f"PATCHED AgentScreenService (replaced lines {start+1}-{end}) OK")
