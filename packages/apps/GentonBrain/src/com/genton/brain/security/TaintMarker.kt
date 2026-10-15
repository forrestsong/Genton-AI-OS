package com.genton.brain.security

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Taint 打标工具
 * 
 * 职责：
 * 1. 为 ActionRequest 生成 taintedArgumentKeys
 * 2. 将 Taint 信息序列化为 AIDL 格式
 * 3. 提供 Taint 验证辅助方法
 */
object TaintMarker {
    
    private const val TAG = "TaintMarker"
    
    /**
     * 为 ActionRequest 生成 taintedArgumentKeys
     * 
     * @param taintMap Taint 映射表
     * @return JSON 格式的 taintedArgumentKeys 字符串
     */
    fun generateTaintedArgumentKeys(
        taintMap: Map<String, SecurityInspector.TaintEntry>
    ): String {
        if (taintMap.isEmpty()) {
            return "[]"
        }
        
        val jsonArray = JSONArray()
        
        for ((key, taintEntry) in taintMap) {
            val entryJson = JSONObject()
            entryJson.put("argument_key", taintEntry.argumentKey)
            entryJson.put("source_level", taintEntry.sourceLevel)
            entryJson.put("source_trust_level", taintEntry.sourceTrustLevel.name)
            entryJson.put("source_description", taintEntry.sourceDescription)
            jsonArray.put(entryJson)
        }
        
        val result = jsonArray.toString(2)
        Log.d(TAG, "Generated taintedArgumentKeys: ${taintMap.size} entries")
        
        return result
    }
    
    /**
     * 生成 AIDL 格式的 TaintMapEntry 数组
     * 
     * 用于传递给 ActionExecutionService
     */
    fun generateAidlTaintMap(
        taintMap: Map<String, SecurityInspector.TaintEntry>
    ): List<Map<String, Any>> {
        val aidlEntries = mutableListOf<Map<String, Any>>()
        
        for ((key, taintEntry) in taintMap) {
            aidlEntries.add(
                mapOf(
                    "argumentKey" to taintEntry.argumentKey,
                    "sourceLevel" to taintEntry.sourceLevel
                )
            )
        }
        
        Log.d(TAG, "Generated ${aidlEntries.size} AIDL taint entries")
        return aidlEntries
    }
    
    /**
     * 验证参数的 Taint 等级是否满足要求
     * 
     * @param paramKey 参数名
     * @param taintMap Taint 映射表
     * @param maxAllowedLevel 允许的最大 Taint 等级
     * @return true 如果通过验证
     */
    fun validateTaintLevel(
        paramKey: String,
        taintMap: Map<String, SecurityInspector.TaintEntry>,
        maxAllowedLevel: SecurityInspector.TrustLevel
    ): Boolean {
        val taintEntry = taintMap[paramKey] ?: return true  // 没有 taint 信息，放行
        
        val passed = taintEntry.sourceLevel <= maxAllowedLevel.level
        
        if (!passed) {
            Log.w(
                TAG,
                "Taint validation failed: $paramKey has level ${taintEntry.sourceLevel} " +
                        "(${taintEntry.sourceTrustLevel}), but max allowed is ${maxAllowedLevel.level} ($maxAllowedLevel)"
            )
        }
        
        return passed
    }
    
    /**
     * 获取参数的 TrustLevel
     */
    fun getTrustLevel(
        paramKey: String,
        taintMap: Map<String, SecurityInspector.TaintEntry>
    ): SecurityInspector.TrustLevel {
        return taintMap[paramKey]?.sourceTrustLevel ?: SecurityInspector.TrustLevel.AGENT_DERIVED
    }
    
    /**
     * 生成 Taint 摘要（用于日志和审计）
     */
    fun generateTaintSummary(
        taintMap: Map<String, SecurityInspector.TaintEntry>
    ): String {
        if (taintMap.isEmpty()) {
            return "No taint information"
        }
        
        val summary = StringBuilder()
        summary.append("Taint Summary (${taintMap.size} parameters):\n")
        
        // 按 TrustLevel 分组
        val grouped = taintMap.values.groupBy { it.sourceTrustLevel }
        
        for (trustLevel in SecurityInspector.TrustLevel.values()) {
            val entries = grouped[trustLevel] ?: continue
            summary.append("  ${trustLevel.name} (${entries.size}):\n")
            for (entry in entries) {
                summary.append("    - ${entry.argumentKey}: ${entry.sourceDescription}\n")
            }
        }
        
        return summary.toString()
    }
    
    /**
     * 解析 taintedArgumentKeys JSON 字符串
     */
    fun parseTaintedArgumentKeys(
        jsonStr: String
    ): Map<String, SecurityInspector.TaintEntry> {
        if (jsonStr.isEmpty() || jsonStr == "[]") {
            return emptyMap()
        }
        
        try {
            val taintMap = mutableMapOf<String, SecurityInspector.TaintEntry>()
            val jsonArray = JSONArray(jsonStr)
            
            for (i in 0 until jsonArray.length()) {
                val entryJson = jsonArray.getJSONObject(i)
                val key = entryJson.getString("argument_key")
                val level = entryJson.getInt("source_level")
                val trustLevelName = entryJson.getString("source_trust_level")
                val description = entryJson.getString("source_description")
                
                val trustLevel = SecurityInspector.TrustLevel.valueOf(trustLevelName)
                
                taintMap[key] = SecurityInspector.TaintEntry(
                    argumentKey = key,
                    sourceLevel = level,
                    sourceTrustLevel = trustLevel,
                    sourceDescription = description
                )
            }
            
            Log.d(TAG, "Parsed ${taintMap.size} taint entries from JSON")
            return taintMap
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse taintedArgumentKeys JSON", e)
            return emptyMap()
        }
    }
}
