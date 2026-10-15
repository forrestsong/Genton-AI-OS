package com.genton.brain.dmi

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DMI 桥接器（Device-Model Interface Bridge）
 * 
 * 职责：
 * 1. 系统能力桥：封装 Android 系统 API（通话、短信、联系人、日历等）
 * 2. Legacy App 桥接：通过 Intent 调用第三方应用
 * 3. 参数验证与转换：DMI schema → 系统 API 参数
 * 4. 权限检查与结果回调
 * 
 * F2.2 DMI 桥方案与落地
 */
class DmiBridge(private val context: Context) {
    
    companion object {
        private const val TAG = "DmiBridge"
        
        // DMI 执行结果码
        const val RESULT_SUCCESS = 0
        const val RESULT_PERMISSION_DENIED = 1
        const val RESULT_INVALID_PARAMETERS = 2
        const val RESULT_NOT_SUPPORTED = 3
        const val RESULT_EXECUTION_FAILED = 4

        // 同步桥接超时（毫秒）
        private const val BRIDGE_TIMEOUT_MS = 30_000L
    }
    
    // 系统能力桥注册表
    private val systemCapabilityBridges = mutableMapOf<String, SystemCapabilityBridge>()
    
    // Legacy App 桥接器
    private lateinit var legacyAppBridge: LegacyAppBridge
    
    /**
     * 初始化 DMI 桥
     */
    fun initialize() {
        Log.i(TAG, "Initializing DMI Bridge")
        
        // 注册系统能力桥
        registerSystemCapabilityBridges()
        
        // 初始化 Legacy App 桥接器
        legacyAppBridge = LegacyAppBridge(context)
        
        Log.i(TAG, "DMI Bridge initialized with ${systemCapabilityBridges.size} system bridges")
    }
    
    /**
     * 注册系统能力桥
     */
    private fun registerSystemCapabilityBridges() {
        // 通话能力桥
        systemCapabilityBridges["dmi.telephony.call"] = com.genton.brain.dmi.bridges.TelephonyCallBridge(context)
        
        // 短信能力桥
        systemCapabilityBridges["dmi.sms.send"] = com.genton.brain.dmi.bridges.SmsSendBridge(context)
        systemCapabilityBridges["dmi.sms.read"] = com.genton.brain.dmi.bridges.SmsReadBridge(context)
        
        // 联系人能力桥
        systemCapabilityBridges["dmi.contacts.query"] = com.genton.brain.dmi.bridges.ContactsQueryBridge(context)
        systemCapabilityBridges["dmi.contacts.create"] = com.genton.brain.dmi.bridges.ContactsCreateBridge(context)
        systemCapabilityBridges["dmi.contacts.update"] = com.genton.brain.dmi.bridges.ContactsUpdateBridge(context)
        
        // 日历能力桥
        systemCapabilityBridges["dmi.calendar.query"] = com.genton.brain.dmi.bridges.CalendarQueryBridge(context)
        systemCapabilityBridges["dmi.calendar.create"] = com.genton.brain.dmi.bridges.CalendarCreateBridge(context)
        systemCapabilityBridges["dmi.calendar.update"] = com.genton.brain.dmi.bridges.CalendarUpdateBridge(context)
        
        // 文件能力桥
        systemCapabilityBridges["dmi.file.read"] = com.genton.brain.dmi.bridges.FileReadBridge(context)
        systemCapabilityBridges["dmi.file.write"] = com.genton.brain.dmi.bridges.FileWriteBridge(context)
        
        // 设置能力桥
        systemCapabilityBridges["dmi.settings.get"] = com.genton.brain.dmi.bridges.SettingsGetBridge(context)
        systemCapabilityBridges["dmi.settings.set"] = com.genton.brain.dmi.bridges.SettingsSetBridge(context)
        
        // 通知能力桥（F3.2 首批 Skill 资产依赖）
        systemCapabilityBridges["dmi.notification.notify"] = com.genton.brain.dmi.bridges.NotificationBridge(context)
        
        Log.d(TAG, "Registered ${systemCapabilityBridges.size} system capability bridges")
    }
    
    /**
     * 执行 DMI 工具调用
     * 
     * @param toolName DMI 工具名称（如 "dmi.telephony.call"）
     * @param parametersJson 参数 JSON 字符串
     * @param callback 执行回调
     */
    fun executeDmiTool(
        toolName: String,
        parametersJson: String,
        callback: DmiExecutionCallback
    ) {
        Log.i(TAG, "Executing DMI tool: $toolName")
        
        try {
            // 1. 解析参数
            val parameters = parseParameters(parametersJson)
            
            // 2. 判断是系统能力还是 Legacy App
            if (systemCapabilityBridges.containsKey(toolName)) {
                // 系统能力桥
                executeSystemCapability(toolName, parameters, callback)
            } else if (toolName.startsWith("dmi.app.")) {
                // Legacy App 桥接
                executeLegacyApp(toolName, parameters, callback)
            } else {
                Log.w(TAG, "Unknown DMI tool: $toolName")
                callback.onFailure(
                    RESULT_NOT_SUPPORTED,
                    "DMI tool not supported: $toolName"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute DMI tool: $toolName", e)
            callback.onFailure(
                RESULT_EXECUTION_FAILED,
                "Execution failed: ${e.message}"
            )
        }
    }
    
    /**
     * 执行系统能力
     */
    private fun executeSystemCapability(
        toolName: String,
        parameters: Map<String, Any>,
        callback: DmiExecutionCallback
    ) {
        Log.d(TAG, "Executing system capability: $toolName")
        
        val bridge = systemCapabilityBridges[toolName]
        if (bridge == null) {
            callback.onFailure(RESULT_NOT_SUPPORTED, "Bridge not found: $toolName")
            return
        }
        
        // 委托给具体的能力桥执行
        bridge.execute(parameters, callback)
    }
    
    /**
     * 执行 Legacy App 调用
     */
    private fun executeLegacyApp(
        toolName: String,
        parameters: Map<String, Any>,
        callback: DmiExecutionCallback
    ) {
        Log.d(TAG, "Executing legacy app: $toolName")
        
        // 委托给 Legacy App 桥接器
        legacyAppBridge.execute(toolName, parameters, callback)
    }
    
    /**
     * 解析参数 JSON
     */
    private fun parseParameters(parametersJson: String): Map<String, Any> {
        val jsonObject = JSONObject(parametersJson)
        val parameters = mutableMapOf<String, Any>()
        
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            parameters[key] = jsonObject.get(key)
        }
        
        return parameters
    }
    
    /**
     * 检查工具是否支持
     */
    fun isToolSupported(toolName: String): Boolean {
        return systemCapabilityBridges.containsKey(toolName) || 
               toolName.startsWith("dmi.app.")
    }
    
    /**
     * 获取支持的工具列表
     */
    fun getSupportedTools(): List<String> {
        return systemCapabilityBridges.keys.toList()
    }

    /**
     * 同步执行 DMI 桥接（供 Skill 编排引擎等确定性调用方使用）
     *
     * @param dmiId DMI 资产 ID，支持 `dmi:domain.action` 与 `dmi.domain.action` 两种写法
     * @param operation 操作名；当 dmiId 未直接命中桥接工具时，用其组合工具名
     * @param parameters 调用参数
     * @return 同步执行结果
     */
    fun executeBridge(
        dmiId: String,
        operation: String,
        parameters: Map<String, Any>
    ): DmiExecutionResult {
        val toolName = resolveToolName(dmiId, operation)
        val parametersJson = JSONObject(parameters).toString()
        val latch = CountDownLatch(1)
        var outcome: DmiExecutionResult? = null

        executeDmiTool(toolName, parametersJson, object : DmiExecutionCallback {
            override fun onSuccess(resultJson: String) {
                outcome = DmiExecutionResult(RESULT_SUCCESS, null, jsonToMap(resultJson))
                latch.countDown()
            }

            override fun onFailure(errorCode: Int, errorMessage: String) {
                outcome = DmiExecutionResult(errorCode, errorMessage, emptyMap())
                latch.countDown()
            }
        })

        if (!latch.await(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Bridge execution timed out: $toolName")
            return DmiExecutionResult(RESULT_EXECUTION_FAILED, "Bridge execution timed out: $toolName", emptyMap())
        }

        return outcome
            ?: DmiExecutionResult(RESULT_EXECUTION_FAILED, "No result from bridge: $toolName", emptyMap())
    }

    /**
     * 归一化工具名：`dmi:calendar.query` → `dmi.calendar.query`
     */
    private fun resolveToolName(dmiId: String, operation: String): String {
        val normalized = dmiId.replace(':', '.')
        return when {
            systemCapabilityBridges.containsKey(normalized) -> normalized
            systemCapabilityBridges.containsKey(operation) -> operation
            else -> normalized
        }
    }

    /**
     * JSON 字符串 → Kotlin Map/List 结构（递归转换，便于编排引擎做 JSONPath 解析）
     */
    private fun jsonToMap(json: String): Map<String, Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            (jsonToAny(JSONObject(json)) as? Map<String, Any>) ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse bridge result: ${e.message}")
            emptyMap()
        }
    }

    private fun jsonToAny(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val map = mutableMapOf<String, Any?>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = jsonToAny(value.get(key))
                }
                map
            }
            is JSONArray -> (0 until value.length()).map { jsonToAny(value.get(it)) }
            else -> value
        }
    }
}

/**
 * DMI 同步执行结果
 *
 * @param resultCode 结果码（见 DmiBridge.RESULT_*）
 * @param errorMessage 错误信息（成功为 null）
 * @param data 结果数据（已转换为 Map/List 结构，便于 JSONPath 引用）
 */
data class DmiExecutionResult(
    val resultCode: Int,
    val errorMessage: String?,
    val data: Map<String, Any>
)
