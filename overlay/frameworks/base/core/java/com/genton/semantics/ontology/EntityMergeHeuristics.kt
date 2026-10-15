// EntityMergeHeuristics.kt
// C3.1 本体模型 v1 schema：EntityID 合并消歧启发式
package com.genton.semantics.ontology

import android.util.Log
import org.json.JSONObject

/**
 * 实体合并启发式算法
 * 
 * 核心职责：
 * 1. 检测重复实体（同一现实对象的多个表示）
 * 2. 基于规则的合并决策（phone/email/name 相似度）
 * 3. 生成合并记录支持回滚
 * 
 * 设计原则：
 * - 高置信度启发式：phone/email 完全匹配 → 合并
 * - 中等置信度：name 高度相似 + 其他属性匹配 → 建议合并（需用户确认）
 * - 低置信度：仅 name 相似 → 不合并
 */
object EntityMergeHeuristics {
    
    private const val TAG = "EntityMergeHeuristics"
    
    // 相似度阈值
    private const val NAME_SIMILARITY_THRESHOLD = 0.85f  // 名称相似度阈值
    private const val HIGH_CONFIDENCE_THRESHOLD = 0.95f  // 高置信度阈值（自动合并）
    
    /**
     * 合并决策结果
     */
    data class MergeDecision(
        /**
         * 是否应该合并
         */
        val shouldMerge: Boolean,
        
        /**
         * 合并置信度（0.0 - 1.0）
         */
        val confidence: Float,
        
        /**
         * 合并原因
         */
        val reason: String,
        
        /**
         * 是否需要用户确认（中等置信度时）
         */
        val requiresConfirmation: Boolean
    )
    
    /**
     * 判断两个实体是否应该合并
     * 
     * @param entity1 实体 1
     * @param entity2 实体 2
     * @return 合并决策
     */
    fun shouldMerge(entity1: Entity, entity2: Entity): MergeDecision {
        // 1. 类型不同，不合并
        if (entity1.type != entity2.type) {
            return MergeDecision(
                shouldMerge = false,
                confidence = 0f,
                reason = "type_mismatch",
                requiresConfirmation = false
            )
        }
        
        // 2. 解析属性
        val attrs1 = parseAttributes(entity1.attributes)
        val attrs2 = parseAttributes(entity2.attributes)
        
        // 3. 基于类型的启发式规则
        return when (entity1.type) {
            EntityType.PERSON -> mergePersonHeuristic(entity1, entity2, attrs1, attrs2)
            EntityType.LOCATION -> mergeLocationHeuristic(entity1, entity2, attrs1, attrs2)
            EntityType.EVENT -> mergeEventHeuristic(entity1, entity2, attrs1, attrs2)
            else -> mergeGenericHeuristic(entity1, entity2, attrs1, attrs2)
        }
    }
    
    /**
     * 人（PERSON）合并启发式
     * 
     * 规则：
     * - phone 完全匹配 → 高置信度合并
     * - email 完全匹配 → 高置信度合并
     * - name 高度相似 + (phone 或 email 部分匹配) → 中等置信度，需确认
     * - 仅 name 相似 → 不合并
     */
    private fun mergePersonHeuristic(
        entity1: Entity,
        entity2: Entity,
        attrs1: Map<String, String>,
        attrs2: Map<String, String>
    ): MergeDecision {
        val phone1 = attrs1["phone"]?.normalizePhone()
        val phone2 = attrs2["phone"]?.normalizePhone()
        val email1 = attrs1["email"]?.toLowerCase()
        val email2 = attrs2["email"]?.toLowerCase()
        val name1 = entity1.displayName
        val name2 = entity2.displayName
        
        // phone 完全匹配
        if (phone1 != null && phone2 != null && phone1 == phone2) {
            return MergeDecision(
                shouldMerge = true,
                confidence = 0.98f,
                reason = "same_phone",
                requiresConfirmation = false
            )
        }
        
        // email 完全匹配
        if (email1 != null && email2 != null && email1 == email2) {
            return MergeDecision(
                shouldMerge = true,
                confidence = 0.98f,
                reason = "same_email",
                requiresConfirmation = false
            )
        }
        
        // name 高度相似 + phone 或 email 部分匹配
        val nameSimilarity = calculateNameSimilarity(name1, name2)
        if (nameSimilarity >= NAME_SIMILARITY_THRESHOLD) {
            val hasPartialMatch = (phone1 != null && phone2 != null && phone1.takeLast(4) == phone2.takeLast(4)) ||
                                   (email1 != null && email2 != null && email1.substringBefore("@") == email2.substringBefore("@"))
            
            if (hasPartialMatch) {
                return MergeDecision(
                    shouldMerge = true,
                    confidence = 0.75f,
                    reason = "name_similarity_with_partial_match",
                    requiresConfirmation = true  // 需要用户确认
                )
            }
        }
        
        // 不合并
        return MergeDecision(
            shouldMerge = false,
            confidence = nameSimilarity,
            reason = "insufficient_match",
            requiresConfirmation = false
        )
    }
    
    /**
     * 地点（LOCATION）合并启发式
     * 
     * 规则：
     * - 经纬度接近（< 100m）→ 高置信度合并
     * - 地址完全匹配 → 高置信度合并
     * - name 相似 + 地址部分匹配 → 中等置信度，需确认
     */
    private fun mergeLocationHeuristic(
        entity1: Entity,
        entity2: Entity,
        attrs1: Map<String, String>,
        attrs2: Map<String, String>
    ): MergeDecision {
        val lat1 = attrs1["latitude"]?.toFloatOrNull()
        val lon1 = attrs1["longitude"]?.toFloatOrNull()
        val lat2 = attrs2["latitude"]?.toFloatOrNull()
        val lon2 = attrs2["longitude"]?.toFloatOrNull()
        val addr1 = attrs1["address"]
        val addr2 = attrs2["address"]
        
        // 经纬度接近（< 100m）
        if (lat1 != null && lon1 != null && lat2 != null && lon2 != null) {
            val distance = calculateDistance(lat1, lon1, lat2, lon2)
            if (distance < 100f) {  // 100 米以内
                return MergeDecision(
                    shouldMerge = true,
                    confidence = 0.95f,
                    reason = "nearby_location",
                    requiresConfirmation = false
                )
            }
        }
        
        // 地址完全匹配
        if (addr1 != null && addr2 != null && addr1 == addr2) {
            return MergeDecision(
                shouldMerge = true,
                confidence = 0.98f,
                reason = "same_address",
                requiresConfirmation = false
            )
        }
        
        // 不合并
        return MergeDecision(
            shouldMerge = false,
            confidence = 0f,
            reason = "insufficient_match",
            requiresConfirmation = false
        )
    }
    
    /**
     * 事件（EVENT）合并启发式
     * 
     * 规则：
     * - 时间完全匹配 + 标题相似 → 高置信度合并
     * - 时间接近（±1h）+ 标题相似 → 中等置信度，需确认
     */
    private fun mergeEventHeuristic(
        entity1: Entity,
        entity2: Entity,
        attrs1: Map<String, String>,
        attrs2: Map<String, String>
    ): MergeDecision {
        val time1 = attrs1["start_time"]?.toLongOrNull()
        val time2 = attrs2["start_time"]?.toLongOrNull()
        val title1 = entity1.displayName
        val title2 = entity2.displayName
        
        if (time1 != null && time2 != null) {
            val timeDiff = Math.abs(time1 - time2)
            val titleSimilarity = calculateNameSimilarity(title1, title2)
            
            // 时间完全匹配（±5 分钟）+ 标题相似
            if (timeDiff < 5 * 60 * 1000 && titleSimilarity >= NAME_SIMILARITY_THRESHOLD) {
                return MergeDecision(
                    shouldMerge = true,
                    confidence = 0.95f,
                    reason = "same_time_and_title",
                    requiresConfirmation = false
                )
            }
            
            // 时间接近（±1h）+ 标题相似
            if (timeDiff < 60 * 60 * 1000 && titleSimilarity >= NAME_SIMILARITY_THRESHOLD) {
                return MergeDecision(
                    shouldMerge = true,
                    confidence = 0.75f,
                    reason = "nearby_time_and_similar_title",
                    requiresConfirmation = true
                )
            }
        }
        
        return MergeDecision(
            shouldMerge = false,
            confidence = 0f,
            reason = "insufficient_match",
            requiresConfirmation = false
        )
    }
    
    /**
     * 通用合并启发式（兜底）
     */
    private fun mergeGenericHeuristic(
        entity1: Entity,
        entity2: Entity,
        attrs1: Map<String, String>,
        attrs2: Map<String, String>
    ): MergeDecision {
        val nameSimilarity = calculateNameSimilarity(entity1.displayName, entity2.displayName)
        
        // 名称高度相似
        if (nameSimilarity >= 0.95f) {
            return MergeDecision(
                shouldMerge = true,
                confidence = nameSimilarity,
                reason = "name_exact_match",
                requiresConfirmation = false
            )
        }
        
        return MergeDecision(
            shouldMerge = false,
            confidence = nameSimilarity,
            reason = "insufficient_match",
            requiresConfirmation = false
        )
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 解析属性 JSON
     */
    private fun parseAttributes(attributesJson: String): Map<String, String> {
        return try {
            val json = JSONObject(attributesJson)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.optString(key, "")
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse attributes: $attributesJson", e)
            emptyMap()
        }
    }
    
    /**
     * 规范化电话号码（去除空格、横杠、括号）
     */
    private fun String.normalizePhone(): String {
        return this.replace(Regex("[\\s\\-()]+"), "")
    }
    
    /**
     * 计算名称相似度（Levenshtein 距离）
     */
    private fun calculateNameSimilarity(name1: String, name2: String): Float {
        if (name1 == name2) return 1.0f
        if (name1.isEmpty() || name2.isEmpty()) return 0.0f
        
        val maxLen = maxOf(name1.length, name2.length)
        val distance = levenshteinDistance(name1.toLowerCase(), name2.toLowerCase())
        
        return 1.0f - (distance.toFloat() / maxLen)
    }
    
    /**
     * Levenshtein 距离（编辑距离）
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // 删除
                    dp[i][j - 1] + 1,      // 插入
                    dp[i - 1][j - 1] + cost // 替换
                )
            }
        }
        
        return dp[s1.length][s2.length]
    }
    
    /**
     * 计算两点距离（Haversine 公式，单位：米）
     */
    private fun calculateDistance(lat1: Float, lon1: Float, lat2: Float, lon2: Float): Float {
        val R = 6371000f  // 地球半径（米）
        val dLat = Math.toRadians((lat2 - lat1).toDouble())
        val dLon = Math.toRadians((lon2 - lon1).toDouble())
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1.toDouble())) * Math.cos(Math.toRadians(lat2.toDouble())) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return (R * c).toFloat()
    }
}
