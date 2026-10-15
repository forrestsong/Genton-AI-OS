#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/services/core/java/com/android/server/ainative/AgentScreenService.java'
with open(p, encoding='utf-8') as f:
    lines = f.readlines()

# Find the line with ScreenshotGraphicBuffer, walk back to the try { line
target = None
for i, line in enumerate(lines):
    if 'ScreenshotGraphicBuffer' in line:
        target = i
        break
if target is None:
    print("ERROR: ScreenshotGraphicBuffer not found")
    sys.exit(1)

# walk back to find 'try {' before target
try_idx = None
for i in range(target, -1, -1):
    if 'try {' in lines[i]:
        try_idx = i
        break
if try_idx is None:
    print("ERROR: try not found")
    sys.exit(1)

# find the end: the 'return null;' after try block + its closing brace
end_idx = None
for i in range(target, len(lines)):
    if 'return null;' in lines[i]:
        # the method closing brace is 1-2 lines after
        end_idx = i + 1  # line after return null (should be '    }')
        break
if end_idx is None:
    print("ERROR: return null not found")
    sys.exit(1)

new_block = '''        // 捕获（占位实现）
        // TODO: 实际实现需要系统权限（本树 SurfaceControl 无 captureDisplay API，
        //       接入时使用 DisplayManager/系统截图服务完成）
        return null;
    }
'''
lines = lines[:try_idx] + [new_block] + lines[end_idx + 1:]
with open(p, 'w', encoding='utf-8') as f:
    f.writelines(lines)
print(f"OK replaced lines {try_idx+1}..{end_idx+1}")
