package com.android.server.genton.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceControl
import com.android.server.wm.WindowManagerService
import com.genton.perception.screen.ScreenModels.*
import com.genton.TrustLevel
import com.android.server.genton.acb.ACBPolicyEngine
import com.android.server.genton.audit.AuditLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * A2.2 帧捕获服务（IAgentScreenService）
 *
 * 职责：
 * 1. 单帧捕获：captureFrame()提供低延迟屏幕截图
 * 2. 帧流管理：startFrameStream()/stopFrameStream()提供持续帧流
 * 3. FLAG_SECURE检测：捕获前检测，部分安全返回掩码帧，全屏安全拒绝
 * 4. 性能优化：缩放控制、JPEG质量控制、关键帧检测
 * 5. 隐私保护：TrustLevel标记、审计日志、安全区域掩码
 *
 * 协同：
 * - 与SecureWindowDetector协同检测安全窗口
 * - 与UITreeService共享安全区域信息和时间戳对齐
 * - 与ACBPolicyEngine的capture_screen能力令牌协同鉴权
 * - 与AuditLogger记录所有捕获操作
 */
class AgentScreenService(
    private val context: Context,
    private val wms: WindowManagerService,
    private val policyEngine: ACBPolicyEngine,
    private val auditLogger: AuditLogger
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val secureDetector = SecureWindowDetector(wms)
    private val frameStreamManager = FrameStreamManager()

    // 工作线程（避免阻塞Binder线程）
    private val workerThread = HandlerThread("AgentScreenService").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    // 帧ID生成器
    private val frameIdGenerator = AtomicLong(0)

    /**
     * 单帧捕获
     *
     * @param request 捕获请求
     * @return 捕获结果（同步返回）
     */
    fun captureFrame(request: CaptureRequest): CaptureFrameResult {
        val startTime = SystemClock.elapsedRealtimeNanos()

        try {
            // 1. ACB能力令牌校验
            val capabilityCheck = policyEngine.checkCapability(
                capability = "capture_screen",
                reason = request.reason
            )
            if (!capabilityCheck.allowed) {
                auditLogger.logScreenCapture(
                    success = false,
                    reason = request.reason,
                    denyReason = "ACB capability denied: ${capabilityCheck.denyReason}"
                )
                return CaptureFrameResult.denied(ScreenServiceStatus.STATUS_DENIED)
            }

            // 2. FLAG_SECURE检测
            val secureDetection = secureDetector.detectSecureWindows(
                displayId = request.displayId,
                requireUserAuthorizedSecureContent = request.requireUserAuthorizedSecureContent
            )

            if (!secureDetection.allowCapture) {
                auditLogger.logScreenCapture(
                    success = false,
                    reason = request.reason,
                    denyReason = secureDetection.denyReason ?: "Unknown secure deny"
                )

                return when {
                    secureDetection.overlayDetected -> CaptureFrameResult.denied(ScreenServiceStatus.STATUS_OVERLAY_DETECTED)
                    secureDetection.secureState == SecureState.FULL -> CaptureFrameResult.denied(ScreenServiceStatus.STATUS_SECURE_CONTENT)
                    else -> CaptureFrameResult.denied(ScreenServiceStatus.STATUS_DENIED)
                }
            }

            // 3. 执行截图
            val display = displayManager.getDisplay(request.displayId)
                ?: return CaptureFrameResult.error("Display not found")

            val screenshot = captureDisplayScreenshot(
                display = display,
                crop = request.crop,
                maxWidth = request.maxWidth,
                maxWaitMs = request.maxWaitMs
            ) ?: return CaptureFrameResult.error("Screenshot capture failed")

            // 4. 应用安全区域掩码
            val maskedScreenshot = if (secureDetection.secureRegions.isNotEmpty()) {
                applySecureMask(screenshot, secureDetection.secureRegions)
            } else {
                screenshot
            }

            // 5. 编码为JPEG
            val jpegData = encodeToJpeg(maskedScreenshot, request.jpegQuality)
            maskedScreenshot.recycle()

            // 6. 构造结果
            val frame = ScreenFrame(
                frameId = frameIdGenerator.incrementAndGet(),
                timestampRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                displayId = request.displayId,
                width = screenshot.width,
                height = screenshot.height,
                jpeg = jpegData,
                maskedSecureRegions = secureDetection.secureRegions,
                partiallySecure = secureDetection.secureState == SecureState.PARTIAL,
                trust = TrustLevel.APP_CONTENT
            )

            // 7. 审计日志
            val elapsed = (SystemClock.elapsedRealtimeNanos() - startTime) / 1_000_000
            auditLogger.logScreenCapture(
                success = true,
                reason = request.reason,
                frameId = frame.frameId,
                secureState = secureDetection.secureState,
                elapsedMs = elapsed
            )

            return CaptureFrameResult.success(frame, ScreenServiceStatus.STATUS_OK)

        } catch (e: Exception) {
            auditLogger.logScreenCapture(
                success = false,
                reason = request.reason,
                denyReason = "Exception: ${e.message}"
            )
            return CaptureFrameResult.error("Internal error: ${e.message}")
        }
    }

    /**
     * 启动帧流
     *
     * @param config 帧流配置
     * @param listener 帧监听器
     * @return 帧流句柄
     */
    fun startFrameStream(
        config: ScreenStreamConfig,
        listener: IAgentFrameListener
    ): StartStreamResult {
        try {
            // 1. ACB能力令牌校验（持续捕获需要更高权限）
            val capabilityCheck = policyEngine.checkCapability(
                capability = "capture_screen_stream",
                reason = "Frame stream"
            )
            if (!capabilityCheck.allowed) {
                return StartStreamResult.denied(ScreenServiceStatus.STATUS_DENIED)
            }

            // 2. 创建帧流
            val handle = frameStreamManager.createStream(
                config = config,
                listener = listener,
                captureCallback = { request ->
                    // 在工作线程捕获帧
                    workerHandler.post {
                        val result = captureFrame(request)
                        if (result.status == ScreenServiceStatus.STATUS_OK && result.frame != null) {
                            listener.onFrameAvailable(result.frame)
                        } else {
                            listener.onError(result.status, result.errorMessage)
                        }
                    }
                }
            )

            auditLogger.logScreenStreamStart(
                streamId = handle.streamId,
                config = config
            )

            return StartStreamResult.success(handle)

        } catch (e: Exception) {
            return StartStreamResult.error("Failed to start stream: ${e.message}")
        }
    }

    /**
     * 停止帧流
     *
     * @param handle 帧流句柄
     */
    fun stopFrameStream(handle: ScreenStreamHandle) {
        frameStreamManager.stopStream(handle.streamId)
        auditLogger.logScreenStreamStop(streamId = handle.streamId)
    }

    /**
     * 查询安全状态（不执行捕获）
     *
     * @param displayId 显示ID
     * @return 安全状态码
     */
    fun querySecureState(displayId: Int): Int {
        val detection = secureDetector.detectSecureWindows(
            displayId = displayId,
            requireUserAuthorizedSecureContent = false
        )
        return detection.secureState
    }

    /**
     * 捕获显示截图（底层调用）
     */
    private fun captureDisplayScreenshot(
        display: Display,
        crop: Rect?,
        maxWidth: Int,
        maxWaitMs: Long
    ): Bitmap? {
        val displayInfo = android.view.DisplayInfo()
        display.getDisplayInfo(displayInfo)

        val width = displayInfo.logicalWidth
        val height = displayInfo.logicalHeight

        // 计算缩放比例
        val scale = if (width > maxWidth) {
            maxWidth.toFloat() / width
        } else {
            1.0f
        }

        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        // 使用SurfaceControl截图
        val sourceCrop = crop ?: Rect(0, 0, width, height)
        
        return SurfaceControl.screenshot(
            SurfaceControl.getInternalDisplayToken(),
            scaledWidth,
            scaledHeight
        )
    }

    /**
     * 应用安全区域掩码
     *
     * 使用黑色矩形遮盖安全区域
     */
    private fun applySecureMask(bitmap: Bitmap, secureRegions: Array<Rect>): Bitmap {
        val masked = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(masked)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.FILL
        }

        secureRegions.forEach { region ->
            canvas.drawRect(region, paint)
        }

        return masked
    }

    /**
     * 编码为JPEG
     */
    private fun encodeToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * 关闭服务
     */
    fun shutdown() {
        frameStreamManager.stopAllStreams()
        workerThread.quitSafely()
    }

    // ==================== 结果类型 ====================

    data class CaptureFrameResult(
        val status: Int,
        val frame: ScreenFrame?,
        val errorMessage: String?
    ) {
        companion object {
            fun success(frame: ScreenFrame, status: Int) = CaptureFrameResult(status, frame, null)
            fun denied(status: Int) = CaptureFrameResult(status, null, null)
            fun error(message: String) = CaptureFrameResult(ScreenServiceStatus.STATUS_INTERNAL_ERROR, null, message)
        }
    }

    data class StartStreamResult(
        val status: Int,
        val handle: ScreenStreamHandle?,
        val errorMessage: String?
    ) {
        companion object {
            fun success(handle: ScreenStreamHandle) = StartStreamResult(ScreenServiceStatus.STATUS_OK, handle, null)
            fun denied(status: Int) = StartStreamResult(status, null, null)
            fun error(message: String) = StartStreamResult(ScreenServiceStatus.STATUS_INTERNAL_ERROR, null, message)
        }
    }
}
