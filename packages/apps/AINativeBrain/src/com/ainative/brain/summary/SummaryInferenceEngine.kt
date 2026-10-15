package com.ainative.brain.summary

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * C4.4 摘要推理引擎 - T5-Small TFLite 推理
 * 
 * 基于 T5-Small (60M 参数) 的文本摘要模型：
 * - 架构: Encoder-Decoder Transformer
 * - 量化: INT8 动态量化
 * - 模型大小: ~45MB
 * - 输入: 512-2048 tokens
 * - 输出: 64-128 tokens
 * 
 * 性能目标:
 * - P50 延迟: ≤150ms (512 tokens)
 * - P95 延迟: ≤200ms
 * - 内存占用: ≤70MB
 */
class SummaryInferenceEngine(
    private val context: Context,
    private val modelPath: String
) {
    companion object {
        private const val TAG = "SummaryInferenceEngine"
        
        // 模型配置
        private const val MAX_INPUT_LENGTH = 512
        private const val MAX_OUTPUT_LENGTH = 128
        private const val START_TOKEN_ID = 0
        private const val END_TOKEN_ID = 1
        private const val PAD_TOKEN_ID = 0
        
        // 性能统计窗口
        private const val STATS_WINDOW_SIZE = 100
    }

    private var interpreter: Interpreter? = null
    private var tokenizer: T5Tokenizer? = null
    
    private val lock = ReentrantLock()
    private val latencyHistory = mutableListOf<Long>()
    private var totalCalls = 0
    private var errorCount = 0

    init {
        initialize()
    }

    /**
     * 初始化模型和 Tokenizer
     */
    private fun initialize() {
        try {
            // 1. 加载 TFLite 模型
            val modelBuffer = loadModelFile(modelPath)
            val options = Interpreter.Options().apply {
                setNumThreads(4)  // 多线程加速
                setUseNNAPI(false)  // T5 模型在 NNAPI 上可能不稳定，先关闭
            }
            interpreter = Interpreter(modelBuffer, options)

            // 2. 加载 Tokenizer
            tokenizer = T5Tokenizer(context, "t5_tokenizer.json")

            Log.i(TAG, "Summary inference engine initialized: model=$modelPath")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize summary engine", e)
            throw e
        }
    }

    /**
     * 生成摘要
     */
    fun summarize(
        text: String,
        maxLength: Int = MAX_OUTPUT_LENGTH,
        minLength: Int = 64
    ): String {
        lock.withLock {
            val startTime = System.currentTimeMillis()
            totalCalls++

            try {
                // 1. Tokenization
                val inputText = "summarize: $text"
                val inputIds = tokenizer!!.encode(inputText, maxLength = MAX_INPUT_LENGTH)

                // 2. Encoder 推理
                val encoderOutput = runEncoder(inputIds)

                // 3. Decoder 推理（自回归生成）
                val outputIds = runDecoder(encoderOutput, maxLength, minLength)

                // 4. Decode tokens to text
                val summary = tokenizer!!.decode(outputIds)

                // 5. 记录延迟
                val latency = System.currentTimeMillis() - startTime
                recordLatency(latency)

                Log.d(TAG, "Summary generated: input=${text.length} chars, " +
                    "output=${summary.length} chars, latency=${latency}ms")

                return summary

            } catch (e: Exception) {
                errorCount++
                Log.e(TAG, "Summarization failed", e)
                return ""
            }
        }
    }

    /**
     * Encoder 推理
     */
    private fun runEncoder(inputIds: IntArray): FloatArray {
        val interpreter = this.interpreter ?: throw IllegalStateException("Interpreter not initialized")

        // 准备输入
        val inputBuffer = createInputBuffer(inputIds)

        // 准备输出（假设 encoder hidden state: [1, seq_len, 512]）
        val outputShape = intArrayOf(1, inputIds.size, 512)
        val outputBuffer = ByteBuffer.allocateDirect(outputShape[0] * outputShape[1] * outputShape[2] * 4)
        outputBuffer.order(ByteOrder.nativeOrder())

        // 运行推理
        interpreter.run(inputBuffer, outputBuffer)

        // 解析输出
        outputBuffer.rewind()
        val encoderOutput = FloatArray(outputShape[1] * outputShape[2])
        outputBuffer.asFloatBuffer().get(encoderOutput)

        return encoderOutput
    }

    /**
     * Decoder 推理（自回归生成）
     */
    private fun runDecoder(
        encoderOutput: FloatArray,
        maxLength: Int,
        minLength: Int
    ): IntArray {
        val outputIds = mutableListOf<Int>()
        var decoderInput = intArrayOf(START_TOKEN_ID)

        repeat(maxLength) { step ->
            // 运行 decoder
            val logits = runDecoderStep(encoderOutput, decoderInput)

            // Greedy decoding（取概率最高的 token）
            val nextTokenId = argmax(logits)

            // 检查结束条件
            if (nextTokenId == END_TOKEN_ID && step >= minLength) {
                break
            }

            outputIds.add(nextTokenId)
            decoderInput = outputIds.toIntArray()
        }

        return outputIds.toIntArray()
    }

    /**
     * Decoder 单步推理
     */
    private fun runDecoderStep(encoderOutput: FloatArray, decoderInput: IntArray): FloatArray {
        val interpreter = this.interpreter ?: throw IllegalStateException("Interpreter not initialized")

        // 准备输入
        val inputs = mutableMapOf<Int, Any>()
        inputs[0] = createInputBuffer(decoderInput)  // decoder_input_ids
        inputs[1] = createEncoderOutputBuffer(encoderOutput)  // encoder_hidden_states

        // 准备输出（vocab_size = 32128 for T5）
        val vocabSize = 32128
        val outputBuffer = ByteBuffer.allocateDirect(vocabSize * 4)
        outputBuffer.order(ByteOrder.nativeOrder())

        // 运行推理
        interpreter.runForMultipleInputsOutputs(arrayOf(inputs[0], inputs[1]), mapOf(0 to outputBuffer))

        // 解析 logits
        outputBuffer.rewind()
        val logits = FloatArray(vocabSize)
        outputBuffer.asFloatBuffer().get(logits)

        return logits
    }

    /**
     * 创建输入 Buffer
     */
    private fun createInputBuffer(inputIds: IntArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(inputIds.size * 4)
        buffer.order(ByteOrder.nativeOrder())
        
        inputIds.forEach { id ->
            buffer.putInt(id)
        }
        buffer.rewind()
        
        return buffer
    }

    /**
     * 创建 Encoder 输出 Buffer
     */
    private fun createEncoderOutputBuffer(encoderOutput: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(encoderOutput.size * 4)
        buffer.order(ByteOrder.nativeOrder())
        
        encoderOutput.forEach { value ->
            buffer.putFloat(value)
        }
        buffer.rewind()
        
        return buffer
    }

    /**
     * Argmax（找最大值索引）
     */
    private fun argmax(array: FloatArray): Int {
        var maxIndex = 0
        var maxValue = array[0]
        
        for (i in 1 until array.size) {
            if (array[i] > maxValue) {
                maxValue = array[i]
                maxIndex = i
            }
        }
        
        return maxIndex
    }

    /**
     * 加载模型文件
     */
    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * 记录延迟
     */
    private fun recordLatency(latency: Long) {
        latencyHistory.add(latency)
        
        // 保持窗口大小
        if (latencyHistory.size > STATS_WINDOW_SIZE) {
            latencyHistory.removeAt(0)
        }
    }

    /**
     * 获取性能统计
     */
    fun getPerformanceStats(): SummaryService.PerformanceStats {
        lock.withLock {
            if (latencyHistory.isEmpty()) {
                return SummaryService.PerformanceStats(
                    avgLatency = 0,
                    p95Latency = 0,
                    totalCalls = totalCalls,
                    errorCount = errorCount
                )
            }

            val sortedLatencies = latencyHistory.sorted()
            val avgLatency = latencyHistory.average().toLong()
            val p95Index = (sortedLatencies.size * 0.95).toInt().coerceAtMost(sortedLatencies.size - 1)
            val p95Latency = sortedLatencies[p95Index]

            return SummaryService.PerformanceStats(
                avgLatency = avgLatency,
                p95Latency = p95Latency,
                totalCalls = totalCalls,
                errorCount = errorCount
            )
        }
    }

    /**
     * 关闭引擎
     */
    fun close() {
        lock.withLock {
            interpreter?.close()
            interpreter = null
            tokenizer = null
            Log.i(TAG, "Summary inference engine closed")
        }
    }
}

/**
 * T5 Tokenizer（简化版）
 */
class T5Tokenizer(context: Context, tokenizerPath: String) {
    
    companion object {
        private const val TAG = "T5Tokenizer"
    }

    // TODO: 加载完整的 SentencePiece tokenizer
    // 当前为占位实现
    
    /**
     * 编码文本为 token IDs
     */
    fun encode(text: String, maxLength: Int = 512): IntArray {
        // TODO: 实现完整的 SentencePiece 编码
        // 占位实现：简单的空格分词
        val words = text.split(" ").take(maxLength)
        return words.map { it.hashCode() % 32000 }.toIntArray()
    }

    /**
     * 解码 token IDs 为文本
     */
    fun decode(tokenIds: IntArray): String {
        // TODO: 实现完整的 SentencePiece 解码
        // 占位实现：返回占位文本
        return "[摘要占位文本 - 待实现完整 Tokenizer]"
    }
}
