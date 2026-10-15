package com.ainative.action.registry

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * A3.1 动作注册表 - 动作注册表管理器
 * 
 * 职责：
 * 1. 加载和管理动作注册表
 * 2. 提供动作查询接口
 * 3. 校验动作注册项有效性
 * 4. 支持动作按能力过滤
 * 5. 提供动作描述和列表功能
 * 
 * 设计要点：
 * - 单例模式，全局唯一注册表
 * - 支持从 JSON 文件加载
 * - 线程安全的查询接口
 * - 构建期校验集成
 */
class ActionRegistry private constructor() {
    
    companion object {
        private const val TAG = "ActionRegistry"
        
        // 单例实例
        @Volatile
        private var instance: ActionRegistry? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(): ActionRegistry {
            return instance ?: synchronized(this) {
                instance ?: ActionRegistry().also { instance = it }
            }
        }
        
        /**
         * 注册表文件路径（系统级）
         */
        private const val REGISTRY_PATH = "/system/etc/ainative/action-registry.json"
    }
    
    // 动作注册表（name -> entry）
    private val registry = ConcurrentHashMap<String, ActionRegistryEntry>()
    
    // 执行器索引（executorId -> actions）
    private val executorIndex = ConcurrentHashMap<String, MutableList<String>>()
    
    // 能力索引（capability -> actions）
    private val capabilityIndex = ConcurrentHashMap<String, MutableList<String>>()
    
    // 是否已加载
    @Volatile
    private var loaded = false
    
    /**
     * 从系统路径加载注册表
     */
    fun load(): Boolean {
        if (loaded) {
            Log.w(TAG, "Registry already loaded")
            return true
        }
        
        return try {
            val file = File(REGISTRY_PATH)
            if (!file.exists()) {
                Log.e(TAG, "Registry file not found: $REGISTRY_PATH")
                return false
            }
            
            loadFromFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load registry", e)
            false
        }
    }
    
    /**
     * 从文件加载注册表
     */
    fun loadFromFile(file: File): Boolean {
        return try {
            val content = file.readText()
            loadFromJson(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from file: ${file.path}", e)
            false
        }
    }
    
    /**
     * 从输入流加载注册表
     */
    fun loadFromStream(stream: InputStream): Boolean {
        return try {
            val content = stream.bufferedReader().use { it.readText() }
            loadFromJson(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from stream", e)
            false
        }
    }
    
    /**
     * 从 JSON 字符串加载注册表
     */
    fun loadFromJson(jsonContent: String): Boolean {
        return try {
            val json = JSONObject(jsonContent)
            val version = json.optString("version", "unknown")
            val actionsArray = json.getJSONArray("actions")
            
            Log.i(TAG, "Loading action registry version: $version")
            
            // 清空现有注册表
            registry.clear()
            executorIndex.clear()
            capabilityIndex.clear()
            
            // 加载所有动作
            val errors = mutableListOf<String>()
            var loadedCount = 0
            
            for (i in 0 until actionsArray.length()) {
                val actionJson = actionsArray.getJSONObject(i)
                
                try {
                    val entry = ActionRegistryEntry.fromJson(actionJson)
                    
                    // 校验动作有效性
                    val validation = entry.validate()
                    if (!validation.isValid) {
                        errors.add("Action ${entry.name}: ${validation.getErrorMessage()}")
                        continue
                    }
                    
                    // 注册动作
                    registerAction(entry)
                    loadedCount++
                    
                } catch (e: Exception) {
                    errors.add("Failed to parse action at index $i: ${e.message}")
                }
            }
            
            loaded = loadedCount > 0
            
            Log.i(TAG, "Loaded $loadedCount actions from registry")
            
            if (errors.isNotEmpty()) {
                Log.w(TAG, "Registry loading errors:\n${errors.joinToString("\n")}")
            }
            
            loaded
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse registry JSON", e)
            false
        }
    }
    
    /**
     * 注册单个动作（内部使用）
     */
    private fun registerAction(entry: ActionRegistryEntry) {
        // 1. 注册到主表
        registry[entry.name] = entry
        
        // 2. 索引到执行器
        executorIndex.getOrPut(entry.executorId) { mutableListOf() }
            .add(entry.name)
        
        // 3. 索引到能力
        capabilityIndex.getOrPut(entry.requiredCapability) { mutableListOf() }
            .add(entry.name)
        
        Log.d(TAG, "Registered action: ${entry.name} (executor=${entry.executorId}, risk=${entry.riskLevel})")
    }
    
    /**
     * 动态注册资产动作（A8.2 资产与动作注册表统一）
     * 
     * 用于从 AgentAssetLibraryService 注册 Skill/DMI/Macro 资产
     * 
     * @param actionName 动作名称（通常为资产ID）
     * @param entry 动作注册项
     * @return 是否成功注册
     */
    fun registerAssetAction(actionName: String, entry: ActionRegistryEntry): Boolean {
        return try {
            // 验证 executorId 必须是资产类型
            if (entry.executorId !in listOf("skill", "dmi", "macro")) {
                Log.e(TAG, "Invalid executor for asset action: ${entry.executorId}")
                return false
            }
            
            // 验证动作有效性
            val validation = entry.validate()
            if (!validation.isValid) {
                Log.e(TAG, "Invalid action entry: ${validation.getErrorMessage()}")
                return false
            }
            
            // 注册动作
            registerAction(entry)
            
            Log.i(TAG, "Asset action registered: $actionName (type=${entry.executorId})")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register asset action: $actionName", e)
            false
        }
    }
    
    /**
     * 注销资产动作（A8.2）
     * 
     * 用于资产被禁用或删除时从注册表移除
     * 
     * @param actionName 动作名称
     * @return 是否成功注销
     */
    fun unregisterAssetAction(actionName: String): Boolean {
        return try {
            val entry = registry.remove(actionName)
            if (entry == null) {
                Log.w(TAG, "Action not found for unregistration: $actionName")
                return false
            }
            
            // 从执行器索引移除
            executorIndex[entry.executorId]?.remove(actionName)
            
            // 从能力索引移除
            capabilityIndex[entry.requiredCapability]?.remove(actionName)
            
            Log.i(TAG, "Asset action unregistered: $actionName")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister asset action: $actionName", e)
            false
        }
    }
    
    /**
     * 更新资产动作状态（A8.2）
     * 
     * 用于资产状态变更时同步到动作注册表
     * 
     * @param actionName 动作名称
     * @param enabled 是否启用
     * @return 是否成功更新
     */
    fun updateAssetActionStatus(actionName: String, enabled: Boolean): Boolean {
        val entry = registry[actionName]
        if (entry == null) {
            Log.w(TAG, "Action not found for status update: $actionName")
            return false
        }
        
        // ActionRegistryEntry 不可变，需要重新注册
        // 这里只是标记，实际执行时由 ActionService 检查资产状态
        Log.d(TAG, "Asset action status updated: $actionName enabled=$enabled")
        return true
    }
    
    /**
     * 获取动作注册项
     */
    fun getAction(actionName: String): ActionRegistryEntry? {
        return registry[actionName]
    }
    
    /**
     * 检查动作是否存在
     */
    fun hasAction(actionName: String): Boolean {
        return registry.containsKey(actionName)
    }
    
    /**
     * 列出所有动作名称
     */
    fun listActions(): List<String> {
        return registry.keys.toList().sorted()
    }
    
    /**
     * 按执行器过滤动作
     */
    fun listActionsByExecutor(executorId: String): List<String> {
        return executorIndex[executorId]?.toList() ?: emptyList()
    }
    
    /**
     * 按能力过滤动作
     */
    fun listActionsByCapability(capability: String): List<String> {
        return capabilityIndex[capability]?.toList() ?: emptyList()
    }
    
    /**
     * 按风险等级过滤动作
     */
    fun listActionsByRiskLevel(riskLevel: Int): List<String> {
        return registry.values
            .filter { it.riskLevel == riskLevel }
            .map { it.name }
            .sorted()
    }
    
    /**
     * 获取所有执行器ID
     */
    fun listExecutors(): List<String> {
        return executorIndex.keys.toList().sorted()
    }
    
    /**
     * 获取所有能力名称
     */
    fun listCapabilities(): List<String> {
        return capabilityIndex.keys.toList().sorted()
    }
    
    /**
     * 描述动作（用于 describeAction API）
     */
    fun describeAction(actionName: String): ActionDescription? {
        val entry = getAction(actionName) ?: return null
        
        return ActionDescription(
            name = entry.name,
            displayName = entry.displayName,
            description = entry.description ?: "No description available",
            executorId = entry.executorId,
            riskLevel = entry.riskLevel,
            requiredCapability = entry.requiredCapability,
            inputSchema = entry.inputSchema.toString(2),
            outputSchema = entry.outputSchema.toString(2),
            requiresConfirmation = entry.requiresUserConfirmation(),
            deprecated = entry.deprecated,
            replacedBy = entry.replacedBy
        )
    }
    
    /**
     * 校验注册表完整性
     */
    fun validateRegistry(): RegistryValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // 1. 校验每个动作
        registry.values.forEach { entry ->
            val validation = entry.validate()
            if (!validation.isValid) {
                errors.add("Action ${entry.name}: ${validation.getErrorMessage()}")
            }
        }
        
        // 2. 校验动作名唯一性（应该已经保证）
        val duplicates = registry.keys.groupingBy { it }.eachCount().filter { it.value > 1 }
        if (duplicates.isNotEmpty()) {
            errors.add("Duplicate action names: ${duplicates.keys.joinToString(", ")}")
        }
        
        // 3. 校验弃用动作的替代项存在性
        registry.values.filter { it.deprecated && it.replacedBy != null }.forEach { entry ->
            if (!registry.containsKey(entry.replacedBy!!)) {
                warnings.add("Deprecated action ${entry.name} references non-existent replacement: ${entry.replacedBy}")
            }
        }
        
        // 4. 统计信息
        val stats = RegistryStats(
            totalActions = registry.size,
            executorCount = executorIndex.size,
            capabilityCount = capabilityIndex.size,
            riskLevelDistribution = mapOf(
                RiskLevel.LOW to registry.values.count { it.riskLevel == RiskLevel.LOW },
                RiskLevel.MEDIUM to registry.values.count { it.riskLevel == RiskLevel.MEDIUM },
                RiskLevel.HIGH to registry.values.count { it.riskLevel == RiskLevel.HIGH }
            ),
            deprecatedCount = registry.values.count { it.deprecated }
        )
        
        return RegistryValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            stats = stats
        )
    }
    
    /**
     * 导出为 JSON
     */
    fun exportToJson(version: String = "1.0"): JSONObject {
        val actionsArray = JSONArray()
        
        registry.values.sortedBy { it.name }.forEach { entry ->
            actionsArray.put(entry.toJson())
        }
        
        return JSONObject().apply {
            put("version", version)
            put("generatedAt", System.currentTimeMillis())
            put("actions", actionsArray)
        }
    }
    
    /**
     * 清空注册表
     */
    fun clear() {
        registry.clear()
        executorIndex.clear()
        capabilityIndex.clear()
        loaded = false
        Log.i(TAG, "Registry cleared")
    }
}

/**
 * 动作描述（用于 API 返回）
 */
data class ActionDescription(
    val name: String,
    val displayName: String,
    val description: String,
    val executorId: String,
    val riskLevel: Int,
    val requiredCapability: String,
    val inputSchema: String,
    val outputSchema: String,
    val requiresConfirmation: Boolean,
    val deprecated: Boolean,
    val replacedBy: String?
)

/**
 * 注册表校验结果
 */
data class RegistryValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val stats: RegistryStats
) {
    fun getReport(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Action Registry Validation Report ===")
        sb.appendLine()
        sb.appendLine("Status: ${if (isValid) "VALID" else "INVALID"}")
        sb.appendLine()
        sb.appendLine("Statistics:")
        sb.appendLine("  Total Actions: ${stats.totalActions}")
        sb.appendLine("  Executors: ${stats.executorCount}")
        sb.appendLine("  Capabilities: ${stats.capabilityCount}")
        sb.appendLine("  Risk Level Distribution:")
        sb.appendLine("    - Low (0): ${stats.riskLevelDistribution[RiskLevel.LOW] ?: 0}")
        sb.appendLine("    - Medium (1): ${stats.riskLevelDistribution[RiskLevel.MEDIUM] ?: 0}")
        sb.appendLine("    - High (2): ${stats.riskLevelDistribution[RiskLevel.HIGH] ?: 0}")
        sb.appendLine("  Deprecated: ${stats.deprecatedCount}")
        sb.appendLine()
        
        if (errors.isNotEmpty()) {
            sb.appendLine("Errors (${errors.size}):")
            errors.forEach { sb.appendLine("  - $it") }
            sb.appendLine()
        }
        
        if (warnings.isNotEmpty()) {
            sb.appendLine("Warnings (${warnings.size}):")
            warnings.forEach { sb.appendLine("  - $it") }
            sb.appendLine()
        }
        
        return sb.toString()
    }
}

/**
 * 注册表统计信息
 */
data class RegistryStats(
    val totalActions: Int,
    val executorCount: Int,
    val capabilityCount: Int,
    val riskLevelDistribution: Map<Int, Int>,
    val deprecatedCount: Int
)
