package com.android.server.genton.perception

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.android.server.notification.NotificationManagerService
import com.genton.perception.notification.*
import com.genton.TrustLevel
import com.android.server.genton.acb.ACBPolicyEngine
import com.android.server.genton.audit.AuditLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A2.3 通知服务（IAgentNotificationService）
 *
 * 职责：
 * 1. 查询当前通知：getActiveNotifications()返回快照
 * 2. 订阅通知流：startNotificationStream()推送增量事件
 * 3. 三层鉴权：App白名单 + 能力令牌 + 会话确认
 * 4. PII脱敏：自动识别并掩码敏感信息
 * 5. 审计日志：记录所有查询和拒绝事件
 *
 * 协同：
 * - 与NotificationManagerService深度集成（通知拦截）
 * - 与NotificationAuthorizationManager协同鉴权
 * - 与NotificationInterceptor协同解析和脱敏
 * - 与AuditLogger记录审计
 */
class AgentNotificationService(
    private val context: Context,
    private val nms: NotificationManagerService,
    private val policyEngine: ACBPolicyEngine,
    private val auditLogger: AuditLogger
) {
    private val piiMasker = NotificationPiiMasker()
    private val interceptor = NotificationInterceptor(context, piiMasker)
    private val authManager = NotificationAuthorizationManager(policyEngine, auditLogger)
    
    private val snapshotIdGenerator = AtomicLong(0)
    
    // 通知流管理
    private val notificationStreams = ConcurrentHashMap<String, NotificationStream>()
    private val streamIdGenerator = AtomicLong(0)
    
    // 工作线程
    private val workerThread = HandlerThread("AgentNotificationService").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    /**
     * 查询当前活跃通知
     *
     * @param request 查询请求
     * @return 查询结果
     */
    fun getActiveNotifications(request: NotificationQueryRequest): QueryNotificationsResult {
        try {
            // 1. 能力令牌鉴权（第二层）
            val capabilityAuth = authManager.checkCapabilityToken(request)
            if (capabilityAuth is NotificationAuthorizationManager.AuthorizationResult.Denied) {
                return QueryNotificationsResult.denied(capabilityAuth.statusCode)
            }

            // 2. 获取系统通知列表
            val allNotifications = nms.getActiveNotifications(context.packageName)
            if (allNotifications.isEmpty()) {
                return QueryNotificationsResult.success(
                    NotificationSnapshot(
                        snapshotId = snapshotIdGenerator.incrementAndGet(),
                        timestampRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        notifications = emptyArray(),
                        trust = TrustLevel.APP_CONTENT
                    )
                )
            }

            // 3. 过滤和鉴权
            val filteredNotifications = allNotifications.mapNotNull { sbn ->
                // 应用过滤条件
                if (!matchesFilter(sbn, request)) {
                    return@mapNotNull null
                }

                // 时效性过滤
                val age = System.currentTimeMillis() - sbn.postTime
                if (age > request.maxAge) {
                    return@mapNotNull null
                }

                // 三层鉴权
                val authResult = authManager.authorize(
                    packageName = sbn.packageName,
                    category = sbn.notification.category,
                    request = request
                )

                when (authResult) {
                    is NotificationAuthorizationManager.AuthorizationResult.Allowed -> {
                        // 允许，解析并返回
                        interceptor.parseNotification(sbn, request.maskPii)
                    }
                    is NotificationAuthorizationManager.AuthorizationResult.RequiresConfirmation -> {
                        // 需要确认，返回占位节点（或跳过）
                        null
                    }
                    is NotificationAuthorizationManager.AuthorizationResult.Denied -> {
                        // 拒绝，跳过
                        null
                    }
                }
            }

            // 4. 构造快照
            val snapshot = NotificationSnapshot(
                snapshotId = snapshotIdGenerator.incrementAndGet(),
                timestampRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                notifications = filteredNotifications.toTypedArray(),
                trust = TrustLevel.APP_CONTENT
            )

            // 5. 审计日志
            auditLogger.logNotificationQuery(
                success = true,
                reason = request.reason,
                resultCount = snapshot.notifications.size,
                totalCount = allNotifications.size
            )

            return QueryNotificationsResult.success(snapshot)

        } catch (e: Exception) {
            auditLogger.logNotificationQuery(
                success = false,
                reason = request.reason,
                error = e.message
            )
            return QueryNotificationsResult.error("Internal error: ${e.message}")
        }
    }

    /**
     * 启动通知流
     *
     * @param config 流配置
     * @param listener 监听器
     * @return 启动结果
     */
    fun startNotificationStream(
        config: NotificationStreamConfig,
        listener: IAgentNotificationListener
    ): StartNotificationStreamResult {
        try {
            // 1. 能力令牌鉴权
            val request = NotificationQueryRequest(
                packageFilter = config.packageFilter,
                categoryFilter = config.categoryFilter,
                includeOngoing = config.includeOngoing,
                maskPii = config.maskPii,
                reason = "Notification stream"
            )
            
            val capabilityAuth = authManager.checkCapabilityToken(request)
            if (capabilityAuth is NotificationAuthorizationManager.AuthorizationResult.Denied) {
                return StartNotificationStreamResult.denied(capabilityAuth.statusCode)
            }

            // 2. 创建通知流
            val streamId = "noti_stream_${streamIdGenerator.incrementAndGet()}_${SystemClock.elapsedRealtime()}"
            val stream = NotificationStream(
                streamId = streamId,
                config = config,
                listener = listener,
                authManager = authManager,
                interceptor = interceptor
            )

            notificationStreams[streamId] = stream
            stream.start()

            auditLogger.logNotificationStreamStart(streamId = streamId)

            return StartNotificationStreamResult.success(
                NotificationStreamHandle(streamId)
            )

        } catch (e: Exception) {
            return StartNotificationStreamResult.error("Failed to start stream: ${e.message}")
        }
    }

    /**
     * 停止通知流
     *
     * @param handle 流句柄
     */
    fun stopNotificationStream(handle: NotificationStreamHandle) {
        val stream = notificationStreams.remove(handle.streamId)
        stream?.stop()
        auditLogger.logNotificationStreamStop(streamId = handle.streamId)
    }

    /**
     * 处理通知发布事件（由NMS调用）
     *
     * 此方法Hook到NotificationManagerService的通知发布流程
     */
    fun onNotificationPosted(sbn: StatusBarNotification) {
        workerHandler.post {
            // 推送到所有活跃流
            notificationStreams.values.forEach { stream ->
                stream.onNotificationPosted(sbn)
            }
        }
    }

    /**
     * 处理通知移除事件（由NMS调用）
     */
    fun onNotificationRemoved(key: String) {
        workerHandler.post {
            // 推送到所有活跃流
            notificationStreams.values.forEach { stream ->
                stream.onNotificationRemoved(key)
            }
        }
    }

    /**
     * 更新白名单配置
     */
    fun updateWhitelist(config: NotificationWhitelistConfig) {
        authManager.updateWhitelist(config)
    }

    /**
     * 关闭服务
     */
    fun shutdown() {
        notificationStreams.keys.toList().forEach { streamId ->
            notificationStreams.remove(streamId)?.stop()
        }
        workerThread.quitSafely()
    }

    // ==================== 私有方法 ====================

    /**
     * 检查通知是否匹配过滤条件
     */
    private fun matchesFilter(sbn: StatusBarNotification, request: NotificationQueryRequest): Boolean {
        // 包名过滤
        if (request.packageFilter != null && !request.packageFilter.contains(sbn.packageName)) {
            return false
        }

        // 分类过滤
        if (request.categoryFilter != null && !request.categoryFilter.contains(sbn.notification.category)) {
            return false
        }

        // 常驻通知过滤
        if (!request.includeOngoing && sbn.isOngoing) {
            return false
        }

        // 摘要通知过滤
        if (!request.includeSummary && (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)) {
            return false
        }

        return true
    }

    // ==================== 通知流 ====================

    /**
     * 通知流实例
     */
    private inner class NotificationStream(
        val streamId: String,
        val config: NotificationStreamConfig,
        val listener: IAgentNotificationListener,
        val authManager: NotificationAuthorizationManager,
        val interceptor: NotificationInterceptor
    ) {
        private var running = false

        fun start() {
            running = true
        }

        fun stop() {
            running = false
        }

        fun onNotificationPosted(sbn: StatusBarNotification) {
            if (!running) return

            // 应用过滤
            if (config.packageFilter != null && !config.packageFilter.contains(sbn.packageName)) {
                return
            }
            if (config.categoryFilter != null && !config.categoryFilter.contains(sbn.notification.category)) {
                return
            }
            if (!config.includeOngoing && sbn.isOngoing) {
                return
            }

            // 鉴权（仅App级白名单，流已通过能力令牌）
            val authResult = authManager.checkAppWhitelist(sbn.packageName, sbn.notification.category)
            if (authResult is NotificationAuthorizationManager.AuthorizationResult.Denied) {
                return
            }

            // 解析并推送
            try {
                val node = interceptor.parseNotification(sbn, config.maskPii)
                listener.onNotificationPosted(node)
            } catch (e: Exception) {
                listener.onError(
                    NotificationServiceStatus.STATUS_INTERNAL_ERROR,
                    "Failed to parse notification: ${e.message}"
                )
            }
        }

        fun onNotificationRemoved(key: String) {
            if (!running) return

            try {
                listener.onNotificationRemoved(key)
            } catch (e: Exception) {
                // Listener已死亡，静默处理
            }
        }
    }

    // ==================== 结果类型 ====================

    data class QueryNotificationsResult(
        val status: Int,
        val snapshot: NotificationSnapshot?,
        val errorMessage: String?
    ) {
        companion object {
            fun success(snapshot: NotificationSnapshot) = QueryNotificationsResult(
                NotificationServiceStatus.STATUS_OK,
                snapshot,
                null
            )

            fun denied(statusCode: Int) = QueryNotificationsResult(statusCode, null, null)

            fun error(message: String) = QueryNotificationsResult(
                NotificationServiceStatus.STATUS_INTERNAL_ERROR,
                null,
                message
            )
        }
    }

    data class StartNotificationStreamResult(
        val status: Int,
        val handle: NotificationStreamHandle?,
        val errorMessage: String?
    ) {
        companion object {
            fun success(handle: NotificationStreamHandle) = StartNotificationStreamResult(
                NotificationServiceStatus.STATUS_OK,
                handle,
                null
            )

            fun denied(statusCode: Int) = StartNotificationStreamResult(statusCode, null, null)

            fun error(message: String) = StartNotificationStreamResult(
                NotificationServiceStatus.STATUS_INTERNAL_ERROR,
                null,
                message
            )
        }
    }
}
