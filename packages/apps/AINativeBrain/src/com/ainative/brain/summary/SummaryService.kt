package com.ainative.brain.summary

import android.content.Context
import android.util.Log
import com.ainative.brain.memory.security.PiiMaskIntegration
import com.ainative.context.ContextFrame

/**
 * C4.4 摘要服务 - TOP_UI_SUMMARY 简报生成
 * 
 * 为 IAgentContextService 生成上下文简报，压缩长文本为 64-128 tokens 的摘要。
 * 
 * 核心功能：
 * 1. TOP_UI_SUMMARY: 上下文简报（应用/窗口/内容摘要）
 * 2. 长文本压缩: 5:1 到 10:1 压缩比
 * 3. PII 源头掩码: 摘要前强制 PII 掩码
 * 4. 批量摘要: 通知聚合、对话历史压缩
 * 
 * 模型: T5-Small (60M 参数, INT8 量化后 ~45MB)
 * 性能目标: P95 延迟 ≤200ms, 内存占用 ≤70MB
 */
class SummaryService(private val context: Context) {

    companion object {
        private const val TAG = "SummaryService"
        
        // 摘要长度约束
        private const val DEFAULT_MAX_LENGTH = 128  // tokens
        private const val DEFAULT_MIN_LENGTH = 64
        private const val MAX_INPUT_LENGTH = 2048   // tokens (~8192 chars)
        
        // 单例
        @Volatile
        private var instance: SummaryService? = null
        
        fun getInstance(context: Context): SummaryService {
            return instance ?: synchronized(this) {
                instance ?: SummaryService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val summaryEngine: SummaryInferenceEngine by lazy {
        SummaryInferenceEngine(context, modelPath = "t5_small_int8.tflite")
    }

    private val piiMaskIntegration: PiiMaskIntegration by lazy {
        PiiMaskIntegration(context)
    }

    /**
     * 生成通用摘要
     */
    fun summarize(
        text: String,
        maxLength: Int = DEFAULT_MAX_LENGTH,
        minLength: Int = DEFAULT_MIN_LENGTH,
        maskPii: Boolean = true
    ): SummaryResult {
        val startTime = System.currentTimeMillis()

        try {
            // 1. 长度检查
            if (text.isBlank()) {
                return SummaryResult(
                    summary = "",
                    originalLength = 0,
                    summaryLength = 0,
                    compressionRatio = 0.0f,
                    latencyMs = 0,
                    piiMasked = false
                )
            }

            if (text.length > MAX_INPUT_LENGTH * 4) {  // ~8192 chars
                Log.w(TAG, "Input text too long: ${text.length} chars, truncating")
                return summarize(
                    text = text.take(MAX_INPUT_LENGTH * 4),
                    maxLength = maxLength,
                    minLength = minLength,
                    maskPii = maskPii
                )
            }

            // 2. PII 掩码（可选）
            val processedText: String
            val piiMasked: Boolean
            
            if (maskPii) {
                val maskResult = piiMaskIntegration.maskText(text)
                if (maskResult.status == PiiMaskIntegration.MaskStatus.REJECT) {
                    Log.w(TAG, "Text rejected due to sensitive PII")
                    return SummaryResult(
                        summary = "[内容包含敏感信息，无法生成摘要]",
                        originalLength = text.length,
                        summaryLength = 0,
                        compressionRatio = 0.0f,
                        latencyMs = System.currentTimeMillis() - startTime,
                        piiMasked = true
                    )
                }
                processedText = maskResult.content
                piiMasked = maskResult.piiMasked
            } else {
                processedText = text
                piiMasked = false
            }

            // 3. 生成摘要
            val summary = summaryEngine.summarize(
                text = processedText,
                maxLength = maxLength,
                minLength = minLength
            )

            // 4. 计算压缩比
            val compressionRatio = if (summary.isNotEmpty()) {
                text.length.toFloat() / summary.length
            } else {
                0.0f
            }

            val latency = System.currentTimeMillis() - startTime

            Log.d(TAG, "Summary generated: original=${text.length} chars, " +
                "summary=${summary.length} chars, ratio=$compressionRatio, latency=${latency}ms")

            return SummaryResult(
                summary = summary,
                originalLength = text.length,
                summaryLength = summary.length,
                compressionRatio = compressionRatio,
                latencyMs = latency,
                piiMasked = piiMasked
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate summary", e)
            return SummaryResult(
                summary = "",
                originalLength = text.length,
                summaryLength = 0,
                compressionRatio = 0.0f,
                latencyMs = System.currentTimeMillis() - startTime,
                piiMasked = false,
                error = e.message
            )
        }
    }

    /**
     * 为 TOP_UI_SUMMARY 生成上下文简报
     * 
     * 集成到 IAgentContextService.getContextFrame()
     */
    fun generateUiSummary(context: ContextFrame): String {
        val fullText = buildContextText(context)

        val result = summarize(
            text = fullText,
            maxLength = 128,
            minLength = 64,
            maskPii = true  // 强制掩码
        )

        return result.summary
    }

    /**
     * 批量摘要（通知聚合、对话历史）
     */
    fun summarizeBatch(texts: List<String>): List<SummaryResult> {
        return texts.map { text ->
            summarize(text)
        }
    }

    /**
     * 对话历史压缩
     */
    fun compressConversation(messages: List<Message>): String {
        val conversationText = buildString {
            messages.forEach { msg ->
                append("${msg.role}: ${msg.content}\n")
            }
        }

        val result = summarize(
            text = conversationText,
            maxLength = 256,  // 对话摘要可以稍长
            minLength = 128,
            maskPii = true
        )

        return result.summary
    }

    /**
     * 通知聚合摘要
     */
    fun summarizeNotifications(notifications: List<NotificationData>): String {
        val notifText = buildString {
            notifications.forEachIndexed { index, notif ->
                append("${index + 1}. ${notif.title}: ${notif.content}\n")
            }
        }

        val result = summarize(
            text = notifText,
            maxLength = 128,
            minLength = 64,
            maskPii = true
        )

        return result.summary
    }

    /**
     * 构建上下文文本
     */
    private fun buildContextText(context: ContextFrame): String {
        return buildString {
            // 应用信息
            if (context.activeApp.isNotEmpty()) {
                append("应用: ${context.activeApp}\n")
            }

            // 窗口标题
            if (context.windowTitle.isNotEmpty()) {
                append("窗口: ${context.windowTitle}\n")
            }

            // 文本内容
            if (context.textContent.isNotEmpty()) {
                append("内容: ${context.textContent}\n")
            }

            // URL（如果是浏览器）
            if (context.url?.isNotEmpty() == true) {
                append("网址: ${context.url}\n")
            }

            // 通知（如果有）
            if (context.recentNotifications.isNotEmpty()) {
                append("最近通知: ${context.recentNotifications.joinToString("; ")}\n")
            }
        }.take(MAX_INPUT_LENGTH * 4)  // 限制长度
    }

    /**
     * 获取性能统计
     */
    fun getPerformanceStats(): PerformanceStats {
        return summaryEngine.getPerformanceStats()
    }

    /**
     * 清理资源
     */
    fun shutdown() {
        summaryEngine.close()
    }

    data class SummaryResult(
        val summary: String,
        val originalLength: Int,
        val summaryLength: Int,
        val compressionRatio: Float,
        val latencyMs: Long,
        val piiMasked: Boolean,
        val error: String? = null
    )

    data class Message(
        val role: String,       // "user" or "assistant"
        val content: String
    )

    data class NotificationData(
        val title: String,
        val content: String,
        val timestamp: Long
    )

    data class PerformanceStats(
        val avgLatency: Long,
        val p95Latency: Long,
        val totalCalls: Int,
        val errorCount: Int
    )
}
