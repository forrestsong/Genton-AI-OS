package com.genton.perception.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.genton.TrustLevel

/**
 * A2.3 通知服务数据模型
 *
 * 设计原则：
 * 1. 脱敏处理：maskPii=true时移除PII（联系人/金额/验证码）
 * 2. 三层鉴权：App级（白名单）+ 能力级（capture_notification令牌）+ 会话级（用户确认）
 * 3. 时效性：capturedAtRealtimeNanos记录捕获时间，postedAtRealtimeMs记录发布时间
 * 4. 富文本：保留通知的结构化信息（actions/progress/bigText）
 * 5. 信任标记：TrustLevel.APP_CONTENT，后续可升级为NOTIFICATION_CONTENT
 */

/**
 * 通知快照
 *
 * 封装某时刻的所有可见通知列表
 */
data class NotificationSnapshot(
    val snapshotId: Long,
    val timestampRealtimeNanos: Long,           // 捕获时间戳（纳秒）
    val notifications: Array<NotificationNode>, // 通知节点列表
    val trust: TrustLevel                       // 信任级别：APP_CONTENT
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NotificationSnapshot

        if (snapshotId != other.snapshotId) return false
        if (timestampRealtimeNanos != other.timestampRealtimeNanos) return false
        if (!notifications.contentEquals(other.notifications)) return false
        if (trust != other.trust) return false

        return true
    }

    override fun hashCode(): Int {
        var result = snapshotId.hashCode()
        result = 31 * result + timestampRealtimeNanos.hashCode()
        result = 31 * result + notifications.contentHashCode()
        result = 31 * result + trust.hashCode()
        return result
    }
}

/**
 * 通知节点
 *
 * 封装单条通知的结构化信息
 */
data class NotificationNode(
    val key: String,                            // 唯一标识（pkg|id|tag|userId）
    val packageName: String,                    // 发布应用包名
    val appName: String?,                       // 应用名称
    val postedAtRealtimeMs: Long,               // 发布时间（毫秒）
    val capturedAtRealtimeNanos: Long,          // 捕获时间（纳秒）
    
    // 基础内容
    val title: String?,                         // 标题（已脱敏）
    val text: String?,                          // 正文（已脱敏）
    val subText: String?,                       // 子文本
    val bigText: String?,                       // 大文本（展开内容，已脱敏）
    
    // 元数据
    val category: String?,                      // 分类（CATEGORY_MESSAGE/EMAIL/CALL等）
    val channelId: String?,                     // 通知渠道ID
    val groupKey: String?,                      // 分组键
    val isSummary: Boolean,                     // 是否为摘要通知
    val isOngoing: Boolean,                     // 是否常驻
    val isClearable: Boolean,                   // 是否可清除
    val priority: Int,                          // 优先级（-2到2）
    
    // 交互能力
    val actions: Array<NotificationAction>,     // 操作按钮
    val contentIntent: String?,                 // 点击意图（PendingIntent描述）
    val deleteIntent: String?,                  // 删除意图
    
    // 进度信息（下载/上传场景）
    val hasProgress: Boolean,
    val progress: Int,                          // 进度值（0-100）
    val progressMax: Int,                       // 进度最大值
    val progressIndeterminate: Boolean,         // 是否不确定进度
    
    // 消息类型扩展（MessagingStyle）
    val isMessagingStyle: Boolean,
    val messagingStyleUser: String?,            // 当前用户名（已脱敏）
    val messagingStyleMessages: Array<MessageEntry>?,  // 消息列表（已脱敏）
    
    // 隐私标记
    val piiMasked: Boolean,                     // 是否已脱敏
    val sensitiveCategory: String?              // 敏感类别（CONTACT/AMOUNT/VERIFICATION_CODE）
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NotificationNode

        if (key != other.key) return false
        if (packageName != other.packageName) return false
        if (capturedAtRealtimeNanos != other.capturedAtRealtimeNanos) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + packageName.hashCode()
        result = 31 * result + capturedAtRealtimeNanos.hashCode()
        return result
    }
}

/**
 * 通知操作
 */
data class NotificationAction(
    val title: String,                          // 操作标题（如"回复"/"忽略"）
    val actionKey: String?,                     // 操作键（用于执行）
    val requiresUnlock: Boolean,                // 是否需要解锁
    val isContextual: Boolean                   // 是否为上下文操作
)

/**
 * 消息条目（MessagingStyle）
 */
data class MessageEntry(
    val sender: String?,                        // 发送者（已脱敏）
    val text: String?,                          // 消息文本（已脱敏）
    val timestampMs: Long                       // 时间戳
)

/**
 * 通知查询请求
 */
data class NotificationQueryRequest(
    val packageFilter: Array<String>?,          // 包名过滤（null=全部）
    val categoryFilter: Array<String>?,         // 分类过滤
    val includeOngoing: Boolean = true,         // 包含常驻通知
    val includeSummary: Boolean = false,        // 包含摘要通知
    val maxAge: Long = 60_000,                  // 最大年龄（毫秒，默认1分钟内）
    val maskPii: Boolean = true,                // 是否脱敏
    val reason: String                          // 查询原因（审计）
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NotificationQueryRequest

        if (packageFilter != null) {
            if (other.packageFilter == null) return false
            if (!packageFilter.contentEquals(other.packageFilter)) return false
        } else if (other.packageFilter != null) return false

        if (categoryFilter != null) {
            if (other.categoryFilter == null) return false
            if (!categoryFilter.contentEquals(other.categoryFilter)) return false
        } else if (other.categoryFilter != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packageFilter?.contentHashCode() ?: 0
        result = 31 * result + (categoryFilter?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 通知服务状态码
 */
object NotificationServiceStatus {
    const val STATUS_OK = 0
    const val STATUS_DENIED = 1                 // ACB能力令牌拒绝
    const val STATUS_APP_NOT_WHITELISTED = 2    // 应用不在白名单
    const val STATUS_USER_CONFIRMATION_REQUIRED = 3  // 需要用户确认
    const val STATUS_NO_ACTIVE_LEASE = 4        // 无活跃租约
    const val STATUS_INTERNAL_ERROR = 5
}

/**
 * 通知白名单配置
 *
 * 三层鉴权的第一层：App级白名单
 */
data class NotificationWhitelistConfig(
    val allowedPackages: Set<String>,           // 允许捕获通知的应用包名
    val deniedCategories: Set<String>,          // 拒绝的分类（如CATEGORY_CALL避免劫持）
    val requireConfirmationForCategories: Set<String>  // 需要用户确认的分类（MESSAGE/EMAIL）
) {
    companion object {
        /**
         * 默认配置（保守策略）
         */
        fun default() = NotificationWhitelistConfig(
            allowedPackages = setOf(
                // 默认为空，需要ACB策略显式配置
            ),
            deniedCategories = setOf(
                Notification.CATEGORY_CALL,     // 拒绝通话通知（防劫持）
                "android.permission.SYSTEM_ALERT_WINDOW"  // 拒绝系统警告
            ),
            requireConfirmationForCategories = setOf(
                Notification.CATEGORY_MESSAGE,  // 消息需确认
                Notification.CATEGORY_EMAIL,    // 邮件需确认
                "verification_code"             // 验证码需确认
            )
        )
    }
}

/**
 * 通知流句柄
 */
data class NotificationStreamHandle(
    val streamId: String
)

/**
 * 通知流配置
 */
data class NotificationStreamConfig(
    val packageFilter: Array<String>?,
    val categoryFilter: Array<String>?,
    val includeOngoing: Boolean = true,
    val maskPii: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NotificationStreamConfig

        if (packageFilter != null) {
            if (other.packageFilter == null) return false
            if (!packageFilter.contentEquals(other.packageFilter)) return false
        } else if (other.packageFilter != null) return false

        if (categoryFilter != null) {
            if (other.categoryFilter == null) return false
            if (!categoryFilter.contentEquals(other.categoryFilter)) return false
        } else if (other.categoryFilter != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packageFilter?.contentHashCode() ?: 0
        result = 31 * result + (categoryFilter?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 通知监听器回调
 */
interface IAgentNotificationListener {
    fun onNotificationPosted(notification: NotificationNode)
    fun onNotificationRemoved(key: String)
    fun onError(status: Int, message: String?)
}
