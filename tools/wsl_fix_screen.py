#!/usr/bin/env python3
import sys

p = '/root/aosp/frameworks/base/services/core/java/com/android/server/genton/AgentScreenService.java'
with open(p) as f:
    c = f.read()

old = '''        // 捕获
        try {
            // SurfaceControl.screenshot() 方法
            // TODO: 实际实现需要系统权限
            SurfaceControl.ScreenshotGraphicBuffer screenshotBuffer = 
                SurfaceControl.captureDisplay(
                    new SurfaceControl.DisplayCaptureArgs.Builder(display.getDisplayId())
                        .setSourceCrop(sourceCrop)
                        .setSize(width, height)
                        .setUid(android.os.Process.myUid())
                        .build()
                );
            
            if (screenshotBuffer != null && screenshotBuffer.getGraphicBuffer() != null) {
                return Bitmap.wrapHardwareBuffer(
                    screenshotBuffer.getGraphicBuffer(),
                    screenshotBuffer.getColorSpace()
                );
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to capture screen bitmap", e);
        }
        
        return null;
    }'''
new = '''        // 捕获（占位实现）
        // TODO: 实际实现需要系统权限（本树 SurfaceControl 无 captureDisplay API，
        //       接入时使用 DisplayManager/系统截图服务完成）
        return null;
    }'''
if old not in c:
    print("ERROR: capture block not found")
    sys.exit(1)
c = c.replace(old, new, 1)
with open(p, 'w') as f:
    f.write(c)
print("PATCHED AgentScreenService captureScreenBitmap OK")
