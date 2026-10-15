package com.android.server.genton.perception

import android.app.Notification
import com.genton.perception.notification.NotificationWhitelistConfig
import com.genton.perception.notification.NotificationQueryRequest
import com.android.server.genton.acb.ACBPolicyEngine
import com.android.server.genton.audit.AuditLogger

/**
 * A2.3 通知三层鉴权机制
 *
 * 三层防护：
 * 1. App级白名单：只允许白名单内应用的通知被捕获
 * 2. 能力级令牌：capture_notification能力令牌（ACB策略控制）
 * 3. 会话级确认：敏感分类（MESSAGE/EMAIL/验证码）需用户实时确认
 *
 * 设计原则：
 * - 默认拒绝（白名单为空时拒绝所有）
 * - 分类拒绝（CALL/SYSTEM_ALERT永久拒绝）
 * - 确认门槛（MESSAGE等需用户确认）
 * - 审计溯源（所有拒绝记录原因）
 *
 * 协同：
 * - 与ACBPolicyEngine的capture_notification能力令牌协同
 * - 与AuditLogger记录所有鉴权决策
 * - 与用户确认门（D2会话流）协同实时确认
 */
class NotificationAuthorizationManager(
    private val policyEngine: ACBPolicyEngine,
    private val auditLogger: AuditLogger
) {
    // 白名单配置（由ACB策略加载）
    private var whitelistConfig: NotificationWhitelistConfig = NotificationWhitelistConfig.default()

    /**
     * 鉴权结果
     */
    sealed class AuthorizationResult {
        object Allowed : AuthorizationResult()
        
        data class Denied(
            val reason: String,
            val statusCode: Int
        ) : AuthorizationResult()
        
        data class RequiresConfirmation(
            val reason: String,
            val confirmationType: String  // "MESSAGE" / "EMAIL" / "VERIFICATION_CODE"
        ) : AuthorizationResult()
    }

    /**
     * 第一层：App级白名单鉴权
     *
     * @param packageName 应用包名
     * @param category 通知分类
     * @return 鉴权结果
     */
    fun checkAppWhitelist(packageName: String, category: String?): AuthorizationResult {
        // 1. 检查包名白名单
        if (!whitelistConfig.allowedPackages.contains(packageName)) {
            auditLogger.logNotificationAuth(
                success = false,
                packageName = packageName,
                reason = "Package not in whitelist"
            )
            return AuthorizationResult.Denied(
                reason = "Package $packageName not whitelisted",
                statusCode = 2  // STATUS_APP_NOT_WHITELISTED
            )
        }

        // 2. 检查分类拒绝列表
        if (category != null && whitelistConfig.deniedCategories.contains(category)) {
            auditLogger.logNotificationAuth(
                success = false,
                packageName = packageName,
                reason = "Category $category is denied"
            )
            return AuthorizationResult.Denied(
                reason = "Category $category is permanently denied",
                statusCode = 1  // STATUS_DENIED
            )
        }

        // 3. 检查是否需要用户确认
        if (category != null && whitelistConfig.requireConfirmationForCategories.contains(category)) {
            return AuthorizationResult.RequiresConfirmation(
                reason = "Category $category requires user confirmation",
                confirmationType = category
            )
        }

        return AuthorizationResult.Allowed
    }

    /**
     * 第二层：能力令牌鉴权
     *
     * @param request 查询请求
     * @return 鉴权结果
     */
    fun checkCapabilityToken(request: NotificationQueryRequest): AuthorizationResult {
        val capabilityCheck = policyEngine.checkCapability(
            capability = "capture_notification",
            reason = request.reason
        )

        if (!capabilityCheck.allowed) {
            auditLogger.logNotificationAuth(
                success = false,
                packageName = null,
                reason = "ACB capability denied: ${capabilityCheck.denyReason}"
            )
            return AuthorizationResult.Denied(
                reason = "ACB capability denied: ${capabilityCheck.denyReason}",
                statusCode = 1  // STATUS_DENIED
            )
        }

        return AuthorizationResult.Allowed
    }

    /**
     * 第三层：会话级确认鉴权
     *
     * 需要与D2会话流的确认门协同，此处仅检查租约状态
     *
     * @param confirmationType 确认类型
     * @return 鉴权结果
     */
    fun checkSessionConfirmation(confirmationType: String): AuthorizationResult {
        // 检查当前会话是否有活跃的确认租约
        val hasActiveLease = policyEngine.hasActiveConfirmationLease(
            confirmationType = "notification_$confirmationType"
        )

        if (!hasActiveLease) {
            auditLogger.logNotificationAuth(
                success = false,
                packageName = null,
                reason = "No active confirmation lease for $confirmationType"
            )
            return AuthorizationResult.Denied(
                reason = "User confirmation required for $confirmationType",
                statusCode = 3  // STATUS_USER_CONFIRMATION_REQUIRED
            )
        }

        return AuthorizationResult.Allowed
    }

    /**
     * 完整鉴权流程
     *
     * 依次执行三层鉴权，任一层拒绝则整体拒绝
     *
     * @param packageName 应用包名
     * @param category 通知分类
     * @param request 查询请求
     * @return 鉴权结果
     */
    fun authorize(
        packageName: String,
        category: String?,
        request: NotificationQueryRequest
    ): AuthorizationResult {
        // 第一层：App级白名单
        val appResult = checkAppWhitelist(packageName, category)
        if (appResult is AuthorizationResult.Denied) {
            return appResult
        }

        // 第二层：能力令牌
        val capabilityResult = checkCapabilityToken(request)
        if (capabilityResult is AuthorizationResult.Denied) {
            return capabilityResult
        }

        // 第三层：会话级确认（如果需要）
        if (appResult is AuthorizationResult.RequiresConfirmation) {
            val confirmationResult = checkSessionConfirmation(appResult.confirmationType)
            if (confirmationResult is AuthorizationResult.Denied) {
                return confirmationResult
            }
        }

        // 全部通过
        auditLogger.logNotificationAuth(
            success = true,
            packageName = packageName,
            reason = "All authorization layers passed"
        )
        return AuthorizationResult.Allowed
    }

    /**
     * 批量鉴权（用于查询多个包名的通知）
     *
     * @param packageNames 包名列表
     * @param request 查询请求
     * @return 允许的包名集合
     */
    fun authorizePackages(
        packageNames: List<String>,
        request: NotificationQueryRequest
    ): Set<String> {
        // 第二层能力令牌先行检查（避免重复）
        val capabilityResult = checkCapabilityToken(request)
        if (capabilityResult is AuthorizationResult.Denied) {
            return emptySet()
        }

        // 逐个检查包名
        return packageNames.filter { packageName ->
            val appResult = checkAppWhitelist(packageName, null)
            appResult is AuthorizationResult.Allowed || appResult is AuthorizationResult.RequiresConfirmation
        }.toSet()
    }

    /**
     * 更新白名单配置
     *
     * 由ACBPolicyEngine在策略变更时调用
     */
    fun updateWhitelist(config: NotificationWhitelistConfig) {
        this.whitelistConfig = config
        auditLogger.logNotificationWhitelistUpdate(
            allowedPackagesCount = config.allowedPackages.size,
            deniedCategoriesCount = config.deniedCategories.size
        )
    }

    /**
     * 获取当前白名单配置（审计查询）
     */
    fun getWhitelistConfig(): NotificationWhitelistConfig {
        return whitelistConfig
    }

    /**
     * 检查分类是否需要确认
     */
    fun requiresConfirmation(category: String?): Boolean {
        if (category == null) return false
        return whitelistConfig.requireConfirmationForCategories.contains(category)
    }

    /**
     * 检查分类是否永久拒绝
     */
    fun isPermanentlyDenied(category: String?): Boolean {
        if (category == null) return false
        return whitelistConfig.deniedCategories.contains(category)
    }
}
