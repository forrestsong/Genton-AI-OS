package com.android.server.genton.action.executor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.server.am.ActivityManagerService
import com.genton.action.ActionModels.*
import com.android.server.genton.audit.AuditLogger

/**
 * A3.3 Activity执行器
 *
 * 职责：
 * 1. 定向启动Activity（包名+类名精确定位）
 * 2. 非导出Activity白名单校验（防绕过权限检查）
 * 3. AMS定向启动（绕过Launcher选择器）
 * 4. 启动失败检测与降级
 *
 * 支持动作：
 * - activity.launch：启动指定Activity
 * - activity.launch_with_data：携带Intent extras启动
 * - activity.bring_to_front：将已有任务前置
 *
 * 安全机制：
 * - 非导出Activity需要白名单授权
 * - 禁止启动系统敏感Activity（Settings密码页等）
 * - 审计所有启动操作
 */
class ActivityExecutor(
    private val context: Context,
    private val ams: ActivityManagerService,
    private val auditLogger: AuditLogger
) : ActionExecutor {

    companion object {
        // 非导出Activity白名单（需要ACB策略显式配置）
        private val NON_EXPORTED_WHITELIST = setOf(
            // 示例：常见应用的深层页面
            "com.android.settings/.wifi.WifiSettings",
            "com.android.settings/.bluetooth.BluetoothSettings"
            // 更多由策略配置动态加载
        )

        // 禁止启动的敏感Activity（永久黑名单）
        private val SENSITIVE_BLACKLIST = setOf(
            "com.android.settings/.password.ChooseLockPassword",
            "com.android.settings/.password.ConfirmLockPassword",
            "com.android.settings/.PrivacySettings",
            "com.android.systemui/.keyguard.KeyguardHostView"
        )
    }

    // 动态白名单（由ACB策略加载）
    private val dynamicWhitelist = mutableSetOf<String>()

    override fun execute(request: ActionRequest): ActionExecutionResult {
        val actionName = request.actionName
        val arguments = request.arguments

        return when (actionName) {
            "activity.launch" -> executeLaunch(request, arguments)
            "activity.launch_with_data" -> executeLaunchWithData(request, arguments)
            "activity.bring_to_front" -> executeBringToFront(request, arguments)
            else -> ActionExecutionResult.failed(
                "Unknown activity action: $actionName",
                errorCode = "UNSUPPORTED_ACTION"
            )
        }
    }

    /**
     * 执行启动Activity
     */
    private fun executeLaunch(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        val packageName = args["package_name"] as? String
            ?: return ActionExecutionResult.failed("Missing package_name")

        val activityName = args["activity_name"] as? String
            ?: return ActionExecutionResult.failed("Missing activity_name")

        val fullName = "$packageName/$activityName"

        // 1. 黑名单检查
        if (SENSITIVE_BLACKLIST.contains(fullName)) {
            auditLogger.logActionExecution(
                success = false,
                actionName = request.actionName,
                denyReason = "Activity in sensitive blacklist"
            )
            return ActionExecutionResult.failed(
                "Activity $fullName is in sensitive blacklist",
                errorCode = "BLACKLISTED"
            )
        }

        // 2. 检查Activity是否存在
        val component = ComponentName(packageName, activityName)
        val activityInfo = try {
            context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            return ActionExecutionResult.failed(
                "Activity not found: $fullName",
                errorCode = "ACTIVITY_NOT_FOUND"
            )
        }

        // 3. 非导出Activity白名单检查
        if (!activityInfo.exported) {
            if (!isWhitelisted(fullName)) {
                auditLogger.logActionExecution(
                    success = false,
                    actionName = request.actionName,
                    denyReason = "Non-exported activity not whitelisted"
                )
                return ActionExecutionResult.failed(
                    "Non-exported activity $fullName requires whitelist authorization",
                    errorCode = "NOT_WHITELISTED"
                )
            }
        }

        // 4. 构造Intent
        val intent = Intent().apply {
            setComponent(component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // 5. 启动Activity
        return try {
            context.startActivityAsUser(intent, UserHandle.CURRENT)

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = fullName
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "launched_activity" to fullName,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            auditLogger.logActionExecution(
                success = false,
                actionName = request.actionName,
                denyReason = "Launch failed: ${e.message}"
            )
            ActionExecutionResult.failed(
                "Failed to launch activity: ${e.message}",
                errorCode = "LAUNCH_FAILED"
            )
        }
    }

    /**
     * 执行携带数据启动Activity
     */
    private fun executeLaunchWithData(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        val packageName = args["package_name"] as? String
            ?: return ActionExecutionResult.failed("Missing package_name")

        val activityName = args["activity_name"] as? String
            ?: return ActionExecutionResult.failed("Missing activity_name")

        val intentData = args["intent_data"] as? Map<String, Any> ?: emptyMap()

        val fullName = "$packageName/$activityName"

        // 黑名单和白名单检查（同executeLaunch）
        if (SENSITIVE_BLACKLIST.contains(fullName)) {
            return ActionExecutionResult.failed(
                "Activity $fullName is in sensitive blacklist",
                errorCode = "BLACKLISTED"
            )
        }

        val component = ComponentName(packageName, activityName)
        val activityInfo = try {
            context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            return ActionExecutionResult.failed(
                "Activity not found: $fullName",
                errorCode = "ACTIVITY_NOT_FOUND"
            )
        }

        if (!activityInfo.exported && !isWhitelisted(fullName)) {
            return ActionExecutionResult.failed(
                "Non-exported activity $fullName requires whitelist authorization",
                errorCode = "NOT_WHITELISTED"
            )
        }

        // 构造Intent并填充extras
        val intent = Intent().apply {
            setComponent(component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

            // 填充Intent extras（仅支持基础类型）
            intentData.forEach { (key, value) ->
                when (value) {
                    is String -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                    is Double -> putExtra(key, value)
                    is Float -> putExtra(key, value)
                    // 复杂类型需要序列化，暂不支持
                }
            }
        }

        return try {
            context.startActivityAsUser(intent, UserHandle.CURRENT)

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = fullName
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "launched_activity" to fullName,
                    "intent_extras_count" to intentData.size,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to launch activity with data: ${e.message}",
                errorCode = "LAUNCH_FAILED"
            )
        }
    }

    /**
     * 执行前置任务
     */
    private fun executeBringToFront(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        val packageName = args["package_name"] as? String
            ?: return ActionExecutionResult.failed("Missing package_name")

        // 将指定应用的任务前置
        return try {
            ams.moveTaskToFront(packageName)

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = packageName
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "brought_to_front" to packageName,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to bring task to front: ${e.message}",
                errorCode = "BRING_TO_FRONT_FAILED"
            )
        }
    }

    /**
     * 检查Activity是否在白名单中
     */
    private fun isWhitelisted(fullActivityName: String): Boolean {
        return NON_EXPORTED_WHITELIST.contains(fullActivityName) ||
               dynamicWhitelist.contains(fullActivityName)
    }

    /**
     * 更新动态白名单（由ACB策略调用）
     */
    fun updateWhitelist(newWhitelist: Set<String>) {
        dynamicWhitelist.clear()
        dynamicWhitelist.addAll(newWhitelist)
        auditLogger.logWhitelistUpdate(
            type = "activity",
            count = dynamicWhitelist.size
        )
    }

    /**
     * 获取当前白名单（审计查询）
     */
    fun getWhitelist(): Set<String> {
        return NON_EXPORTED_WHITELIST + dynamicWhitelist
    }

    override fun cancel(actionRequestId: String): Boolean {
        // Activity启动是瞬时操作，无法取消
        return false
    }

    override fun describeCapabilities(): String {
        return """
            ActivityExecutor: Launch Android Activities
            Supported actions:
            - activity.launch: Launch activity by package and class name
            - activity.launch_with_data: Launch with Intent extras
            - activity.bring_to_front: Bring existing task to front
            
            Security:
            - Non-exported activities require whitelist authorization
            - Sensitive activities (password, privacy) are blacklisted
            - All launches are audited
        """.trimIndent()
    }
}
