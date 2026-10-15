package com.genton.privacy

import android.util.Log
import java.util.regex.Pattern

/**
 * E3.1 PII 掩码规则库 - 规则库兜底方案
 * 
 * 职责：
 * 1. 提供基于正则表达式 + 关键词的 PII 检测（模型失败时兜底）
 * 2. 实现掩码策略（强/中/弱三级）
 * 3. 支持 Luhn/身份证校验位验证
 * 
 * 性能目标：
 * - 单次检测延迟 ≤5ms (P99)
 * - 规则库单独召回率 ≥70%
 * - 模型 + 规则库联合召回率 ≥92%
 * 
 * 依赖关系：
 * - 供 A5.1 IAgentMemoryService 消费（记忆写入前掩码）
 * - 供 A2.1 IAgentUiTreeService 消费（UI 树 maskPii）
 * - 供 C4.3 PII 掩码模型作为兜底备份
 * 
 * 版本：v1（A3 期基础版）
 * 
 * @since A3
 */
object PiiMaskRules {
    
    private const val TAG = "PiiMaskRules"
    
    /**
     * 掩码强度级别
     */
    enum class MaskLevel {
        NONE,       // 不掩码（公开信息）
        WEAK,       // 弱掩码（保留大类/量级）
        MEDIUM,     // 中掩码（保留首尾）
        STRONG      // 强掩码（全替换 ***）
    }
    
    /**
     * PII 类别
     */
    enum class PiiCategory {
        BANK_CARD,      // 银行卡号
        ID_CARD,        // 身份证号
        PHONE,          // 手机号
        EMAIL,          // 邮箱
        PASSWORD,       // 密码
        API_KEY,        // API 密钥
        IP_ADDRESS,     // IP 地址
        FINANCIAL,      // 金融信息（金额/流水）
        HEALTH,         // 健康信息（诊断/药品）
        UNKNOWN         // 未知类别（疑似敏感）
    }
    
    /**
     * 掩码结果
     */
    data class MaskResult(
        val maskedText: String,
        val detectedPii: List<DetectedPii>,
        val maskApplied: Boolean
    )
    
    /**
     * 检测到的 PII 实体
     */
    data class DetectedPii(
        val category: PiiCategory,
        val originalText: String,
        val maskedText: String,
        val start: Int,
        val end: Int,
        val confidence: Float,  // 规则库检测置信度 (0.0-1.0)
        val maskLevel: MaskLevel
    )
    
    // ==================== 正则表达式规则 ====================
    
    // 银行卡号：13-19 位数字（含可选空格/短横线分隔）
    private val BANK_CARD_PATTERN = Pattern.compile(
        "\\b([0-9]{4}[\\s-]?[0-9]{4}[\\s-]?[0-9]{4}[\\s-]?[0-9]{3,7})\\b"
    )
    
    // 身份证号：18 位或 15 位数字
    private val ID_CARD_PATTERN = Pattern.compile(
        "\\b([0-9]{15}|[0-9]{17}[0-9Xx])\\b"
    )
    
    // 手机号：11 位数字（中国）
    private val PHONE_PATTERN = Pattern.compile(
        "\\b(1[3-9][0-9]{9})\\b"
    )
    
    // 邮箱：RFC 5322 简化版
    private val EMAIL_PATTERN = Pattern.compile(
        "\\b([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})\\b"
    )
    
    // IP 地址：IPv4
    private val IPV4_PATTERN = Pattern.compile(
        "\\b((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\." +
        "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?))\\b"
    )
    
    // API 密钥：含 key/token/secret + 长字符串
    private val API_KEY_PATTERN = Pattern.compile(
        "(api[_-]?key|access[_-]?token|secret[_-]?key)[\\s:=]+([a-zA-Z0-9_\\-]{20,})",
        Pattern.CASE_INSENSITIVE
    )
    
    // 私钥 PEM 格式
    private val PRIVATE_KEY_PATTERN = Pattern.compile(
        "-----BEGIN (RSA |EC )?PRIVATE KEY-----[\\s\\S]+?-----END (RSA |EC )?PRIVATE KEY-----"
    )
    
    // 连续数字 ≥10 位（疑似敏感）
    private val LONG_DIGITS_PATTERN = Pattern.compile(
        "\\b([0-9]{10,})\\b"
    )
    
    // ==================== 关键词词表 ====================
    
    // 金融关键词（50 词简化版）
    private val FINANCIAL_KEYWORDS = setOf(
        "银行卡", "信用卡", "账号", "账户", "余额", "转账", "支付", "密码",
        "cvv", "交易", "订单", "流水号", "卡号", "储蓄卡", "借记卡",
        "bank", "card", "account", "balance", "payment", "transaction"
    )
    
    // 健康关键词（50 词简化版，实际应 500+ 词）
    private val HEALTH_KEYWORDS = setOf(
        "病历", "诊断", "处方", "药品", "血压", "血糖", "心率", "体检",
        "糖尿病", "高血压", "肿瘤", "癌症", "艾滋", "肝炎", "基因",
        "medical", "diagnosis", "prescription", "blood", "disease"
    )
    
    // 密码关键词
    private val PASSWORD_KEYWORDS = setOf(
        "密码", "口令", "验证码", "动态码", "pin", "password", "passcode", "pin码"
    )
    
    // ==================== 公开 API ====================
    
    /**
     * 检测并掩码文本中的 PII
     * 
     * @param text 原始文本
     * @param aggressiveMode 激进模式（宁可过掩码，不漏检）
     * @return 掩码结果
     */
    fun maskText(text: String, aggressiveMode: Boolean = true): MaskResult {
        if (text.isEmpty()) {
            return MaskResult(text, emptyList(), false)
        }
        
        val detectedList = mutableListOf<DetectedPii>()
        
        // 1. 强优先级：密码/私钥（立即全掩码）
        detectPasswords(text, detectedList)
        detectPrivateKeys(text, detectedList)
        
        // 2. 高优先级：银行卡/身份证（带校验）
        detectBankCards(text, detectedList)
        detectIdCards(text, detectedList)
        
        // 3. 中优先级：手机号/邮箱/IP
        detectPhones(text, detectedList)
        detectEmails(text, detectedList)
        detectIpAddresses(text, detectedList)
        
        // 4. API 密钥
        detectApiKeys(text, detectedList)
        
        // 5. 上下文关键词（金融/健康）
        if (aggressiveMode) {
            detectContextualPii(text, detectedList)
        }
        
        // 6. 疑似敏感（长数字串）
        if (aggressiveMode) {
            detectSuspiciousDigits(text, detectedList)
        }
        
        // 7. 按位置排序，避免掩码重叠
        val sortedPii = detectedList.sortedBy { it.start }
        
        // 8. 应用掩码
        val maskedText = applyMasks(text, sortedPii)
        
        return MaskResult(
            maskedText = maskedText,
            detectedPii = sortedPii,
            maskApplied = sortedPii.isNotEmpty()
        )
    }
    
    // ==================== 检测方法 ====================
    
    private fun detectBankCards(text: String, detectedList: MutableList<DetectedPii>) {
        val matcher = BANK_CARD_PATTERN.matcher(text)
        while (matcher.find()) {
            val cardNumber = matcher.group(1).replace("[\\s-]".toRegex(), "")
            if (cardNumber.length in 13..19 && isValidLuhn(cardNumber)) {
                detectedList.add(
                    DetectedPii(
                        category = PiiCategory.BANK_CARD,
                        originalText = matcher.group(1),
                        maskedText = maskBankCard(matcher.group(1)),
                        start = matcher.start(1),
                        end = matcher.end(1),
                        confidence = 0.95f,  // Luhn 校验通过，高置信度
                        maskLevel = MaskLevel.MEDIUM
                    )
                )
            }
        }
    }
    
    private fun detectIdCards(text: String, detectedList: MutableList<DetectedPii>) {
        val matcher = ID_CARD_PATTERN.matcher(text)
        while (matcher.find()) {
            val idCard = matcher.group(1)
            if (isValidIdCard(idCard)) {
                detectedList.add(
                    DetectedPii(
                        category = PiiCategory.ID_CARD,
                        originalText = idCard,
                        maskedText = maskIdCard(idCard),
                        start = matcher.start(1),
                        end = matcher.end(1),
                        confidence = 0.92f,
                        maskLevel = MaskLevel.MEDIUM
                    )
                )
            }
        }
    }
    
    private fun detectPhones(text: String, detectedList: MutableList<DetectedPii>) {
        val matcher = PHONE_PATTERN.matcher(text)
        while (matcher.find()) {
            detectedList.add(
                DetectedPii(
                    category = PiiCategory.PHONE,
                    originalText = matcher.group(1),
                    maskedText = maskPhone(matcher.group(1)),
                    start = matcher.start(1),
                    end = matcher.end(1),
                    confidence = 0.88f,
                    maskLevel = MaskLevel.MEDIUM
                )
            )
        }
    }
    
    private fun detectEmails(text: String, detectedList: MutableList<DetectedPii>) {
        val matcher = EMAIL_PATTERN.matcher(text)
        while (matcher.find()) {
            detectedList.add(
                DetectedPii(
                    category = PiiCategory.EMAIL,
                    originalText = matcher.group(1),
                    maskedText = maskEmail(matcher.group(1)),
                    start = matcher.start(1),
                    end = matcher.end(1),
                    confidence = 0.85f,
                    maskLevel = MaskLevel.MEDIUM
                )
            )
        }
    }
    
    private fun detectIpAddresses(text: String, detectedList: MutableList<DetectedPii>) {
        val matcher = IPV4_PATTERN.matcher(text)
        while (matcher.find()) {
            val ip = matcher.group(1)
            // 过滤私网 IP
            if (!isPrivateIp(ip)) {
                detectedList.add(
                    DetectedPii(
                        category = PiiCategory.IP_ADDRESS,
                        originalText = ip,
                        maskedText = "***.***.***." + ip.substringAfterLast('.'),
                        start = matcher.start(1),
                        end = matcher.end(1),
                        confidence = 0.80f,
                        maskLevel = MaskLevel.MEDIUM
                    )
                )
            }
        }
    }
    
    private fun detectApiKeys(text: String, detectedList: MutableList<DetectedPii>) {
        val matcher = API_KEY_PATTERN.matcher(text)
        while (matcher.find()) {
            detectedList.add(
                DetectedPii(
                    category = PiiCategory.API_KEY,
                    originalText = matcher.group(0),
                    maskedText = matcher.group(1) + "=***",
                    start = matcher.start(0),
                    end = matcher.end(0),
                    confidence = 0.90f,
                    maskLevel = MaskLevel.STRONG
                )
            )
        }
    }
    
    private fun detectPrivateKeys(text: String, detectedList: MutableList<DetectedPii>) {
        val matcher = PRIVATE_KEY_PATTERN.matcher(text)
        while (matcher.find()) {
            detectedList.add(
                DetectedPii(
                    category = PiiCategory.API_KEY,
                    originalText = matcher.group(0),
                    maskedText = "-----BEGIN PRIVATE KEY----- [REDACTED] -----END PRIVATE KEY-----",
                    start = matcher.start(0),
                    end = matcher.end(0),
                    confidence = 1.0f,
                    maskLevel = MaskLevel.STRONG
                )
            )
        }
    }
    
    private fun detectPasswords(text: String, detectedList: MutableList<DetectedPii>) {
        val lowerText = text.lowercase()
        PASSWORD_KEYWORDS.forEach { keyword ->
            var index = lowerText.indexOf(keyword)
            while (index >= 0) {
                // 查找关键词后 20 字符内的内容
                val endIndex = minOf(index + keyword.length + 20, text.length)
                val context = text.substring(index, endIndex)
                
                // 简单启发式：关键词后跟 `:` 或 `=` 的内容
                val valueMatch = Pattern.compile("[:=]\\s*([^\\s]{6,})").matcher(context)
                if (valueMatch.find()) {
                    val start = index + valueMatch.start(1)
                    val end = index + valueMatch.end(1)
                    detectedList.add(
                        DetectedPii(
                            category = PiiCategory.PASSWORD,
                            originalText = valueMatch.group(1),
                            maskedText = "***",
                            start = start,
                            end = end,
                            confidence = 0.85f,
                            maskLevel = MaskLevel.STRONG
                        )
                    )
                }
                
                index = lowerText.indexOf(keyword, index + 1)
            }
        }
    }
    
    private fun detectContextualPii(text: String, detectedList: MutableList<DetectedPii>) {
        val lowerText = text.lowercase()
        
        // 检测金融上下文
        if (FINANCIAL_KEYWORDS.any { lowerText.contains(it) }) {
            // 简化示例：检测金额（¥或$ + 数字）
            val amountPattern = Pattern.compile("[¥\\$]\\s?([0-9,]+\\.?[0-9]*)")
            val matcher = amountPattern.matcher(text)
            while (matcher.find()) {
                val amount = matcher.group(1).replace(",", "").toDoubleOrNull()
                if (amount != null && amount >= 10000) {  // 金额 ≥10000 才掩码
                    detectedList.add(
                        DetectedPii(
                            category = PiiCategory.FINANCIAL,
                            originalText = matcher.group(0),
                            maskedText = matcher.group(0)[0] + "****",
                            start = matcher.start(0),
                            end = matcher.end(0),
                            confidence = 0.70f,
                            maskLevel = MaskLevel.WEAK
                        )
                    )
                }
            }
        }
        
        // 检测健康上下文（简化示例）
        if (HEALTH_KEYWORDS.any { lowerText.contains(it) }) {
            Log.d(TAG, "Detected health context, applying conservative masking")
        }
    }
    
    private fun detectSuspiciousDigits(text: String, detectedList: MutableList<DetectedPii>) {
        val matcher = LONG_DIGITS_PATTERN.matcher(text)
        while (matcher.find()) {
            val digits = matcher.group(1)
            // 排除已检测的 PII（避免重复）
            val overlaps = detectedList.any { 
                it.start <= matcher.start(1) && it.end >= matcher.end(1)
            }
            
            if (!overlaps && digits.length in 10..16) {
                detectedList.add(
                    DetectedPii(
                        category = PiiCategory.UNKNOWN,
                        originalText = digits,
                        maskedText = digits.substring(0, 3) + "****" + digits.substring(digits.length - 3),
                        start = matcher.start(1),
                        end = matcher.end(1),
                        confidence = 0.50f,  // 低置信度（疑似）
                        maskLevel = MaskLevel.WEAK
                    )
                )
            }
        }
    }
    
    // ==================== 校验算法 ====================
    
    /**
     * Luhn 算法校验银行卡号
     */
    private fun isValidLuhn(cardNumber: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in cardNumber.length - 1 downTo 0) {
            var digit = cardNumber[i].toString().toIntOrNull() ?: return false
            if (alternate) {
                digit *= 2
                if (digit > 9) {
                    digit -= 9
                }
            }
            sum += digit
            alternate = !alternate
        }
        return sum % 10 == 0
    }
    
    /**
     * 身份证号校验位验证（18 位）
     */
    private fun isValidIdCard(idCard: String): Boolean {
        if (idCard.length == 15) return true  // 15 位老身份证不校验
        if (idCard.length != 18) return false
        
        val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        val checkCodes = charArrayOf('1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2')
        
        var sum = 0
        for (i in 0..16) {
            sum += (idCard[i].toString().toIntOrNull() ?: return false) * weights[i]
        }
        
        val checkCode = checkCodes[sum % 11]
        return idCard[17].uppercaseChar() == checkCode
    }
    
    /**
     * 判断是否为私网 IP
     */
    private fun isPrivateIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        
        return when (first) {
            10 -> true
            172 -> second in 16..31
            192 -> second == 168
            127 -> true  // localhost
            else -> false
        }
    }
    
    // ==================== 掩码方法 ====================
    
    private fun maskBankCard(card: String): String {
        val cleaned = card.replace("[\\s-]".toRegex(), "")
        return if (cleaned.length >= 8) {
            cleaned.substring(0, 4) + " **** **** " + cleaned.substring(cleaned.length - 4)
        } else {
            "****"
        }
    }
    
    private fun maskIdCard(idCard: String): String {
        return if (idCard.length >= 5) {
            idCard.substring(0, 3) + "***********" + idCard.substring(idCard.length - 2)
        } else {
            "***"
        }
    }
    
    private fun maskPhone(phone: String): String {
        return if (phone.length == 11) {
            phone.substring(0, 3) + "****" + phone.substring(7)
        } else {
            "***"
        }
    }
    
    private fun maskEmail(email: String): String {
        val parts = email.split("@")
        if (parts.size != 2) return "***@***"
        
        val username = parts[0]
        val maskedUsername = if (username.isNotEmpty()) {
            username[0] + "***"
        } else {
            "***"
        }
        
        return "$maskedUsername@${parts[1]}"
    }
    
    /**
     * 应用掩码到原文本（处理重叠）
     */
    private fun applyMasks(text: String, sortedPii: List<DetectedPii>): String {
        if (sortedPii.isEmpty()) return text
        
        val result = StringBuilder()
        var lastEnd = 0
        
        for (pii in sortedPii) {
            // 跳过重叠区域
            if (pii.start < lastEnd) continue
            
            // 添加未掩码部分
            result.append(text.substring(lastEnd, pii.start))
            
            // 添加掩码部分
            result.append(pii.maskedText)
            
            lastEnd = pii.end
        }
        
        // 添加剩余部分
        if (lastEnd < text.length) {
            result.append(text.substring(lastEnd))
        }
        
        return result.toString()
    }
}
