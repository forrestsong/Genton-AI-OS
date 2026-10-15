package com.genton.brain.skill

import android.content.Context
import android.util.Log
import com.genton.assets.AssetManifest
import com.genton.assets.IAgentAssetLibraryService
import org.json.JSONObject
import java.io.File

/**
 * Skill 资产管理器（F3.1 资产注册集成）
 * 
 * 功能：
 * 1. 加载系统预装 Skill 声明
 * 2. 注册 Skill 到 IAgentAssetLibraryService
 * 3. Skill 生命周期管理（启用/停用/版本更新）
 * 4. Skill 查询与解析
 */
class SkillAssetManager(
    private val context: Context,
    private val assetLibrary: IAgentAssetLibraryService,
    private val skillExecutor: SkillExecutor
) {
    
    companion object {
        private const val TAG = "SkillAssetManager"
        private const val SKILL_ASSETS_DIR = "skills"
        private const val KIND_SKILL = 1

        // 语义直连执行模式（对齐 AssetManifest.executionMode）
        private const val EXECUTION_MODE_SEMANTIC_DIRECT = 0
        private const val EXECUTION_MODE_MACRO_INJECT = 1
    }
    
    private val registeredSkills = mutableMapOf<String, SkillMetadata>()
    
    /**
     * 初始化：加载并注册所有预装 Skill
     */
    fun initialize() {
        Log.i(TAG, "初始化 Skill 资产管理器")
        
        try {
            // 扫描 assets/skills/ 目录
            val skillFiles = context.assets.list(SKILL_ASSETS_DIR) ?: emptyArray()
            Log.d(TAG, "发现 ${skillFiles.size} 个 Skill 声明文件")
            
            for (fileName in skillFiles) {
                if (fileName.endsWith(".json")) {
                    loadAndRegisterSkill(fileName)
                }
            }
            
            Log.i(TAG, "Skill 资产管理器初始化完成，已注册 ${registeredSkills.size} 个 Skill")
            
        } catch (e: Exception) {
            Log.e(TAG, "Skill 资产管理器初始化失败", e)
        }
    }
    
    /**
     * 加载并注册单个 Skill
     */
    private fun loadAndRegisterSkill(fileName: String) {
        try {
            val skillJson = context.assets.open("$SKILL_ASSETS_DIR/$fileName")
                .bufferedReader().use { it.readText() }
            
            val skillDeclaration = JSONObject(skillJson)
            val skill = skillDeclaration.getJSONObject("skill")
            val metadata = skillDeclaration.getJSONObject("metadata")
            
            val skillId = skill.getString("id")
            val skillName = skill.getString("name")
            val skillVersion = skill.getString("version")
            
            Log.d(TAG, "注册 Skill: $skillId v$skillVersion")
            
            // 构建 AssetManifest
            val manifest = AssetManifest().apply {
                id = skillId
                kind = KIND_SKILL
                version = skillVersion
                name = skillName
                description = skill.optString("description", "")
                
                // 能力声明
                capability = metadata.getString("capability")
                riskLevel = metadata.getInt("riskLevel")
                confirmation = when (metadata.optString("confirmation", "default")) {
                    "always" -> 1
                    "never" -> 2
                    else -> 0
                }
                
                // 语义直连标注（D15：宿主不自行判定，由资产声明）
                executionMode = when (metadata.optString("executionMode", "semantic_direct")) {
                    "macro_inject" -> EXECUTION_MODE_MACRO_INJECT
                    else -> EXECUTION_MODE_SEMANTIC_DIRECT
                }
                
                // 目标平台
                if (metadata.has("targetPlatform")) {
                    val platform = metadata.getJSONObject("targetPlatform")
                    osRange = platform.optString("osRange", "")
                    deviceModels = platform.optJSONArray("deviceModels")
                        ?.let { arr -> Array(arr.length()) { i -> arr.getString(i) } }
                        ?: emptyArray()
                }
                
                // Schema
                if (skillDeclaration.has("parameters")) {
                    inputSchemaJson = skillDeclaration.getJSONObject("parameters").toString()
                }
                if (skillDeclaration.has("returns")) {
                    outputSchemaJson = skillDeclaration.getJSONObject("returns").toString()
                }
                
                // 降级链
                if (skillDeclaration.has("fallbackChain")) {
                    val chain = skillDeclaration.getJSONArray("fallbackChain")
                    fallbacks = Array(chain.length()) { i -> chain.getString(i) }
                }
                
                // 标签
                if (metadata.has("labels")) {
                    val labelArray = metadata.getJSONArray("labels")
                    labels = Array(labelArray.length()) { i -> labelArray.getString(i) }
                }
                
                // 执行器
                executor = KIND_SKILL
                artifactType = "skill_bundle"
            }
            
            // 注册到资产库
            val assetIdOut = arrayOfNulls<String>(1)
            val result = assetLibrary.registerAsset(manifest, skillJson.toByteArray(), assetIdOut)
            
            if (result == IAgentAssetLibraryService.STATUS_OK) {
                Log.i(TAG, "✓ Skill 注册成功: $skillId -> ${assetIdOut[0]}")
                
                registeredSkills[skillId] = SkillMetadata(
                    id = skillId,
                    name = skillName,
                    version = skillVersion,
                    declaration = skillDeclaration,
                    enabled = true
                )
            } else {
                Log.e(TAG, "✗ Skill 注册失败: $skillId, 错误码=$result")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "加载 Skill 文件失败: $fileName", e)
        }
    }
    
    /**
     * 执行 Skill
     */
    fun executeSkill(skillId: String, parameters: Map<String, Any>): SkillExecutionResult {
        val metadata = registeredSkills[skillId]
        
        if (metadata == null) {
            Log.e(TAG, "Skill 未注册: $skillId")
            return SkillExecutionResult(
                success = false,
                errorCode = -1,
                errorMessage = "Skill 未注册: $skillId",
                data = emptyMap()
            )
        }
        
        if (!metadata.enabled) {
            Log.w(TAG, "Skill 已禁用: $skillId")
            return SkillExecutionResult(
                success = false,
                errorCode = -2,
                errorMessage = "Skill 已禁用: $skillId",
                data = emptyMap()
            )
        }
        
        Log.i(TAG, "执行 Skill: $skillId")
        return skillExecutor.execute(metadata.declaration, parameters)
    }
    
    /**
     * 获取 Skill 声明
     */
    fun getSkillDeclaration(skillId: String): JSONObject? {
        return registeredSkills[skillId]?.declaration
    }
    
    /**
     * 启用/禁用 Skill
     */
    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        registeredSkills[skillId]?.let {
            it.enabled = enabled
            Log.i(TAG, "Skill ${if (enabled) "启用" else "禁用"}: $skillId")
            
            // 同步到资产库
            val status = if (enabled) 
                IAgentAssetLibraryService.STATUS_OK 
            else 
                IAgentAssetLibraryService.STATUS_DISABLED
            
            assetLibrary.setAssetStatus(skillId, status)
        }
    }
    
    /**
     * 获取所有已注册 Skill 列表
     */
    fun listRegisteredSkills(): List<SkillInfo> {
        return registeredSkills.values.map { metadata ->
            SkillInfo(
                id = metadata.id,
                name = metadata.name,
                version = metadata.version,
                enabled = metadata.enabled,
                category = metadata.declaration.getJSONObject("skill").optString("category", "other"),
                description = metadata.declaration.getJSONObject("skill").optString("description", "")
            )
        }
    }
    
    /**
     * 从外部文件加载 Skill（用于开发/测试）
     */
    fun loadSkillFromFile(filePath: String): String? {
        try {
            val skillJson = File(filePath).readText()
            val skillDeclaration = JSONObject(skillJson)
            val skillId = skillDeclaration.getJSONObject("skill").getString("id")
            
            Log.d(TAG, "从文件加载 Skill: $filePath -> $skillId")
            
            // 注册逻辑同上
            loadAndRegisterSkillFromJson(skillJson)
            
            return skillId
            
        } catch (e: Exception) {
            Log.e(TAG, "从文件加载 Skill 失败: $filePath", e)
            return null
        }
    }
    
    private fun loadAndRegisterSkillFromJson(skillJson: String) {
        val skillDeclaration = JSONObject(skillJson)
        val skill = skillDeclaration.getJSONObject("skill")
        val metadata = skillDeclaration.getJSONObject("metadata")
        
        val skillId = skill.getString("id")
        val skillName = skill.getString("name")
        val skillVersion = skill.getString("version")
        
        // 构建 AssetManifest（逻辑同 loadAndRegisterSkill）
        val manifest = AssetManifest().apply {
            id = skillId
            kind = KIND_SKILL
            version = skillVersion
            name = skillName
            description = skill.optString("description", "")
            capability = metadata.getString("capability")
            riskLevel = metadata.getInt("riskLevel")
            confirmation = when (metadata.optString("confirmation", "default")) {
                "always" -> 1
                "never" -> 2
                else -> 0
            }
            executionMode = when (metadata.optString("executionMode", "semantic_direct")) {
                "macro_inject" -> EXECUTION_MODE_MACRO_INJECT
                else -> EXECUTION_MODE_SEMANTIC_DIRECT
            }
            
            if (skillDeclaration.has("parameters")) {
                inputSchemaJson = skillDeclaration.getJSONObject("parameters").toString()
            }
            if (skillDeclaration.has("returns")) {
                outputSchemaJson = skillDeclaration.getJSONObject("returns").toString()
            }
            
            executor = KIND_SKILL
            artifactType = "skill_bundle"
        }
        
        val assetIdOut = arrayOfNulls<String>(1)
        val result = assetLibrary.registerAsset(manifest, skillJson.toByteArray(), assetIdOut)
        
        if (result == IAgentAssetLibraryService.STATUS_OK) {
            registeredSkills[skillId] = SkillMetadata(
                id = skillId,
                name = skillName,
                version = skillVersion,
                declaration = skillDeclaration,
                enabled = true
            )
            Log.i(TAG, "✓ Skill 注册成功: $skillId")
        } else {
            Log.e(TAG, "✗ Skill 注册失败: $skillId, 错误码=$result")
        }
    }
}

/**
 * Skill 元数据
 */
data class SkillMetadata(
    val id: String,
    val name: String,
    val version: String,
    val declaration: JSONObject,
    var enabled: Boolean
)

/**
 * Skill 信息（对外展示）
 */
data class SkillInfo(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val category: String,
    val description: String
)
