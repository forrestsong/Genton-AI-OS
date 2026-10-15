package com.genton.brain.dmi

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * DMI 执行器
 * 
 * 职责：
 * 1. 管理 DmiBridge 实例
 * 2. 提供统一的 DMI 工具执行接口
 * 3. 与 ToolLoop 集成
 * 4. 结果回调管理
 * 
 * F2.2 DMI 桥方案 - 集成层
 */
class DmiExecutor(private val dmiBridge: DmiBridge) {
    
    companion object {
        private const val TAG = "DmiExecutor"
    }
    
    // 执行回调映射表（requestId -> callback）
    private val callbackMap = ConcurrentHashMap<String, DmiExecutionCallback>()
    
    /**
     * 执行 DMI 工具
     * 
     * @param requestId 请求 ID（用于异步回调关联）
     * @param toolName DMI 工具名称
     * @param parametersJson 参数 JSON 字符串
     * @param callback 执行回调
     */
    fun executeDmiTool(
        requestId: String,
        toolName: String,
        parametersJson: String,
        callback: DmiExecutionCallback
    ) {
        Log.i(TAG, "Executing DMI tool: requestId=$requestId, toolName=$toolName")
        
        // 注册回调
        callbackMap[requestId] = callback
        
        // 创建包装回调（自动清理）
        val wrappedCallback = object : DmiExecutionCallback {
            override fun onSuccess(resultJson: String) {
                try {
                    callback.onSuccess(resultJson)
                } finally {
                    callbackMap.remove(requestId)
                }
            }
            
            override fun onFailure(errorCode: Int, errorMessage: String) {
                try {
                    callback.onFailure(errorCode, errorMessage)
                } finally {
                    callbackMap.remove(requestId)
                }
            }
        }
        
        // 委托给 DmiBridge 执行
        dmiBridge.executeDmiTool(toolName, parametersJson, wrappedCallback)
    }
    
    /**
     * 检查工具是否支持
     */
    fun isToolSupported(toolName: String): Boolean {
        return dmiBridge.isToolSupported(toolName)
    }
    
    /**
     * 获取支持的工具列表
     */
    fun getSupportedTools(): List<String> {
        return dmiBridge.getSupportedTools()
    }
    
    /**
     * 取消执行（如果支持）
     * 
     * 注意：大部分 DMI 操作是同步且不可取消的
     */
    fun cancelExecution(requestId: String) {
        Log.i(TAG, "Cancelling DMI execution: $requestId")
        
        val callback = callbackMap.remove(requestId)
        if (callback != null) {
            callback.onFailure(
                DmiBridge.RESULT_EXECUTION_FAILED,
                "Execution cancelled"
            )
        }
    }
}
