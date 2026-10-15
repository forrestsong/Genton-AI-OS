package com.genton.brain.dmi

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * F2.2 DMI 桥方案 - 确定性校验器
 *
 * 核心职责：
 * 1. 执行前校验：参数完整性、权限、桥接目标可用性
 * 2. 确定性判定：执行前确保操作可成功（避免试错）
 * 3. 回退链推荐：当前 DMI 不可用时推荐备选方案
 *
 * 校验规则（对齐 F2.1 schema EXIST-001 至 EXIST-009）：
 * - 参数必需性校验
 * - 权限校验
 * - 桥接目标存在性校验
 * - 能力令牌校验
 * - 安全级别校验
 *
 * 依赖：
 * - F2.1 DMI schema（execution.bridge 字段定义）
 * - A4.2 能力令牌体系（capabilityScope 校验）
 */
class DmiDeterministicValidator(
    private val dmiBridge: DmiBridge
) {

    companion object {
        private const val TAG = "DmiValidator"
    }

    /**
     * 校验结果
     */
    data class ValidationResult(
        val valid: Boolean,
        val errorCode: Int?,
        val errorMessage: String?,
        val suggestions: List<String>  // 建议的回退 DMI（如果当前不可用）
    ) {
        companion object {
            fun success() = ValidationResult(true, null, null, emptyList())
            fun failure(errorCode: Int, errorMessage: String, suggestions: List<String> = emptyList()) =
                ValidationResult(false, errorCode, errorMessage, suggestions)
        }
    }

    /**
     * 执行前确定性校验
     *
     * @param dmiDeclaration DMI 声明（OpenAPI 3.0 + x-genton + execution）
     * @param toolName 工具名称
     * @param parameters 参数
     * @return 校验结果
     */
    fun validate(
        dmiDeclaration: JSONObject,
        toolName: String,
        parameters: Map<String, Any>
    ): ValidationResult {
        Log.d(TAG, "Validating DMI tool: $toolName")

        try {
            // 1. 获取 operation 声明
            val operation = extractOperation(dmiDeclaration, toolName)
                ?: return ValidationResult.failure(
                    DmiBridge.RESULT_NOT_SUPPORTED,
                    "Tool not found in declaration: $toolName"
                )

            // 2. 参数完整性校验
            val paramValidation = validateParameters(operation, parameters)
            if (!paramValidation.valid) return paramValidation

            // 3. 权限校验
            val permissionValidation = validatePermissions(dmiDeclaration, operation)
            if (!permissionValidation.valid) return permissionValidation

            // 4. 桥接目标可用性校验
            val bridgeValidation = validateBridgeTarget(operation)
            if (!bridgeValidation.valid) return bridgeValidation

            // 5. 能力令牌校验（L2 级别）
            val capabilityValidation = validateCapabilityScope(operation)
            if (!capabilityValidation.valid) return capabilityValidation

            Log.i(TAG, "Validation passed for tool: $toolName")
            return ValidationResult.success()

        } catch (e: Exception) {
            Log.e(TAG, "Validation error", e)
            return ValidationResult.failure(
                DmiBridge.RESULT_INVALID_PARAMETERS,
                "Validation failed: ${e.message}"
            )
        }
    }

    /**
     * 提取 operation 声明
     */
    private fun extractOperation(declaration: JSONObject, toolName: String): JSONObject? {
        val paths = declaration.optJSONObject("paths") ?: return null

        // 遍历所有路径
        val pathKeys = paths.keys()
        while (pathKeys.hasNext()) {
            val path = pathKeys.next()
            val pathItem = paths.optJSONObject(path) ?: continue

            // 遍历 HTTP 方法
            val methods = listOf("get", "post", "put", "delete", "patch")
            for (method in methods) {
                val operation = pathItem.optJSONObject(method) ?: continue
                val operationId = operation.optString("operationId", "")

                if (operationId == toolName) {
                    return operation
                }
            }
        }

        return null
    }

    /**
     * 参数完整性校验
     */
    private fun validateParameters(
        operation: JSONObject,
        parameters: Map<String, Any>
    ): ValidationResult {
        val requestBody = operation.optJSONObject("requestBody") ?: return ValidationResult.success()
        val content = requestBody.optJSONObject("content") ?: return ValidationResult.success()
        val jsonContent = content.optJSONObject("application/json") ?: return ValidationResult.success()
        val schema = jsonContent.optJSONObject("schema") ?: return ValidationResult.success()

        // 检查必需参数
        val required = schema.optJSONArray("required") ?: return ValidationResult.success()
        for (i in 0 until required.length()) {
            val requiredParam = required.getString(i)
            if (!parameters.containsKey(requiredParam)) {
                return ValidationResult.failure(
                    DmiBridge.RESULT_INVALID_PARAMETERS,
                    "Missing required parameter: $requiredParam"
                )
            }
        }

        return ValidationResult.success()
    }

    /**
     * 权限校验
     */
    private fun validatePermissions(
        declaration: JSONObject,
        operation: JSONObject
    ): ValidationResult {
        // 获取全局所需权限
        val info = declaration.optJSONObject("info") ?: return ValidationResult.success()
        val xGenton = info.optJSONObject("x-genton") ?: return ValidationResult.success()
        val requiredPermissions = xGenton.optJSONArray("requiredPermissions")
            ?: return ValidationResult.success()

        // 检查权限（这里简化处理，实际需要通过 PackageManager 检查）
        val missingPermissions = mutableListOf<String>()
        for (i in 0 until requiredPermissions.length()) {
            val permission = requiredPermissions.getString(i)
            // TODO: 实际检查权限
            // if (!hasPermission(permission)) missingPermissions.add(permission)
        }

        if (missingPermissions.isNotEmpty()) {
            return ValidationResult.failure(
                DmiBridge.RESULT_PERMISSION_DENIED,
                "Missing permissions: ${missingPermissions.joinToString(", ")}"
            )
        }

        return ValidationResult.success()
    }

    /**
     * 桥接目标可用性校验
     */
    private fun validateBridgeTarget(operation: JSONObject): ValidationResult {
        val execution = operation.optJSONObject("execution") ?: return ValidationResult.failure(
            DmiBridge.RESULT_INVALID_PARAMETERS,
            "Missing execution declaration (EXIST-001)"
        )

        val bridge = execution.optJSONObject("bridge") ?: return ValidationResult.failure(
            DmiBridge.RESULT_INVALID_PARAMETERS,
            "Missing bridge configuration (EXIST-005)"
        )

        val bridgeType = bridge.optString("type", "")
        if (bridgeType.isEmpty()) {
            return ValidationResult.failure(
                DmiBridge.RESULT_INVALID_PARAMETERS,
                "Missing bridge.type (EXIST-005)"
            )
        }

        val target = bridge.optString("target", "")
        if (target.isEmpty()) {
            return ValidationResult.failure(
                DmiBridge.RESULT_INVALID_PARAMETERS,
                "Missing bridge.target (EXIST-006)"
            )
        }

        // 验证桥接类型合法性
        val validTypes = setOf("system", "app", "http", "intent", "content_provider", "aidl")
        if (bridgeType !in validTypes) {
            return ValidationResult.failure(
                DmiBridge.RESULT_NOT_SUPPORTED,
                "Invalid bridge.type: $bridgeType (EXIST-005)"
            )
        }

        // TODO: 验证目标存在性（系统服务/应用包/端点 URL 可达）
        // 例如：system → 检查 SystemService 存在
        //      app → 检查应用已安装
        //      http → 检查端点可达性

        return ValidationResult.success()
    }

    /**
     * 能力令牌校验（L2 级别）
     */
    private fun validateCapabilityScope(operation: JSONObject): ValidationResult {
        val xGenton = operation.optJSONObject("x-genton") ?: return ValidationResult.success()
        val securityLevel = xGenton.optString("securityLevel", "L0")

        // L2 级别必须声明 capabilityScope（EXIST-008）
        if (securityLevel == "L2") {
            val capabilityScope = xGenton.optJSONArray("capabilityScope")
            if (capabilityScope == null || capabilityScope.length() == 0) {
                return ValidationResult.failure(
                    DmiBridge.RESULT_INVALID_PARAMETERS,
                    "L2 security level requires capabilityScope (EXIST-008)"
                )
            }

            // TODO: 检查能力令牌是否已授予
            // 对接 A4.2 能力令牌体系
        }

        return ValidationResult.success()
    }

    /**
     * 推荐回退 DMI（当当前 DMI 不可用时）
     *
     * @param dmiDeclaration DMI 声明
     * @return 回退 DMI 列表（按优先级排序）
     */
    fun suggestFallback(dmiDeclaration: JSONObject): List<String> {
        val info = dmiDeclaration.optJSONObject("info") ?: return emptyList()
        val xGenton = info.optJSONObject("x-genton") ?: return emptyList()
        val fallbackChain = xGenton.optJSONArray("fallbackChain") ?: return emptyList()

        val suggestions = mutableListOf<String>()
        for (i in 0 until fallbackChain.length()) {
            suggestions.add(fallbackChain.getString(i))
        }

        Log.d(TAG, "Fallback suggestions: $suggestions")
        return suggestions
    }

    /**
     * 批量校验多个 DMI（选择最佳可用）
     *
     * @param candidates 候选 DMI 列表（DMI 声明 + 工具名）
     * @param parameters 参数
     * @return 第一个校验通过的 DMI，如果全部失败则返回 null
     */
    fun selectBestAvailableDmi(
        candidates: List<Pair<JSONObject, String>>,
        parameters: Map<String, Any>
    ): Pair<JSONObject, String>? {
        for ((declaration, toolName) in candidates) {
            val result = validate(declaration, toolName, parameters)
            if (result.valid) {
                Log.i(TAG, "Selected DMI: $toolName (first valid)")
                return Pair(declaration, toolName)
            }
        }

        Log.w(TAG, "No valid DMI found in candidates")
        return null
    }
}
