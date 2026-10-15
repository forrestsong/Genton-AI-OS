package com.ainative.brain.memory.extraction

import android.content.Context
import android.util.Log
import com.ainative.brain.memory.extraction.FactExtractor.ExtractedFact
import com.ainative.brain.memory.extraction.PreferenceExtractor.ExtractedPreference
import com.ainative.brain.memory.security.PiiMaskIntegration
import com.ainative.brain.memory.security.TieredEncryption
import com.ainative.brain.memory.security.ProvenanceTracker
import com.ainative.brain.memory.integration.MemoryServiceIntegration
import kotlinx.coroutines.*

/**
 * C3.4 记忆抽取 - 后台抽取任务
 * 
 * 对话结束后自动触发的记忆抽取流程：
 * 1. 事实抽取（FactExtractor）
 * 2. 偏好抽取（PreferenceExtractor）
 * 3. PII 掩码检测（PiiMaskIntegration）
 * 4. 分级加密（TieredEncryption）
 * 5. 来源标注（ProvenanceTracker）
 * 6. 写入记忆服务（IAgentMemoryService）
 * 
 * 执行时机：对话结束后 5 秒（debounce）
 * 并发策略：每个对话独立抽取，同时最多 2 个任务
 */
class MemoryExtractionTask(private val context: Context) {

    companion object {
        private const val TAG = "MemoryExtractionTask"
        private const val DEBOUNCE_DELAY_MS = 5000L // 5 秒防抖
        private const val MAX_CONCURRENT_TASKS = 2
    }

    data class Message(
        val id: String,
        val role: String,           // "user" or "assistant"
        val content: String,
        val timestamp: Long
    )

    data class ExtractionResult(
        val conversationId: String,
        val factsCount: Int,
        val preferencesCount: Int,
        val rejectedCount: Int,     // PII 拒绝入库数量
        val encryptedCount: Int,
        val success: Boolean,
        val errorMessage: String? = null
    )

    private val factExtractor = FactExtractor()
    private val preferenceExtractor = PreferenceExtractor()
    private val piiMaskIntegration = PiiMaskIntegration(context)
    private val tieredEncryption = TieredEncryption(context)
    private val provenanceTracker = ProvenanceTracker()
    private val memoryServiceIntegration = MemoryServiceIntegration(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_TASKS)

    /**
     * 触发对话记忆抽取（带防抖）
     */
    fun scheduleExtraction(conversationId: String, messages: List<Message>) {
        scope.launch {
            // 防抖延迟
            delay(DEBOUNCE_DELAY_MS)
            
            // 限制并发数
            semaphore.withPermit {
                try {
                    val result = extractMemories(conversationId, messages)
                    logResult(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Extraction failed for conversation $conversationId", e)
                }
            }
        }
    }

    /**
     * 执行完整抽取流程
     */
    private suspend fun extractMemories(
        conversationId: String,
        messages: List<Message>
    ): ExtractionResult = withContext(Dispatchers.Default) {
        
        Log.i(TAG, "Starting extraction for conversation $conversationId (${messages.size} messages)")

        var factsCount = 0
        var preferencesCount = 0
        var rejectedCount = 0
        var encryptedCount = 0

        try {
            // Step 1: 并行抽取事实和偏好
            val (facts, preferences) = coroutineScope {
                val factsDeferred = async { factExtractor.extract(messages.map { toExtractorMessage(it) }) }
                val preferencesDeferred = async { preferenceExtractor.extract(messages.map { toExtractorMessage(it) }) }
                
                Pair(factsDeferred.await(), preferencesDeferred.await())
            }

            Log.d(TAG, "Extracted ${facts.size} facts, ${preferences.size} preferences")

            // Step 2: 处理事实类记忆
            for (fact in facts) {
                val processed = processFact(conversationId, fact)
                when (processed.status) {
                    ProcessStatus.SUCCESS -> factsCount++
                    ProcessStatus.REJECTED -> rejectedCount++
                    ProcessStatus.ENCRYPTED -> {
                        factsCount++
                        encryptedCount++
                    }
                }
            }

            // Step 3: 处理偏好类记忆
            for (preference in preferences) {
                val processed = processPreference(conversationId, preference)
                when (processed.status) {
                    ProcessStatus.SUCCESS -> preferencesCount++
                    ProcessStatus.REJECTED -> rejectedCount++
                    ProcessStatus.ENCRYPTED -> {
                        preferencesCount++
                        encryptedCount++
                    }
                }
            }

            return@withContext ExtractionResult(
                conversationId = conversationId,
                factsCount = factsCount,
                preferencesCount = preferencesCount,
                rejectedCount = rejectedCount,
                encryptedCount = encryptedCount,
                success = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "Extraction error", e)
            return@withContext ExtractionResult(
                conversationId = conversationId,
                factsCount = factsCount,
                preferencesCount = preferencesCount,
                rejectedCount = rejectedCount,
                encryptedCount = encryptedCount,
                success = false,
                errorMessage = e.message
            )
        }
    }

    /**
     * 处理事实类记忆
     */
    private fun processFact(conversationId: String, fact: ExtractedFact): ProcessedMemory {
        // Step 1: PII 掩码检测
        val maskResult = piiMaskIntegration.maskFact(fact)
        
        if (maskResult.status == PiiMaskIntegration.MaskStatus.REJECT) {
            Log.w(TAG, "Fact rejected due to sensitive PII: ${fact.type}")
            return ProcessedMemory(ProcessStatus.REJECTED)
        }

        val content = maskResult.content

        // Step 2: 判定敏感度
        val sensitivity = tieredEncryption.determineSensitivity(
            detectedPiiCategories = maskResult.detectedCategories,
            memoryType = TieredEncryption.MemoryType.FACT
        )

        // Step 3: 加密
        val encrypted = tieredEncryption.encrypt(content, sensitivity)

        // Step 4: 来源标注
        val provenance = provenanceTracker.createProvenance(
            sourceKind = ProvenanceTracker.SourceKind.USER_CONVERSATION,
            sourceRef = conversationId,
            extractionModel = ProvenanceTracker.MODEL_RULE_BASED_V1,
            content = content
        )

        // Step 5: 写入记忆服务（TODO: 集成 A5.1）
        writeToMemoryService(
            type = "FACT",
            content = encrypted.content,
            encrypted = encrypted.encrypted,
            iv = encrypted.iv,
            keyAlias = encrypted.keyAlias,
            provenance = provenance,
            metadata = mapOf(
                "factType" to fact.type.name,
                "confidence" to fact.confidence.toString(),
                "entities" to fact.entities.joinToString(",") { it.text }
            )
        )

        val status = if (encrypted.encrypted) ProcessStatus.ENCRYPTED else ProcessStatus.SUCCESS
        return ProcessedMemory(status)
    }

    /**
     * 处理偏好类记忆
     */
    private fun processPreference(conversationId: String, preference: ExtractedPreference): ProcessedMemory {
        // Step 1: PII 掩码检测
        val maskResult = piiMaskIntegration.maskPreference(preference)
        
        if (maskResult.status == PiiMaskIntegration.MaskStatus.REJECT) {
            Log.w(TAG, "Preference rejected due to sensitive PII: ${preference.type}")
            return ProcessedMemory(ProcessStatus.REJECTED)
        }

        val content = "${preference.type.name}: ${maskResult.content} (strength=${preference.strength})"

        // Step 2: 偏好类默认中敏感
        val sensitivity = TieredEncryption.SensitivityLevel.MEDIUM

        // Step 3: 加密
        val encrypted = tieredEncryption.encrypt(content, sensitivity)

        // Step 4: 来源标注
        val provenance = provenanceTracker.createProvenance(
            sourceKind = ProvenanceTracker.SourceKind.USER_CONVERSATION,
            sourceRef = conversationId,
            extractionModel = ProvenanceTracker.MODEL_RULE_BASED_V1,
            content = content
        )

        // Step 5: 写入记忆服务（TODO: 集成 A5.1）
        writeToMemoryService(
            type = "PREFERENCE",
            content = encrypted.content,
            encrypted = encrypted.encrypted,
            iv = encrypted.iv,
            keyAlias = encrypted.keyAlias,
            provenance = provenance,
            metadata = mapOf(
                "preferenceType" to preference.type.name,
                "subject" to preference.subject,
                "strength" to preference.strength.toString(),
                "confidence" to preference.confidence.toString()
            )
        )

        return ProcessedMemory(ProcessStatus.ENCRYPTED)
    }

    /**
     * 写入记忆服务
     */
    private fun writeToMemoryService(
        type: String,
        content: String,
        encrypted: Boolean,
        iv: String?,
        keyAlias: String?,
        provenance: ProvenanceTracker.Provenance,
        metadata: Map<String, String>
    ): Boolean {
        return try {
            when (type) {
                "FACT" -> {
                    // 从 metadata 重建 ExtractedFact（简化版）
                    val fact = FactExtractor.ExtractedFact(
                        type = FactExtractor.FactType.valueOf(metadata["factType"]!!),
                        content = content,
                        entities = emptyList(),  // 已在 tags 中
                        confidence = metadata["confidence"]!!.toFloat()
                    )
                    memoryServiceIntegration.writeFact(
                        conversationId = provenance.sourceRef,
                        fact = fact,
                        encryptedContent = content,
                        encrypted = encrypted,
                        iv = iv,
                        keyAlias = keyAlias,
                        provenance = provenance
                    )
                }
                "PREFERENCE" -> {
                    val preference = PreferenceExtractor.ExtractedPreference(
                        type = PreferenceExtractor.PreferenceType.valueOf(metadata["preferenceType"]!!),
                        subject = metadata["subject"]!!,
                        strength = metadata["strength"]!!.toInt(),
                        confidence = metadata["confidence"]!!.toFloat()
                    )
                    memoryServiceIntegration.writePreference(
                        conversationId = provenance.sourceRef,
                        preference = preference,
                        encryptedContent = content,
                        encrypted = encrypted,
                        iv = iv,
                        keyAlias = keyAlias,
                        provenance = provenance
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown memory type: $type")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to memory service", e)
            false
        }
    }

    /**
     * 消息格式转换
     */
    private fun toExtractorMessage(message: Message): FactExtractor.Message {
        return FactExtractor.Message(
            role = message.role,
            content = message.content,
            timestamp = message.timestamp
        )
    }

    /**
     * 记录抽取结果
     */
    private fun logResult(result: ExtractionResult) {
        if (result.success) {
            Log.i(TAG, "Extraction completed: conversation=${result.conversationId}, " +
                "facts=${result.factsCount}, preferences=${result.preferencesCount}, " +
                "rejected=${result.rejectedCount}, encrypted=${result.encryptedCount}")
        } else {
            Log.e(TAG, "Extraction failed: conversation=${result.conversationId}, " +
                "error=${result.errorMessage}")
        }
    }

    /**
     * 清理资源
     */
    fun shutdown() {
        scope.cancel()
    }

    enum class ProcessStatus {
        SUCCESS,        // 成功入库（明文）
        ENCRYPTED,      // 成功入库（加密）
        REJECTED        // 拒绝入库
    }

    private data class ProcessedMemory(val status: ProcessStatus)
}
