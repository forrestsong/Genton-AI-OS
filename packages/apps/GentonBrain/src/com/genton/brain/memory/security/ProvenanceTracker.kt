package com.genton.brain.memory.security

import android.util.Log
import java.security.MessageDigest
import java.util.*

/**
 * C3.4 记忆抽取 - 来源标注（Provenance）
 * 
 * 为每条记忆标注来源信息，防止长期记忆投毒攻击：
 * - sourceKind: 来源类型（USER_CONVERSATION/SYSTEM_INFERENCE/EXTERNAL_IMPORT）
 * - sourceRef: 来源引用（对话 ID/推理任务 ID/导入文件路径）
 * - extractionModel: 抽取模型版本（如 "rule_based_v1" 或 "ner_model_v2"）
 * - contentFingerprint: 内容指纹（SHA-256 摘要前 16 字节）
 * - trustScore: 可信度评分（0.0-1.0）
 * - extractionTimestamp: 抽取时间戳
 * 
 * 可信度计算规则：
 * - USER_CONVERSATION（用户对话）: 0.95
 * - SYSTEM_INFERENCE（系统推理）: 0.75
 * - EXTERNAL_IMPORT（外部导入）: 0.50
 */
class ProvenanceTracker {

    companion object {
        private const val TAG = "ProvenanceTracker"
        
        // 抽取模型版本
        const val MODEL_RULE_BASED_V1 = "rule_based_v1"
        const val MODEL_NER_V1 = "ner_model_v1"
    }

    enum class SourceKind(val trustScore: Float) {
        USER_CONVERSATION(0.95f),   // 用户对话（最可信）
        SYSTEM_INFERENCE(0.75f),    // 系统推理（中等可信）
        EXTERNAL_IMPORT(0.50f)      // 外部导入（低可信）
    }

    data class Provenance(
        val sourceKind: SourceKind,
        val sourceRef: String,
        val extractionModel: String,
        val contentFingerprint: String,
        val trustScore: Float,
        val extractionTimestamp: Long
    )

    /**
     * 创建来源标注
     */
    fun createProvenance(
        sourceKind: SourceKind,
        sourceRef: String,
        extractionModel: String,
        content: String
    ): Provenance {
        val fingerprint = computeFingerprint(content)
        val timestamp = System.currentTimeMillis()

        return Provenance(
            sourceKind = sourceKind,
            sourceRef = sourceRef,
            extractionModel = extractionModel,
            contentFingerprint = fingerprint,
            trustScore = sourceKind.trustScore,
            extractionTimestamp = timestamp
        )
    }

    /**
     * 验证记忆完整性（检查内容是否被篡改）
     */
    fun verifyIntegrity(content: String, provenance: Provenance): Boolean {
        val currentFingerprint = computeFingerprint(content)
        val isValid = currentFingerprint == provenance.contentFingerprint

        if (!isValid) {
            Log.w(TAG, "Memory integrity check failed: " +
                "expected=${provenance.contentFingerprint}, " +
                "actual=$currentFingerprint")
        }

        return isValid
    }

    /**
     * 评估记忆可信度（综合来源和时效性）
     */
    fun evaluateTrust(provenance: Provenance): Float {
        val baseScore = provenance.trustScore

        // 时效性衰减（超过 90 天的记忆可信度降低 10%）
        val ageInDays = (System.currentTimeMillis() - provenance.extractionTimestamp) / (24 * 60 * 60 * 1000)
        val agePenalty = if (ageInDays > 90) 0.1f else 0.0f

        return (baseScore - agePenalty).coerceIn(0.0f, 1.0f)
    }

    /**
     * 检查是否为高风险来源（外部导入且可信度低）
     */
    fun isHighRisk(provenance: Provenance): Boolean {
        return provenance.sourceKind == SourceKind.EXTERNAL_IMPORT &&
               evaluateTrust(provenance) < 0.4f
    }

    /**
     * 计算内容指纹（SHA-256 前 16 字节 Base64）
     */
    private fun computeFingerprint(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        
        // 截取前 16 字节并 Base64 编码
        val truncated = hash.copyOfRange(0, 16)
        return Base64.getEncoder().encodeToString(truncated)
    }

    /**
     * 批量创建来源标注（用于对话后批量抽取）
     */
    fun createBatchProvenance(
        conversationId: String,
        extractionModel: String,
        contents: List<String>
    ): List<Provenance> {
        return contents.map { content ->
            createProvenance(
                sourceKind = SourceKind.USER_CONVERSATION,
                sourceRef = conversationId,
                extractionModel = extractionModel,
                content = content
            )
        }
    }

    /**
     * 序列化为存储格式
     */
    fun serialize(provenance: Provenance): String {
        return "${provenance.sourceKind.name}|" +
               "${provenance.sourceRef}|" +
               "${provenance.extractionModel}|" +
               "${provenance.contentFingerprint}|" +
               "${provenance.trustScore}|" +
               "${provenance.extractionTimestamp}"
    }

    /**
     * 从存储格式反序列化
     */
    fun deserialize(serialized: String): Provenance? {
        return try {
            val parts = serialized.split("|")
            if (parts.size != 6) {
                Log.e(TAG, "Invalid provenance format: $serialized")
                return null
            }

            Provenance(
                sourceKind = SourceKind.valueOf(parts[0]),
                sourceRef = parts[1],
                extractionModel = parts[2],
                contentFingerprint = parts[3],
                trustScore = parts[4].toFloat(),
                extractionTimestamp = parts[5].toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize provenance: $serialized", e)
            null
        }
    }
}
