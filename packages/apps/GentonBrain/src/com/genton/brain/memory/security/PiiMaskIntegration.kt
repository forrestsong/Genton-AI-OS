package com.genton.brain.memory.security

import android.content.Context
import android.util.Log
import com.genton.privacy.PiiMaskService
import com.genton.privacy.PiiMaskRules
import com.genton.brain.memory.extraction.ExtractedFact
import com.genton.brain.memory.extraction.ExtractedPreference

/**
 * PII 掩码集成 - 记忆写入前的 PII 检测与掩码
 * 
 * 职责：
 * 1. 调用 C4.3 PII 掩码服务
 * 2. 强敏感 PII（密码/证件/金融）拒绝入库
 * 3. 中等敏感 PII（手机号/邮箱）掩码后入库
 * 
 * 拒绝规则（对齐 E3.1 验收基准）：
 * - PASSWORD: 密码/口令/验证码 → 拒绝
 * - BANK_CARD: 银行卡号/CVV → 拒绝
 * - ID_CARD: 身份证号/护照号 → 拒绝
 * - FINANCIAL: 大额金额（≥10000）→ 拒绝
 * 
 * 掩码规则：
 * - PHONE: 手机号 → 掩码（138****5678）
 * - EMAIL: 邮箱 → 掩码（u***@example.com）
 * - IP_ADDRESS: IP 地址 → 掩码
 * 
 * @since A3
 */
class PiiMaskIntegration(context: Context) {
    
    companion object {
        private const val TAG = "PiiMaskIntegration"
        
        // 强敏感 PII 类别（拒绝入库）
        private val REJECT_CATEGORIES = setOf(
            PiiMaskRules.PiiCategory.PASSWORD,
            PiiMaskRules.PiiCategory.BANK_CARD,
            PiiMaskRules.PiiCategory.ID_CARD,
            PiiMaskRules.PiiCategory.FINANCIAL  // 仅当金额 ≥10000
        )
        
        // 中等敏感 PII 类别（掩码后入库）
        private val MASK_CATEGORIES = setOf(
            PiiMaskRules.PiiCategory.PHONE,
            PiiMaskRules.PiiCategory.EMAIL,
            PiiMaskRules.PiiCategory.IP_ADDRESS
        )
    }
    
    private val piiMaskService = PiiMaskService.getInstance(context)
    
    /**
     * 对事实进行 PII 掩码
     * 
     * @param fact 抽取的事实
     * @return 掩码结果
     */
    fun maskFact(fact: ExtractedFact): MaskedMemory {
        val maskResult = piiMaskService.maskPii(
            fact.content,
            PiiMaskService.MaskMode.AUTO,
            aggressiveMode = true
        )
        
        return processMaskResult(
            originalContent = fact.content,
            maskResult = maskResult,
            memoryType = "FACT"
        )
    }
    
    /**
     * 对偏好进行 PII 掩码
     * 
     * @param preference 抽取的偏好
     * @return 掩码结果
     */
    fun maskPreference(preference: ExtractedPreference): MaskedMemory {
        // 偏好通常不含 PII，但仍需检测
        val text = "${preference.type}: ${preference.subject}"
        
        val maskResult = piiMaskService.maskPii(
            text,
            PiiMaskService.MaskMode.AUTO,
            aggressiveMode = false  // 偏好用宽松模式
        )
        
        return processMaskResult(
            originalContent = text,
            maskResult = maskResult,
            memoryType = "PREFERENCE"
        )
    }
    
    /**
     * 对通用文本进行 PII 掩码（用于摘要等场景）
     * 
     * @param text 待掩码文本
     * @return 掩码结果
     */
    fun maskText(text: String): MaskedMemory {
        val maskResult = piiMaskService.maskPii(
            text,
            PiiMaskService.MaskMode.AUTO,
            aggressiveMode = true
        )
        
        return processMaskResult(
            originalContent = text,
            maskResult = maskResult,
            memoryType = "TEXT"
        )
    }
    
    /**
     * 处理掩码结果
     */
    private fun processMaskResult(
        originalContent: String,
        maskResult: PiiMaskRules.MaskResult,
        memoryType: String
    ): MaskedMemory {
        
        if (!maskResult.maskApplied) {
            // 未检测到 PII，直接通过
            return MaskedMemory(
                status = MaskStatus.OK,
                content = originalContent,
                piiMasked = false,
                detectedCategories = emptyList()
            )
        }
        
        val detectedCategories = maskResult.detectedPii.map { it.category }.toSet()
        
        // 检查是否包含强敏感 PII
        val hasRejectPii = detectedCategories.any { it in REJECT_CATEGORIES }
        
        if (hasRejectPii) {
            val rejectReason = buildRejectReason(detectedCategories)
            
            Log.w(TAG, "[$memoryType] 检测到强敏感 PII，拒绝入库：$rejectReason")
            
            return MaskedMemory(
                status = MaskStatus.REJECT,
                content = "",
                piiMasked = false,
                detectedCategories = detectedCategories.toList(),
                rejectReason = rejectReason
            )
        }
        
        // 中等敏感 PII，掩码后入库
        val hasMaskPii = detectedCategories.any { it in MASK_CATEGORIES }
        
        if (hasMaskPii) {
            Log.i(TAG, "[$memoryType] 检测到中等敏感 PII，已掩码：${detectedCategories}")
            
            return MaskedMemory(
                status = MaskStatus.OK,
                content = maskResult.maskedText,
                piiMasked = true,
                detectedCategories = detectedCategories.toList()
            )
        }
        
        // 其他 PII（如疑似敏感数字串），掩码后入库
        Log.d(TAG, "[$memoryType] 检测到其他 PII，已掩码")
        
        return MaskedMemory(
            status = MaskStatus.OK,
            content = maskResult.maskedText,
            piiMasked = true,
            detectedCategories = detectedCategories.toList()
        )
    }
    
    /**
     * 构建拒绝原因
     */
    private fun buildRejectReason(categories: Set<PiiMaskRules.PiiCategory>): String {
        val reasons = mutableListOf<String>()
        
        if (PiiMaskRules.PiiCategory.PASSWORD in categories) {
            reasons.add("密码/验证码")
        }
        if (PiiMaskRules.PiiCategory.BANK_CARD in categories) {
            reasons.add("银行卡号")
        }
        if (PiiMaskRules.PiiCategory.ID_CARD in categories) {
            reasons.add("身份证号")
        }
        if (PiiMaskRules.PiiCategory.FINANCIAL in categories) {
            reasons.add("大额金额")
        }
        
        return "包含强敏感 PII：${reasons.joinToString("、")}"
    }
    
    /**
     * 批量掩码（优化）
     */
    fun maskBatch(contents: List<String>): List<MaskedMemory> {
        return contents.map { content ->
            val maskResult = piiMaskService.maskPii(content)
            processMaskResult(content, maskResult, "BATCH")
        }
    }
}

/**
 * 掩码后的记忆
 */
data class MaskedMemory(
    val status: MaskStatus,
    val content: String,
    val piiMasked: Boolean,
    val detectedCategories: List<PiiMaskRules.PiiCategory>,
    val rejectReason: String? = null
)

/**
 * 掩码状态
 */
enum class MaskStatus {
    OK,       // 通过（无 PII 或已掩码）
    REJECT    // 拒绝（强敏感 PII）
}
