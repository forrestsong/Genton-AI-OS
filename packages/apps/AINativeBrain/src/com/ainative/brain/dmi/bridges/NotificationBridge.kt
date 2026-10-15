package com.ainative.brain.dmi.bridges

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ainative.brain.dmi.DmiBridge
import com.ainative.brain.dmi.DmiExecutionCallback
import com.ainative.brain.dmi.SystemCapabilityBridge
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通知能力桥（F3.2 首批 Skill 资产依赖）
 *
 * DMI: dmi.notification.notify
 * 功能：发送系统通知
 * 参数：
 *   - title: String (必填) - 通知标题
 *   - body: String (必填) - 通知正文
 *   - priority: String (可选，默认 normal) - high/normal/low
 */
class NotificationBridge(private val context: Context) : SystemCapabilityBridge {

    companion object {
        private const val TAG = "NotificationBridge"
        private const val CHANNEL_ID = "ainative_agent"
        private const val CHANNEL_NAME = "Agent 提醒"
        private const val MAX_TITLE_LENGTH = 128
        private const val MAX_BODY_LENGTH = 1024

        private val notificationIdGenerator = AtomicInteger(1000)
    }

    override fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        Log.i(TAG, "Executing notification notify")

        // 1. 验证参数
        val validationError = validateParameters(parameters)
        if (validationError != null) {
            callback.onFailure(DmiBridge.RESULT_INVALID_PARAMETERS, validationError)
            return
        }

        // 2. 检查权限
        val permissionError = checkPermissions()
        if (permissionError != null) {
            callback.onFailure(DmiBridge.RESULT_PERMISSION_DENIED, permissionError)
            return
        }

        try {
            // 3. 提取参数
            val title = (parameters["title"] as String).take(MAX_TITLE_LENGTH)
            val body = (parameters["body"] as String).take(MAX_BODY_LENGTH)
            val priority = (parameters["priority"] as? String) ?: "normal"

            // 4. 构建并发送通知
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(manager)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(toNotificationPriority(priority))
                .setAutoCancel(true)
                .build()

            val notificationId = notificationIdGenerator.incrementAndGet()
            manager.notify(notificationId, notification)

            // 5. 返回结果
            val result = JSONObject().apply {
                put("success", true)
                put("notificationId", notificationId)
                put("title", title)
            }
            callback.onSuccess(result.toString())

            Log.i(TAG, "Notification sent: id=$notificationId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification", e)
            callback.onFailure(
                DmiBridge.RESULT_EXECUTION_FAILED,
                "Failed to send notification: ${e.message}"
            )
        }
    }

    /**
     * 创建通知渠道（Android 8.0+ 必需）
     */
    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun toNotificationPriority(priority: String): Int {
        return when (priority.lowercase()) {
            "high" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
    }

    override fun validateParameters(parameters: Map<String, Any>): String? {
        val title = parameters["title"]
        if (title !is String || title.isEmpty()) {
            return "Invalid parameter: title must be a non-empty string"
        }

        val body = parameters["body"]
        if (body !is String || body.isEmpty()) {
            return "Invalid parameter: body must be a non-empty string"
        }

        val priority = parameters["priority"]
        if (priority != null && priority !is String) {
            return "Invalid parameter: priority must be a string"
        }

        return null
    }

    override fun checkPermissions(): String? {
        // Android 13+ 需要 POST_NOTIFICATIONS 运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                return "Missing permission: POST_NOTIFICATIONS"
            }
        }
        return null
    }
}
