package com.ainative.brain.dmi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * 通话能力桥
 * 
 * DMI: dmi.telephony.call
 * 功能：发起电话呼叫
 * 参数：
 *   - phoneNumber: String (必填) - 电话号码
 *   - videoCall: Boolean (可选，默认 false) - 是否视频通话
 */
class TelephonyCallBridge(private val context: Context) : SystemCapabilityBridge {
    
    companion object {
        private const val TAG = "TelephonyCallBridge"
    }
    
    override fun execute(parameters: Map<String, Any>, callback: DmiExecutionCallback) {
        Log.i(TAG, "Executing telephony call")
        
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
            val videoCall = parameters["videoCall"] as? Boolean ?: false
            
            // 4. 构建 Intent
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                
                if (videoCall) {
                    putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, 
                        android.telecom.VideoProfile.STATE_BIDIRECTIONAL)
                }
            }
            
            // 5. 发起呼叫
            context.startActivity(intent)
            
            // 6. 返回成功结果
            val result = JSONObject().apply {
                put("success", true)
                put("phoneNumber", phoneNumber)
                put("videoCall", videoCall)
            }
            
            callback.onSuccess(result.toString())
            
            Log.i(TAG, "Telephony call initiated: $phoneNumber")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate call", e)
            callback.onFailure(
                DmiBridge.RESULT_EXECUTION_FAILED,
                "Failed to initiate call: ${e.message}"
            )
        }
    }
    
    override fun validateParameters(parameters: Map<String, Any>): String? {
        // 检查必填参数
        if (!parameters.containsKey("phoneNumber")) {
            return "Missing required parameter: phoneNumber"
        }
        
        val phoneNumber = parameters["phoneNumber"]
        if (phoneNumber !is String || phoneNumber.isEmpty()) {
            return "Invalid parameter: phoneNumber must be a non-empty string"
        }
        
        // 验证电话号码格式（简单验证）
        if (!phoneNumber.matches(Regex("^[+\\d\\s()-]+$"))) {
            return "Invalid phone number format: $phoneNumber"
        }
        
        return null
    }
    
    override fun checkPermissions(): String? {
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        
        return if (hasCallPermission) {
            null
        } else {
            "Missing permission: CALL_PHONE"
        }
    }
}
