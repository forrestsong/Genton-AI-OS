package com.android.server.ainative;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.ainative.perception.CaptureRequest;
import com.ainative.perception.IAgentFrameListener;
import com.ainative.perception.IAgentScreenService;
import com.ainative.perception.ScreenFrame;
import com.ainative.perception.ScreenStreamConfig;
import com.ainative.perception.ScreenStreamHandle;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 屏幕捕获服务
 * 
 * 职责：
 * 1. 合成帧捕获（通过 SurfaceControl）
 * 2. FLAG_SECURE 检测与标记
 * 3. Overlay 检测
 * 4. 掩码帧生成（PII 保护）
 * 5. 关键帧流 + 丢帧上报
 * 
 * 对应任务：A2.2
 * 前置依赖：A1.3（AIDL 骨架）
 * 草案来源：D1
 * 
 * @author AI Native OS Team
 * @since A2 (读-写-界闭环)
 */
public class AgentScreenService extends IAgentScreenService.Stub {
    
    /**
     * 单点鉴权（WBS 1.3）：本接口全部事务（含未来新增方法）都要求调用方持有
     * com.ainative.permission.BIND_SCREEN_SERVICE（signature|privileged）。
     * 这修正了此前"checkPermission() 恒返回 true、真正的 enforce 被注释掉"的缺陷。
     */
    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws android.os.RemoteException {
        AgentCallerAuth.enforceTransaction(mContext, AgentCallerAuth.PERM_BIND_SCREEN,
                "AgentScreenService", code);
        return super.onTransact(code, data, reply, flags);
    }
    private static final String TAG = "AgentScreenService";
    
    // 状态常量
    private static final int STATUS_OK = 0;
    private static final int STATUS_PERMISSION_DENIED = 1;
    private static final int STATUS_SECURE_WINDOW = 2;
    private static final int STATUS_INTERNAL_ERROR = -1;
    
    // 捕获模式
    private static final int CAPTURE_MODE_FULL = 0;      // 全屏捕获
    private static final int CAPTURE_MODE_MASKED = 1;    // 掩码捕获（FLAG_SECURE 区域遮挡）
    private static final int CAPTURE_MODE_METADATA = 2;  // 仅元数据（不包含像素）
    
    // 帧格式
    private static final int FORMAT_PNG = 0;
    private static final int FORMAT_JPEG = 1;
    private static final int FORMAT_RAW = 2;
    
    // 流配置
    private static final int DEFAULT_FPS = 10;           // 默认 10fps
    private static final int MAX_FPS = 30;               // 最大 30fps
    private static final int DEFAULT_QUALITY = 80;       // JPEG 质量 80
    
    // 上下文
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final WindowManager mWindowManager;
    
    // 捕获线程
    private final HandlerThread mCaptureThread;
    private final Handler mCaptureHandler;
    
    // 流管理
    private final ConcurrentHashMap<String, ScreenStream> mActiveStreams;
    
    // 帧序列号
    private long mFrameSequence = 0;
    
    // 统计信息
    private long mTotalFramesCaptured = 0;
    private long mTotalFramesDropped = 0;
    
    /**
     * 构造函数
     */
    public AgentScreenService(Context context) {
        mContext = context;
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        
        mCaptureThread = new HandlerThread("AgentScreenCapture");
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());
        
        mActiveStreams = new ConcurrentHashMap<>();
        
        Slog.i(TAG, "AgentScreenService initialized");
    }
    
    /**
     * 捕获单帧屏幕快照
     * 
     * 流程：
     * 1. 权限检查（READ_SCREEN_CONTENT）
     * 2. 检测 FLAG_SECURE 窗口
     * 3. 通过 SurfaceControl 捕获合成帧
     * 4. 检测 Overlay
     * 5. 生成掩码帧（如果需要）
     * 6. 压缩编码（PNG/JPEG）
     * 
     * @param request 捕获请求
     * @return ScreenFrame 帧对象
     */
    @Override
    public ScreenFrame captureScreen(CaptureRequest request) {
        try {
            // 1. 权限检查
            if (!checkPermission()) {
                return createErrorFrame(STATUS_PERMISSION_DENIED, "Permission denied");
            }
            
            long startTime = System.nanoTime();
            
            // 2. 检测 FLAG_SECURE 窗口
            List<SecureWindow> secureWindows = detectSecureWindows();
            boolean hasSecureWindow = !secureWindows.isEmpty();
            
            // 3. 确定捕获模式
            int captureMode = determineCaptureMode(request, hasSecureWindow);
            
            if (captureMode == CAPTURE_MODE_METADATA) {
                // 仅返回元数据，不包含像素数据
                return createMetadataOnlyFrame(secureWindows);
            }
            
            // 4. 捕获合成帧
            Bitmap bitmap = captureScreenBitmap(request);
            if (bitmap == null) {
                return createErrorFrame(STATUS_INTERNAL_ERROR, "Failed to capture screen");
            }
            
            // 5. 检测 Overlay
            List<OverlayRegion> overlays = detectOverlays();
            
            // 6. 生成掩码帧
            if (captureMode == CAPTURE_MODE_MASKED && hasSecureWindow) {
                bitmap = applySecureMask(bitmap, secureWindows);
            }
            
            // 7. 编码压缩
            byte[] frameData = encodeBitmap(bitmap, request.format, request.quality);
            
            // 8. 构建 ScreenFrame
            ScreenFrame frame = new ScreenFrame();
            frame.sequenceId = ++mFrameSequence;
            frame.timestampNanos = System.nanoTime();
            frame.width = bitmap.getWidth();
            frame.height = bitmap.getHeight();
            frame.format = request.format;
            frame.data = frameData;
            frame.dataLength = frameData.length;
            frame.hasSecureWindow = hasSecureWindow;
            frame.secureWindowCount = secureWindows.size();
            frame.secureRegions = serializeSecureRegions(secureWindows);
            frame.overlayCount = overlays.size();
            frame.captureLatencyNanos = frame.timestampNanos - startTime;
            frame.statusCode = STATUS_OK;
            
            mTotalFramesCaptured++;
            
            Slog.d(TAG, "Captured frame: seq=" + frame.sequenceId
                + ", size=" + frame.width + "x" + frame.height
                + ", secure=" + frame.hasSecureWindow
                + ", latency=" + (frame.captureLatencyNanos / 1000000) + "ms");
            
            return frame;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to capture screen", e);
            return createErrorFrame(STATUS_INTERNAL_ERROR, e.getMessage());
        }
    }
    
    /**
     * 启动屏幕流
     * 
     * 持续捕获屏幕帧并通过回调通知
     * 
     * @param config 流配置（fps、格式、质量等）
     * @param listener 帧监听器
     * @return ScreenStreamHandle 流句柄
     */
    @Override
    public ScreenStreamHandle startScreenStream(ScreenStreamConfig config, IAgentFrameListener listener) {
        if (listener == null) {
            Slog.w(TAG, "Null listener, ignoring stream request");
            return null;
        }
        
        if (!checkPermission()) {
            Slog.w(TAG, "Permission denied for screen stream");
            return null;
        }
        
        // 生成流 ID
        String streamId = UUID.randomUUID().toString();
        
        // 验证配置
        int fps = Math.min(config.fps > 0 ? config.fps : DEFAULT_FPS, MAX_FPS);
        int quality = config.quality > 0 ? config.quality : DEFAULT_QUALITY;
        
        // 创建流对象
        ScreenStream stream = new ScreenStream(streamId, config, listener, fps, quality);
        mActiveStreams.put(streamId, stream);
        
        // 启动捕获循环
        mCaptureHandler.post(() -> captureLoop(stream));
        
        // 返回句柄
        ScreenStreamHandle handle = new ScreenStreamHandle();
        handle.streamId = streamId;
        handle.fps = fps;
        handle.format = config.format;
        handle.quality = quality;
        handle.timestampNanos = System.nanoTime();
        
        Slog.i(TAG, "Started screen stream: id=" + streamId + ", fps=" + fps);
        
        return handle;
    }
    
    /**
     * 停止屏幕流
     * 
     * @param handle 流句柄
     */
    @Override
    public void stopScreenStream(ScreenStreamHandle handle) {
        if (handle == null || handle.streamId == null) {
            return;
        }
        
        ScreenStream stream = mActiveStreams.remove(handle.streamId);
        if (stream != null) {
            stream.stop();
            Slog.i(TAG, "Stopped screen stream: id=" + handle.streamId
                + ", frames=" + stream.frameCount
                + ", dropped=" + stream.droppedFrames);
        }
    }
    
    /**
     * 捕获循环（用于流模式）
     */
    private void captureLoop(ScreenStream stream) {
        if (!stream.isActive()) {
            return;
        }
        
        try {
            // 捕获帧
            CaptureRequest request = new CaptureRequest();
            request.format = stream.config.format;
            request.quality = stream.quality;
            request.maskSecureWindows = true;
            
            ScreenFrame frame = captureScreen(request);
            
            if (frame.statusCode == STATUS_OK) {
                // 通知监听器
                try {
                    stream.listener.onFrameCaptured(frame);
                    stream.frameCount++;
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify listener, stopping stream", e);
                    stream.stop();
                    return;
                }
            } else {
                stream.droppedFrames++;
                
                // 上报丢帧
                if (stream.droppedFrames % 10 == 0) {
                    Slog.w(TAG, "Stream " + stream.streamId + " dropped " + stream.droppedFrames + " frames");
                    try {
                        stream.listener.onFrameDropped(stream.droppedFrames);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to report dropped frames", e);
                    }
                }
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error in capture loop", e);
            stream.droppedFrames++;
        }
        
        // 计算下一帧时间
        long delay = 1000 / stream.fps;  // ms
        mCaptureHandler.postDelayed(() -> captureLoop(stream), delay);
    }
    
    /**
     * 检测 FLAG_SECURE 窗口
     * 
     * 遍历所有可见窗口，检查 FLAG_SECURE 标志
     */
    private List<SecureWindow> detectSecureWindows() {
        List<SecureWindow> secureWindows = new ArrayList<>();
        
        // TODO: 实际实现需要访问 WindowManagerService
        // 这里提供骨架示例
        
        /*
        WindowManagerService wms = LocalServices.getService(WindowManagerService.class);
        synchronized (wms.mGlobalLock) {
            wms.mRoot.forAllWindows(window -> {
                if (window.isVisible() && window.isFlagSecure()) {
                    SecureWindow sw = new SecureWindow();
                    sw.windowId = window.mWindowId;
                    sw.packageName = window.getOwningPackage();
                    sw.bounds = window.getFrame();
                    sw.reason = "FLAG_SECURE";
                    secureWindows.add(sw);
                }
            }, false);
        }
        */
        
        return secureWindows;
    }
    
    /**
     * 检测 Overlay
     * 
     * 检测悬浮窗、系统 UI overlay
     */
    private List<OverlayRegion> detectOverlays() {
        List<OverlayRegion> overlays = new ArrayList<>();
        
        // TODO: 实现 overlay 检测
        // 1. 检测 TYPE_SYSTEM_OVERLAY 窗口
        // 2. 检测 TYPE_APPLICATION_OVERLAY 窗口
        // 3. 检测 SystemUI 元素（状态栏、导航栏）
        
        return overlays;
    }
    
    /**
     * 确定捕获模式
     */
    private int determineCaptureMode(CaptureRequest request, boolean hasSecureWindow) {
        if (!hasSecureWindow) {
            return CAPTURE_MODE_FULL;
        }
        
        // 有 FLAG_SECURE 窗口
        if (request.maskSecureWindows) {
            return CAPTURE_MODE_MASKED;  // 掩码模式
        } else {
            return CAPTURE_MODE_METADATA;  // 仅元数据
        }
    }
    
    /**
     * 捕获屏幕 Bitmap
     * 
     * 通过 SurfaceControl 捕获合成帧
     */
    private Bitmap captureScreenBitmap(CaptureRequest request) {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return null;
        }
        
        // 获取屏幕尺寸
        Rect sourceCrop = new Rect();
        display.getRectSize(sourceCrop);
        
        int width = sourceCrop.width();
        int height = sourceCrop.height();
        
        // 缩放
        if (request.scale > 0 && request.scale < 1.0f) {
            width = (int) (width * request.scale);
            height = (int) (height * request.scale);
        }
        
        // 捕获
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
    }
    
    /**
     * 应用安全掩码
     * 
     * 在 FLAG_SECURE 区域上绘制遮挡
     */
    private Bitmap applySecureMask(Bitmap original, List<SecureWindow> secureWindows) {
        Bitmap masked = original.copy(original.getConfig(), true);
        Canvas canvas = new Canvas(masked);
        
        Paint paint = new Paint();
        paint.setColor(0xFF000000);  // 黑色
        paint.setAlpha(255);         // 完全不透明
        
        for (SecureWindow window : secureWindows) {
            // 在安全区域绘制黑色矩形
            Rect bounds = window.bounds;
            canvas.drawRect(bounds, paint);
            
            // 可选：添加提示文字
            Paint textPaint = new Paint();
            textPaint.setColor(0xFFFFFFFF);  // 白色
            textPaint.setTextSize(48);
            textPaint.setTextAlign(Paint.Align.CENTER);
            
            String text = "[SECURE: " + window.reason + "]";
            canvas.drawText(text, bounds.centerX(), bounds.centerY(), textPaint);
        }
        
        return masked;
    }
    
    /**
     * 编码 Bitmap
     */
    private byte[] encodeBitmap(Bitmap bitmap, int format, int quality) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        Bitmap.CompressFormat compressFormat;
        if (format == FORMAT_PNG) {
            compressFormat = Bitmap.CompressFormat.PNG;
            quality = 100;  // PNG 无损
        } else {
            compressFormat = Bitmap.CompressFormat.JPEG;
        }
        
        bitmap.compress(compressFormat, quality, baos);
        return baos.toByteArray();
    }
    
    /**
     * 序列化安全区域
     */
    private String serializeSecureRegions(List<SecureWindow> secureWindows) {
        StringBuilder sb = new StringBuilder();
        for (SecureWindow window : secureWindows) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(window.packageName)
              .append(":")
              .append(window.bounds.toShortString())
              .append(":")
              .append(window.reason);
        }
        return sb.toString();
    }
    
    /**
     * 权限检查
     */
    private boolean checkPermission() {
        // WBS 1.3：真实校验（此前为注释掉的占位实现 + return true）
        // 返回 boolean 供本类方法返回错误帧使用；onTransact 另有一道强制边界。
        if (AgentCallerAuth.isSystemCaller()) {
            return true;
        }
        return AgentCallerAuth.holdsPermission(mContext, AgentCallerAuth.PERM_READ_SCREEN);
    }
    
    /**
     * 创建错误帧
     */
    private ScreenFrame createErrorFrame(int statusCode, String message) {
        ScreenFrame frame = new ScreenFrame();
        frame.sequenceId = -1;
        frame.timestampNanos = System.nanoTime();
        frame.statusCode = statusCode;
        frame.errorMessage = message;
        return frame;
    }
    
    /**
     * 创建仅元数据帧
     */
    private ScreenFrame createMetadataOnlyFrame(List<SecureWindow> secureWindows) {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        
        ScreenFrame frame = new ScreenFrame();
        frame.sequenceId = ++mFrameSequence;
        frame.timestampNanos = System.nanoTime();
        frame.width = display.getWidth();
        frame.height = display.getHeight();
        frame.data = new byte[0];  // 无像素数据
        frame.dataLength = 0;
        frame.hasSecureWindow = true;
        frame.secureWindowCount = secureWindows.size();
        frame.secureRegions = serializeSecureRegions(secureWindows);
        frame.statusCode = STATUS_OK;
        frame.isMetadataOnly = true;
        
        return frame;
    }
    
    /**
     * 获取统计信息
     */
    public String getStatistics() {
        return "Captured: " + mTotalFramesCaptured
            + ", Dropped: " + mTotalFramesDropped
            + ", Active streams: " + mActiveStreams.size();
    }
    
    // ===== 内部数据结构 =====
    
    /**
     * 安全窗口
     */
    private static class SecureWindow {
        int windowId;
        String packageName;
        Rect bounds;
        String reason;  // FLAG_SECURE / DRM / Secure input
    }
    
    /**
     * Overlay 区域
     */
    private static class OverlayRegion {
        int windowId;
        Rect bounds;
        String type;  // system / application
    }
    
    /**
     * 屏幕流
     */
    private static class ScreenStream {
        String streamId;
        ScreenStreamConfig config;
        IAgentFrameListener listener;
        int fps;
        int quality;
        boolean active;
        long frameCount;
        long droppedFrames;
        
        ScreenStream(String streamId, ScreenStreamConfig config, IAgentFrameListener listener, int fps, int quality) {
            this.streamId = streamId;
            this.config = config;
            this.listener = listener;
            this.fps = fps;
            this.quality = quality;
            this.active = true;
            this.frameCount = 0;
            this.droppedFrames = 0;
        }
        
        boolean isActive() {
            return active;
        }
        
        void stop() {
            active = false;
        }
    }
}
