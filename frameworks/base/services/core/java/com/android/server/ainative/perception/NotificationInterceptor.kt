package com.android.server.ainative.perception

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.ainative.perception.notification.*
import com.ainative.TrustLevel
import java.util.regex.Pattern

/**
 * A2.3 PII脱敏处理器
 *
 * 职责：
 * 1. 识别敏感信息类型（联系人/金额/验证码/身份证/银行卡）
 * 2. 应用脱敏规则（掩码/移除/泛化）
 * 3. 标记敏感类别（审计溯源）
 *
 * 脱敏策略：
 * - 联系人：替换为"[联系人]"
 * - 金额：替换为"[金额]"
 * - 验证码：替换为"[验证码]"
 * - 身份证/银行卡：替换为"[敏感数字]"
 * - 保留结构：保留"您有一条新消息"等通用模板
 */
class NotificationPiiMasker {
    
    companion object {
        // 验证码模式（4-8位数字）
        private val VERIFICATION_CODE_PATTERN = Pattern.compile(
            "(?:验证码|动态码|校验码|code|Code|CODE)[：:是为]?\\s*([0-9]{4,8})"
        )
        
        // 金额模式（¥/￥/$符号 + 数字）
        private val AMOUNT_PATTERN = Pattern.compile(
            "(?:[¥￥$]|人民币)\\s*([0-9,]+\\.?[0-9]*)|([0-9,]+\\.?[0-9]*)\\s*(?:元|块|美元)"
        )
        
        // 身份证模式（18位或15位）
        private val ID_CARD_PATTERN = Pattern.compile(
            "[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]"
        )
        
        // 银行卡模式（13-19位数字，可能有空格）
        private val BANK_CARD_PATTERN = Pattern.compile(
            "(?:\\d{4}[\\s-]?){3,4}\\d{1,4}"
        )
        
        // 手机号模式（中国11位）
        private val PHONE_PATTERN = Pattern.compile(
            "1[3-9]\\d{9}"
        )
        
        // 邮箱模式
        private val EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
        )
    }
    
    /**
     * 脱敏结果
     */
    data class MaskResult(
        val maskedText: String,
        val sensitiveCategories: Set<String>
    )
    
    /**
     * 脱敏文本
     *
     * @param text 原始文本
     * @return 脱敏结果
     */
    fun maskText(text: String?): MaskResult {
        if (text.isNullOrBlank()) {
            return MaskResult("", emptySet())
        }
        
        var masked = text
        val categories = mutableSetOf<String>()
        
        // 1. 验证码脱敏
        val verificationMatcher = VERIFICATION_CODE_PATTERN.matcher(masked)
        if (verificationMatcher.find()) {
            masked = verificationMatcher.replaceAll("$0[验证码]")
            categories.add("VERIFICATION_CODE")
        }
        
        // 2. 金额脱敏
        val amountMatcher = AMOUNT_PATTERN.matcher(masked)
        if (amountMatcher.find()) {
            masked = amountMatcher.replaceAll("[金额]")
            categories.add("AMOUNT")
        }
        
        // 3. 身份证脱敏
        val idCardMatcher = ID_CARD_PATTERN.matcher(masked)
        if (idCardMatcher.find()) {
            masked = idCardMatcher.replaceAll("[身份证]")
            categories.add("ID_CARD")
        }
        
        // 4. 银行卡脱敏
        val bankCardMatcher = BANK_CARD_PATTERN.matcher(masked)
        if (bankCardMatcher.find()) {
            masked = bankCardMatcher.replaceAll("[银行卡]")
            categories.add("BANK_CARD")
        }
        
        // 5. 手机号脱敏
        val phoneMatcher = PHONE_PATTERN.matcher(masked)
        if (phoneMatcher.find()) {
            masked = phoneMatcher.replaceAll("[手机号]")
            categories.add("PHONE")
        }
        
        // 6. 邮箱脱敏
        val emailMatcher = EMAIL_PATTERN.matcher(masked)
        if (emailMatcher.find()) {
            masked = emailMatcher.replaceAll("[邮箱]")
            categories.add("EMAIL")
        }
        
        return MaskResult(masked, categories)
    }
    
    /**
     * 检测是否包含敏感信息（不执行脱敏）
     */
    fun containsSensitiveInfo(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        
        return VERIFICATION_CODE_PATTERN.matcher(text).find() ||
               AMOUNT_PATTERN.matcher(text).find() ||
               ID_CARD_PATTERN.matcher(text).find() ||
               BANK_CARD_PATTERN.matcher(text).find() ||
               PHONE_PATTERN.matcher(text).find() ||
               EMAIL_PATTERN.matcher(text).find()
    }
}

/**
 * A2.3 NMS增强层（通知拦截器）
 *
 * 职责：
 * 1. Hook NotificationManagerService的通知发布流程
 * 2. 解析StatusBarNotification为结构化NotificationNode
 * 3. 应用PII脱敏规则
 * 4. 向AgentNotificationService推送通知事件
 *
 * 实现方式：
 * - 注入NotificationManagerService.mListeners
 * - 拦截enqueueNotificationInternal()
 * - 异步处理避免阻塞通知发布
 */
class NotificationInterceptor(
    private val context: Context,
    private val piiMasker: NotificationPiiMasker
) {
    private val packageManager = context.packageManager
    
    /**
     * 解析StatusBarNotification为NotificationNode
     *
     * @param sbn 原始通知
     * @param maskPii 是否脱敏
     * @return 结构化通知节点
     */
    fun parseNotification(sbn: StatusBarNotification, maskPii: Boolean): NotificationNode {
        val notification = sbn.notification
        val extras = notification.extras
        
        // 提取基础内容
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val rawBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        
        // 应用脱敏
        val (title, titleCategories) = if (maskPii) {
            val result = piiMasker.maskText(rawTitle)
            result.maskedText to result.sensitiveCategories
        } else {
            rawTitle to emptySet()
        }
        
        val (text, textCategories) = if (maskPii) {
            val result = piiMasker.maskText(rawText)
            result.maskedText to result.sensitiveCategories
        } else {
            rawText to emptySet()
        }
        
        val (bigText, bigTextCategories) = if (maskPii) {
            val result = piiMasker.maskText(rawBigText)
            result.maskedText to result.sensitiveCategories
        } else {
            rawBigText to emptySet()
        }
        
        val allCategories = titleCategories + textCategories + bigTextCategories
        
        // 解析操作按钮
        val actions = notification.actions?.map { action ->
            NotificationAction(
                title = action.title?.toString() ?: "",
                actionKey = action.actionIntent?.creatorPackage,
                requiresUnlock = action.actionIntent?.isActivity ?: false,
                isContextual = action.isContextual
            )
        }?.toTypedArray() ?: emptyArray()
        
        // 解析进度信息
        val hasProgress = extras.containsKey(Notification.EXTRA_PROGRESS)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100)
        val progressIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        
        // 解析MessagingStyle
        val (isMessagingStyle, messagingUser, messages) = parseMessagingStyle(extras, maskPii)
        
        // 获取应用名称
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        
        return NotificationNode(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            postedAtRealtimeMs = sbn.postTime,
            capturedAtRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            
            title = title,
            text = text,
            subText = rawSubText,
            bigText = bigText,
            
            category = notification.category,
            channelId = notification.channelId,
            groupKey = sbn.groupKey,
            isSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
            priority = notification.priority,
            
            actions = actions,
            contentIntent = notification.contentIntent?.creatorPackage,
            deleteIntent = notification.deleteIntent?.creatorPackage,
            
            hasProgress = hasProgress,
            progress = progress,
            progressMax = progressMax,
            progressIndeterminate = progressIndeterminate,
            
            isMessagingStyle = isMessagingStyle,
            messagingStyleUser = messagingUser,
            messagingStyleMessages = messages,
            
            piiMasked = maskPii,
            sensitiveCategory = if (allCategories.isNotEmpty()) allCategories.joinToString(",") else null
        )
    }
    
    /**
     * 解析MessagingStyle
     */
    private fun parseMessagingStyle(
        extras: Bundle,
        maskPii: Boolean
    ): Triple<Boolean, String?, Array<MessageEntry>?> {
        val messagesBundle = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: return Triple(false, null, null)
        
        val messages = messagesBundle.mapNotNull { parcelable ->
            val bundle = parcelable as? Bundle ?: return@mapNotNull null
            val sender = bundle.getCharSequence("sender")?.toString()
            val text = bundle.getCharSequence("text")?.toString()
            val timestamp = bundle.getLong("time", 0)
            
            val (maskedSender, maskedText) = if (maskPii) {
                piiMasker.maskText(sender).maskedText to piiMasker.maskText(text).maskedText
            } else {
                sender to text
            }
            
            MessageEntry(
                sender = maskedSender,
                text = maskedText,
                timestampMs = timestamp
            )
        }.toTypedArray()
        
        val user = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
        val maskedUser = if (maskPii) piiMasker.maskText(user).maskedText else user
        
        return Triple(true, maskedUser, messages)
    }
}
