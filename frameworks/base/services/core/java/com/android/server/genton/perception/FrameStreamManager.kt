package com.android.server.genton.perception

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.genton.perception.screen.ScreenModels.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A2.2 帧流管理器
 *
 * 职责：
 * 1. 管理多个并发帧流的生命周期
 * 2. 按配置的FPS定时触发帧捕获
 * 3. 关键帧检测（仅在画面变化时推送）
 * 4. 回调调度和错误处理
 * 5. 资源清理（防止泄漏）
 *
 * 设计要点：
 * - 每个帧流独立Handler线程
 * - 关键帧模式：比较帧差异，仅推送变化帧
 * - 自动超时清理（5分钟无活动）
 * - 线程安全的并发管理
 */
class FrameStreamManager {
    private val streamIdGenerator = AtomicLong(0)
    private val activeStreams = ConcurrentHashMap<String, FrameStream>()

    // 清理线程
    private val cleanupThread = HandlerThread("FrameStreamCleanup").apply { start() }
    private val cleanupHandler = Handler(cleanupThread.looper)

    /**
     * 帧流实例
     */
    private inner class FrameStream(
        val streamId: String,
        val config: ScreenStreamConfig,
        val listener: IAgentFrameListener,
        val captureCallback: (CaptureRequest) -> Unit
    ) {
        private val streamThread = HandlerThread("FrameStream-$streamId").apply { start() }
        private val streamHandler = Handler(streamThread.looper)

        private var running = false
        private var lastFrameTimestamp = 0L
        private var lastFrameSignature: ByteArray? = null
        private var frameCount = 0L

        // 超时参数
        private val timeoutMs = 5 * 60 * 1000L  // 5分钟
        private var lastActivityTime = SystemClock.elapsedRealtime()

        /**
         * 启动帧流
         */
        fun start() {
            running = true
            lastActivityTime = SystemClock.elapsedRealtime()
            scheduleNextCapture(0)
            scheduleTimeoutCheck()
        }

        /**
         * 停止帧流
         */
        fun stop() {
            running = false
            streamHandler.removeCallbacksAndMessages(null)
            streamThread.quitSafely()
        }

        /**
         * 调度下一次捕获
         */
        private fun scheduleNextCapture(delayMs: Long) {
            if (!running) return

            streamHandler.postDelayed({
                if (running) {
                    executeCaptureAndScheduleNext()
                }
            }, delayMs)
        }

        /**
         * 执行捕获并调度下一次
         */
        private fun executeCaptureAndScheduleNext() {
            val captureStartTime = SystemClock.elapsedRealtimeNanos()

            // 构造捕获请求
            val request = CaptureRequest(
                displayId = config.displayId,
                crop = null,
                maxWidth = config.maxWidth,
                jpegQuality = config.jpegQuality,
                maxWaitMs = 50,
                reason = "Frame stream $streamId (frame $frameCount)",
                requireUserAuthorizedSecureContent = false
            )

            // 触发捕获（异步回调）
            captureCallback(request)

            frameCount++
            lastActivityTime = SystemClock.elapsedRealtime()

            // 计算下一次捕获延迟
            val captureElapsedMs = (SystemClock.elapsedRealtimeNanos() - captureStartTime) / 1_000_000
            val intervalMs = 1000L / config.fpsHint
            val nextDelayMs = maxOf(0, intervalMs - captureElapsedMs)

            scheduleNextCapture(nextDelayMs)
        }

        /**
         * 调度超时检查
         */
        private fun scheduleTimeoutCheck() {
            cleanupHandler.postDelayed({
                checkTimeout()
            }, timeoutMs)
        }

        /**
         * 检查超时
         */
        private fun checkTimeout() {
            val elapsed = SystemClock.elapsedRealtime() - lastActivityTime
            if (elapsed >= timeoutMs) {
                // 超时，自动停止
                try {
                    listener.onError(
                        ScreenServiceStatus.STATUS_INTERNAL_ERROR,
                        "Stream timeout after ${timeoutMs / 1000}s inactivity"
                    )
                } catch (e: Exception) {
                    // Listener已死亡，静默处理
                }
                stopStream(streamId)
            } else if (running) {
                // 未超时，继续调度
                scheduleTimeoutCheck()
            }
        }

        /**
         * 关键帧检测
         *
         * 简化版：比较JPEG前1KB的字节差异
         */
        fun shouldPushFrame(frame: ScreenFrame): Boolean {
            if (!config.keyframesOnly) {
                // 所有帧模式
                return true
            }

            val signature = computeFrameSignature(frame.jpeg)

            if (lastFrameSignature == null) {
                // 第一帧，必须推送
                lastFrameSignature = signature
                return true
            }

            val diff = computeDifference(lastFrameSignature!!, signature)
            val threshold = 0.05  // 5%差异阈值

            return if (diff > threshold) {
                // 关键帧
                lastFrameSignature = signature
                true
            } else {
                // 跳过
                false
            }
        }

        /**
         * 计算帧签名（前1KB）
         */
        private fun computeFrameSignature(jpeg: ByteArray): ByteArray {
            val signatureSize = minOf(1024, jpeg.size)
            return jpeg.copyOfRange(0, signatureSize)
        }

        /**
         * 计算两个签名的差异度
         */
        private fun computeDifference(sig1: ByteArray, sig2: ByteArray): Float {
            val size = minOf(sig1.size, sig2.size)
            var diffCount = 0

            for (i in 0 until size) {
                if (sig1[i] != sig2[i]) {
                    diffCount++
                }
            }

            return diffCount.toFloat() / size
        }
    }

    /**
     * 创建帧流
     */
    fun createStream(
        config: ScreenStreamConfig,
        listener: IAgentFrameListener,
        captureCallback: (CaptureRequest) -> Unit
    ): ScreenStreamHandle {
        val streamId = "stream_${streamIdGenerator.incrementAndGet()}_${SystemClock.elapsedRealtime()}"

        val stream = FrameStream(
            streamId = streamId,
            config = config,
            listener = listener,
            captureCallback = captureCallback
        )

        activeStreams[streamId] = stream
        stream.start()

        return ScreenStreamHandle(streamId)
    }

    /**
     * 停止帧流
     */
    fun stopStream(streamId: String) {
        val stream = activeStreams.remove(streamId)
        stream?.stop()
    }

    /**
     * 停止所有帧流
     */
    fun stopAllStreams() {
        val streamIds = activeStreams.keys.toList()
        streamIds.forEach { stopStream(it) }
    }

    /**
     * 获取活跃帧流数量
     */
    fun getActiveStreamCount(): Int {
        return activeStreams.size
    }

    /**
     * 检查关键帧（供外部调用）
     */
    fun shouldPushFrame(streamId: String, frame: ScreenFrame): Boolean {
        val stream = activeStreams[streamId] ?: return false
        return stream.shouldPushFrame(frame)
    }

    /**
     * 关闭管理器
     */
    fun shutdown() {
        stopAllStreams()
        cleanupHandler.removeCallbacksAndMessages(null)
        cleanupThread.quitSafely()
    }
}
