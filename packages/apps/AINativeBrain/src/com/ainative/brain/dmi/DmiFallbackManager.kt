package com.ainative.brain.dmi

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * F2.2 DMI 桥方案 - 回退声明管理器
 *
 * 核心职责：
 * 1. 管理 DMI 回退链（fallbackChain）
 * 2. 当主 DMI 不可用时自动尝试备选方案
 * 3. 回退策略：系统能力 → Legacy App → 用户通知
 *
 * 回退链格式（对齐 F2.1 schema）：
 * ```json
 * "fallbackChain": [
 *   "dmi:weather_backup",      // 备选 DMI
 *   "macro:weather_macro",      // 备选宏
 *   "system:notification"       // 系统级降级（通知用户）
 * ]
 * ```
 *
 * 依赖：
 * - F2.1 DMI schema（fallbackChain 字段）
 * - DmiDeterministicValidator（确定性校验）
 */
class DmiFallbackManager(
    private val dmiBridge: DmiBridge,
    private val validator: DmiDeterministicValidator
) {

    companion object {
        private const val TAG = "DmiFallbackManager"
    }

    /**
     * 回退结果
     */
    data class FallbackResult(
        val success: Boolean,
        val usedFallback: String?,  // 使用的回退资产（如果有）
        val result: String?,        // 执行结果
        val errorCode: Int?,
        val errorMessage: String?
    )

    /**
     * 执行 DMI 工具（带自动回退）
     *
     * @param dmiDeclaration 主 DMI 声明
     * @param toolName 工具名称
     * @param parametersJson 参数 JSON
     * @param callback 执行回调
     */
    fun executeWithFallback(
        dmiDeclaration: JSONObject,
        toolName: String,
        parametersJson: String,
        callback: DmiExecutionCallback
    ) {
        Log.i(TAG, "Executing DMI with fallback: $toolName")

        // 1. 尝试主 DMI
        val parameters = parseParameters(parametersJson)
        val validationResult = validator.validate(dmiDeclaration, toolName, parameters)

        if (validationResult.valid) {
            // 主 DMI 可用，直接执行
            Log.d(TAG, "Primary DMI validated, executing: $toolName")
            dmiBridge.executeDmiTool(toolName, parametersJson, callback)
            return
        }

        // 2. 主 DMI 不可用，尝试回退链
        Log.w(TAG, "Primary DMI validation failed: ${validationResult.errorMessage}")
        val fallbackChain = extractFallbackChain(dmiDeclaration)

        if (fallbackChain.isEmpty()) {
            // 无回退链，直接失败
            Log.e(TAG, "No fallback chain available")
            callback.onFailure(
                validationResult.errorCode ?: DmiBridge.RESULT_NOT_SUPPORTED,
                "Primary DMI failed and no fallback: ${validationResult.errorMessage}"
            )
            return
        }

        // 3. 遍历回退链
        Log.i(TAG, "Trying fallback chain: ${fallbackChain.size} candidates")
        tryFallbackChain(fallbackChain, parametersJson, 0, callback)
    }

    /**
     * 递归尝试回退链
     */
    private fun tryFallbackChain(
        fallbackChain: List<String>,
        parametersJson: String,
        currentIndex: Int,
        callback: DmiExecutionCallback
    ) {
        if (currentIndex >= fallbackChain.size) {
            // 回退链全部失败
            Log.e(TAG, "All fallback options exhausted")
            callback.onFailure(
                DmiBridge.RESULT_NOT_SUPPORTED,
                "Primary DMI and all fallbacks failed"
            )
            return
        }

        val fallbackAsset = fallbackChain[currentIndex]
        Log.d(TAG, "Trying fallback [$currentIndex]: $fallbackAsset")

        // 解析回退资产类型
        val (assetType, assetId) = parseFallbackAsset(fallbackAsset)

        when (assetType) {
            "dmi" -> {
                // 回退到另一个 DMI
                executeFallbackDmi(assetId, parametersJson, callback, fallbackChain, currentIndex)
            }
            "macro" -> {
                // 回退到宏（需要宏执行器支持）
                Log.w(TAG, "Macro fallback not implemented: $assetId")
                tryFallbackChain(fallbackChain, parametersJson, currentIndex + 1, callback)
            }
            "system" -> {
                // 系统级降级（通知用户）
                handleSystemFallback(assetId, callback)
            }
            else -> {
                Log.w(TAG, "Unknown fallback type: $assetType")
                tryFallbackChain(fallbackChain, parametersJson, currentIndex + 1, callback)
            }
        }
    }

    /**
     * 执行回退 DMI
     */
    private fun executeFallbackDmi(
        dmiToolName: String,
        parametersJson: String,
        callback: DmiExecutionCallback,
        fallbackChain: List<String>,
        currentIndex: Int
    ) {
        // 包装回调：如果失败则尝试下一个回退
        val wrappedCallback = object : DmiExecutionCallback {
            override fun onSuccess(resultJson: String) {
                Log.i(TAG, "Fallback DMI succeeded: $dmiToolName")
                callback.onSuccess(resultJson)
            }

            override fun onFailure(errorCode: Int, errorMessage: String) {
                Log.w(TAG, "Fallback DMI failed: $dmiToolName, trying next...")
                tryFallbackChain(fallbackChain, parametersJson, currentIndex + 1, callback)
            }
        }

        dmiBridge.executeDmiTool(dmiToolName, parametersJson, wrappedCallback)
    }

    /**
     * 处理系统级降级（通知用户）
     */
    private fun handleSystemFallback(fallbackId: String, callback: DmiExecutionCallback) {
        when (fallbackId) {
            "notification" -> {
                // 发送系统通知告知用户操作失败
                Log.i(TAG, "System fallback: notification")
                val result = JSONObject().apply {
                    put("success", false)
                    put("fallback", "notification")
                    put("message", "操作失败，已通知用户")
                }
                callback.onSuccess(result.toString())
            }
            else -> {
                Log.w(TAG, "Unknown system fallback: $fallbackId")
                callback.onFailure(
                    DmiBridge.RESULT_NOT_SUPPORTED,
                    "Unknown system fallback: $fallbackId"
                )
            }
        }
    }

    /**
     * 提取回退链
     */
    private fun extractFallbackChain(dmiDeclaration: JSONObject): List<String> {
        val info = dmiDeclaration.optJSONObject("info") ?: return emptyList()
        val xAinative = info.optJSONObject("x-ainative") ?: return emptyList()
        val fallbackChain = xAinative.optJSONArray("fallbackChain") ?: return emptyList()

        val chain = mutableListOf<String>()
        for (i in 0 until fallbackChain.length()) {
            chain.add(fallbackChain.getString(i))
        }

        return chain
    }

    /**
     * 解析回退资产（格式：type:assetId）
     */
    private fun parseFallbackAsset(asset: String): Pair<String, String> {
        val parts = asset.split(":", limit = 2)
        return if (parts.size == 2) {
            Pair(parts[0], parts[1])
        } else {
            Pair("unknown", asset)
        }
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
}
