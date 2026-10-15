package com.genton.brain.dmi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * Legacy App 桥接器
 * 
 * 职责：
 * 1. 通过 Intent 调用第三方应用
 * 2. 构建标准化的 Intent 参数
 * 3. 权限检查与结果回调
 * 4. 异常处理与降级
 * 
 * F2.2 DMI 桥方案 - Legacy App 桥接层
 */
class LegacyAppBridge(private val context: Context) {
    
    companion object {
        private const val TAG = "LegacyAppBridge"
        
        // Intent 动作映射
        private val INTENT_ACTION_MAP = mapOf(
            "dmi.app.browser.open" to Intent.ACTION_VIEW,
            "dmi.app.email.send" to Intent.ACTION_SENDTO,
            "dmi.app.share.text" to Intent.ACTION_SEND,
            "dmi.app.camera.capture" to "android.media.action.IMAGE_CAPTURE",
            "dmi.app.maps.navigate" to Intent.ACTION_VIEW
        )
    }
    
    /**
     * 执行 Legacy App 调用
     */
    fun execute(
        toolName: String,
        parameters: Map<String, Any>,
        callback: DmiExecutionCallback
    ) {
        Log.i(TAG, "Executing legacy app: $toolName")
        
        try {
            // 1. 根据工具名称构建 Intent
            val intent = buildIntent(toolName, parameters)
            
            if (intent == null) {
                callback.onFailure(
                    DmiBridge.RESULT_NOT_SUPPORTED,
                    "Unsupported legacy app tool: $toolName"
                )
                return
            }
            
            // 2. 验证 Intent 可解析（检查是否有应用可处理）
            if (!isIntentResolvable(intent)) {
                callback.onFailure(
                    DmiBridge.RESULT_EXECUTION_FAILED,
                    "No application found to handle this intent"
                )
                return
            }
            
            // 3. 启动 Intent
            context.startActivity(intent)
            
            // 4. 返回成功结果
            val result = JSONObject().apply {
                put("success", true)
                put("toolName", toolName)
                put("action", intent.action)
            }
            
            callback.onSuccess(result.toString())
            
            Log.i(TAG, "Legacy app intent launched: ${intent.action}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute legacy app", e)
            callback.onFailure(
                DmiBridge.RESULT_EXECUTION_FAILED,
                "Failed to execute legacy app: ${e.message}"
            )
        }
    }
    
    /**
     * 构建 Intent
     */
    private fun buildIntent(toolName: String, parameters: Map<String, Any>): Intent? {
        return when (toolName) {
            "dmi.app.browser.open" -> buildBrowserIntent(parameters)
            "dmi.app.email.send" -> buildEmailIntent(parameters)
            "dmi.app.share.text" -> buildShareIntent(parameters)
            "dmi.app.camera.capture" -> buildCameraIntent(parameters)
            "dmi.app.maps.navigate" -> buildMapsIntent(parameters)
            else -> null
        }
    }
    
    /**
     * 构建浏览器 Intent
     * 参数：url: String
     */
    private fun buildBrowserIntent(parameters: Map<String, Any>): Intent? {
        val url = parameters["url"] as? String ?: return null
        
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
    
    /**
     * 构建邮件 Intent
     * 参数：to: String, subject: String, body: String
     */
    private fun buildEmailIntent(parameters: Map<String, Any>): Intent? {
        val to = parameters["to"] as? String ?: return null
        val subject = parameters["subject"] as? String
        val body = parameters["body"] as? String
        
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$to")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            
            if (subject != null) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            
            if (body != null) {
                putExtra(Intent.EXTRA_TEXT, body)
            }
        }
    }
    
    /**
     * 构建分享 Intent
     * 参数：text: String, title: String (可选)
     */
    private fun buildShareIntent(parameters: Map<String, Any>): Intent? {
        val text = parameters["text"] as? String ?: return null
        val title = parameters["title"] as? String
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        return if (title != null) {
            Intent.createChooser(intent, title).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            intent
        }
    }
    
    /**
     * 构建相机 Intent
     * 参数：outputUri: String (可选)
     */
    private fun buildCameraIntent(parameters: Map<String, Any>): Intent? {
        val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        val outputUri = parameters["outputUri"] as? String
        if (outputUri != null) {
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri.parse(outputUri))
        }
        
        return intent
    }
    
    /**
     * 构建地图导航 Intent
     * 参数：destination: String 或 latitude: Double, longitude: Double
     */
    private fun buildMapsIntent(parameters: Map<String, Any>): Intent? {
        val destination = parameters["destination"] as? String
        val latitude = parameters["latitude"] as? Double
        val longitude = parameters["longitude"] as? Double
        
        val uri = when {
            destination != null -> {
                Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
            }
            latitude != null && longitude != null -> {
                Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
            }
            else -> return null
        }
        
        return Intent(Intent.ACTION_VIEW).apply {
            data = uri
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
    
    /**
     * 检查 Intent 是否可解析
     */
    private fun isIntentResolvable(intent: Intent): Boolean {
        val packageManager = context.packageManager
        val resolveInfo = packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolveInfo != null
    }
}
