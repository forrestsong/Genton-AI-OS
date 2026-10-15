package com.ainative.brain.dmi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 短信发送能力桥
 * 
 * DMI: dmi.sms.send
 * 功能：发送短信
 * 参数：
 *   - phoneNumber: String (必填) - 手机号码
 *   - message: String (必填) - 短信内容
 */
class SmsSendBridge(private val context: Context) : SystemCapabilityBridge {
    
    companion object {
        private const val TAG = "SmsSendBridge"
        private const val MAX_SMS_LENGTH = 160
    }
    
    override fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        Log.i(TAG, "Executing SMS send")
        
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
            val phoneNumber = parameters["phoneNumber"] as String
            val message = parameters["message"] as String
            
            // 4. 获取 SmsManager
            val smsManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? SmsManager
                ?: SmsManager.getDefault()
            
            // 5. 发送短信（自动分片处理长短信）
            if (message.length > MAX_SMS_LENGTH) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(
                    phoneNumber,
                    null,
                    parts,
                    null,
                    null
                )
            } else {
                smsManager.sendTextMessage(
                    phoneNumber,
                    null,
                    message,
                    null,
                    null
                )
            }
            
            // 6. 返回成功结果
            val result = JSONObject().apply {
                put("success", true)
                put("phoneNumber", phoneNumber)
                put("messageLength", message.length)
            }
            
            callback.onSuccess(result.toString())
            
            Log.i(TAG, "SMS sent to: $phoneNumber")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS", e)
            callback.onFailure(
                DmiBridge.RESULT_EXECUTION_FAILED,
                "Failed to send SMS: ${e.message}"
            )
        }
    }
    
    override fun validateParameters(parameters: Map<String, Any>): String? {
        // 检查 phoneNumber
        if (!parameters.containsKey("phoneNumber")) {
            return "Missing required parameter: phoneNumber"
        }
        
        val phoneNumber = parameters["phoneNumber"]
        if (phoneNumber !is String || phoneNumber.isEmpty()) {
            return "Invalid parameter: phoneNumber must be a non-empty string"
        }
        
        // 检查 message
        if (!parameters.containsKey("message")) {
            return "Missing required parameter: message"
        }
        
        val message = parameters["message"]
        if (message !is String || message.isEmpty()) {
            return "Invalid parameter: message must be a non-empty string"
        }
        
        return null
    }
    
    override fun checkPermissions(): String? {
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        
        return if (hasSmsPermission) {
            null
        } else {
            "Missing permission: SEND_SMS"
        }
    }
}
