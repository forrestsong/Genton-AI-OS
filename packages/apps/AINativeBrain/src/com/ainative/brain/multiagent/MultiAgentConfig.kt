package com.ainative.brain.multiagent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * 多 Agent 配置管理器
 * 
 * 职责：
 * 1. 管理多 Agent 功能开关
 * 2. 配置资源配额策略
 * 3. 配置切换策略（热/冷切换阈值）
 * 4. 持久化配置
 * 
 * B3.3 多 Agent 托管 - 配置管理
 */
class MultiAgentConfig(private val context: Context) {
    
    companion object {
        private const val TAG = "MultiAgentConfig"
        private const val PREFS_NAME = "multi_agent_config"
        
        // 配置键
        private const val KEY_MULTI_AGENT_ENABLED = "multi_agent_enabled"
        private const val KEY_MAX_INSTANCES = "max_instances"
        private const val KEY_MAX_BACKGROUND_AGENTS = "max_background_agents"
        private const val KEY_HOT_SWITCH_THRESHOLD_MS = "hot_switch_threshold_ms"
        private const val KEY_COLD_SWITCH_THRESHOLD_MS = "cold_switch_threshold_ms"
        private const val KEY_AUTO_SUSPEND_IDLE_MIN = "auto_suspend_idle_min"
        private const val KEY_DEFAULT_MEMORY_QUOTA_MB = "default_memory_quota_mb"
        private const val KEY_DEFAULT_TOKEN_BUDGET = "default_token_budget"
        
        // 默认值
        const val DEFAULT_MAX_INSTANCES = 5
        const val DEFAULT_MAX_BACKGROUND_AGENTS = 2
        const val DEFAULT_HOT_SWITCH_THRESHOLD_MS = 300L
        const val DEFAULT_COLD_SWITCH_THRESHOLD_MS = 1000L
        const val DEFAULT_AUTO_SUSPEND_IDLE_MIN = 30
        const val DEFAULT_MEMORY_QUOTA_MB = 512
        const val DEFAULT_TOKEN_BUDGET = 10000
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 多 Agent 功能是否启用
     */
    var multiAgentEnabled: Boolean
        get() = prefs.getBoolean(KEY_MULTI_AGENT_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MULTI_AGENT_ENABLED, value).apply()
            Log.i(TAG, "Multi-agent enabled: $value")
        }
    
    /**
     * 最大 Agent 实例数
     */
    var maxInstances: Int
        get() = prefs.getInt(KEY_MAX_INSTANCES, DEFAULT_MAX_INSTANCES)
        set(value) {
            prefs.edit().putInt(KEY_MAX_INSTANCES, value).apply()
            Log.i(TAG, "Max instances: $value")
        }
    
    /**
     * 最大后台 Agent 数
     */
    var maxBackgroundAgents: Int
        get() = prefs.getInt(KEY_MAX_BACKGROUND_AGENTS, DEFAULT_MAX_BACKGROUND_AGENTS)
        set(value) {
            prefs.edit().putInt(KEY_MAX_BACKGROUND_AGENTS, value).apply()
            Log.i(TAG, "Max background agents: $value")
        }
    
    /**
     * 热切换延迟阈值（ms）
     */
    var hotSwitchThresholdMs: Long
        get() = prefs.getLong(KEY_HOT_SWITCH_THRESHOLD_MS, DEFAULT_HOT_SWITCH_THRESHOLD_MS)
        set(value) {
            prefs.edit().putLong(KEY_HOT_SWITCH_THRESHOLD_MS, value).apply()
            Log.i(TAG, "Hot switch threshold: ${value}ms")
        }
    
    /**
     * 冷切换延迟阈值（ms）
     */
    var coldSwitchThresholdMs: Long
        get() = prefs.getLong(KEY_COLD_SWITCH_THRESHOLD_MS, DEFAULT_COLD_SWITCH_THRESHOLD_MS)
        set(value) {
            prefs.edit().putLong(KEY_COLD_SWITCH_THRESHOLD_MS, value).apply()
            Log.i(TAG, "Cold switch threshold: ${value}ms")
        }
    
    /**
     * 自动挂起空闲时间（分钟）
     */
    var autoSuspendIdleMin: Int
        get() = prefs.getInt(KEY_AUTO_SUSPEND_IDLE_MIN, DEFAULT_AUTO_SUSPEND_IDLE_MIN)
        set(value) {
            prefs.edit().putInt(KEY_AUTO_SUSPEND_IDLE_MIN, value).apply()
            Log.i(TAG, "Auto suspend idle: ${value}min")
        }
    
    /**
     * 默认内存配额（MB）
     */
    var defaultMemoryQuotaMb: Int
        get() = prefs.getInt(KEY_DEFAULT_MEMORY_QUOTA_MB, DEFAULT_MEMORY_QUOTA_MB)
        set(value) {
            prefs.edit().putInt(KEY_DEFAULT_MEMORY_QUOTA_MB, value).apply()
            Log.i(TAG, "Default memory quota: ${value}MB")
        }
    
    /**
     * 默认 Token 预算
     */
    var defaultTokenBudget: Int
        get() = prefs.getInt(KEY_DEFAULT_TOKEN_BUDGET, DEFAULT_TOKEN_BUDGET)
        set(value) {
            prefs.edit().putInt(KEY_DEFAULT_TOKEN_BUDGET, value).apply()
            Log.i(TAG, "Default token budget: $value")
        }
    
    /**
     * 重置为默认配置
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Config reset to defaults")
    }
    
    /**
     * 导出配置为 JSON
     */
    fun exportToJson(): JSONObject {
        return JSONObject().apply {
            put(KEY_MULTI_AGENT_ENABLED, multiAgentEnabled)
            put(KEY_MAX_INSTANCES, maxInstances)
            put(KEY_MAX_BACKGROUND_AGENTS, maxBackgroundAgents)
            put(KEY_HOT_SWITCH_THRESHOLD_MS, hotSwitchThresholdMs)
            put(KEY_COLD_SWITCH_THRESHOLD_MS, coldSwitchThresholdMs)
            put(KEY_AUTO_SUSPEND_IDLE_MIN, autoSuspendIdleMin)
            put(KEY_DEFAULT_MEMORY_QUOTA_MB, defaultMemoryQuotaMb)
            put(KEY_DEFAULT_TOKEN_BUDGET, defaultTokenBudget)
        }
    }
    
    /**
     * 从 JSON 导入配置
     */
    fun importFromJson(json: JSONObject) {
        multiAgentEnabled = json.optBoolean(KEY_MULTI_AGENT_ENABLED, true)
        maxInstances = json.optInt(KEY_MAX_INSTANCES, DEFAULT_MAX_INSTANCES)
        maxBackgroundAgents = json.optInt(KEY_MAX_BACKGROUND_AGENTS, DEFAULT_MAX_BACKGROUND_AGENTS)
        hotSwitchThresholdMs = json.optLong(KEY_HOT_SWITCH_THRESHOLD_MS, DEFAULT_HOT_SWITCH_THRESHOLD_MS)
        coldSwitchThresholdMs = json.optLong(KEY_COLD_SWITCH_THRESHOLD_MS, DEFAULT_COLD_SWITCH_THRESHOLD_MS)
        autoSuspendIdleMin = json.optInt(KEY_AUTO_SUSPEND_IDLE_MIN, DEFAULT_AUTO_SUSPEND_IDLE_MIN)
        defaultMemoryQuotaMb = json.optInt(KEY_DEFAULT_MEMORY_QUOTA_MB, DEFAULT_MEMORY_QUOTA_MB)
        defaultTokenBudget = json.optInt(KEY_DEFAULT_TOKEN_BUDGET, DEFAULT_TOKEN_BUDGET)
        
        Log.i(TAG, "Config imported from JSON")
    }
    
    /**
     * 验证配置有效性
     */
    fun validate(): Boolean {
        if (maxInstances < 1 || maxInstances > 20) {
            Log.e(TAG, "Invalid maxInstances: $maxInstances")
            return false
        }
        
        if (maxBackgroundAgents < 0 || maxBackgroundAgents >= maxInstances) {
            Log.e(TAG, "Invalid maxBackgroundAgents: $maxBackgroundAgents")
            return false
        }
        
        if (hotSwitchThresholdMs < 100 || hotSwitchThresholdMs > 5000) {
            Log.e(TAG, "Invalid hotSwitchThresholdMs: $hotSwitchThresholdMs")
            return false
        }
        
        if (coldSwitchThresholdMs < 500 || coldSwitchThresholdMs > 10000) {
            Log.e(TAG, "Invalid coldSwitchThresholdMs: $coldSwitchThresholdMs")
            return false
        }
        
        return true
    }
    
    /**
     * 打印当前配置
     */
    fun logCurrentConfig() {
        Log.i(TAG, """
            === Multi-Agent Configuration ===
            Enabled: $multiAgentEnabled
            Max Instances: $maxInstances
            Max Background Agents: $maxBackgroundAgents
            Hot Switch Threshold: ${hotSwitchThresholdMs}ms
            Cold Switch Threshold: ${coldSwitchThresholdMs}ms
            Auto Suspend Idle: ${autoSuspendIdleMin}min
            Default Memory Quota: ${defaultMemoryQuotaMb}MB
            Default Token Budget: $defaultTokenBudget
            =================================
        """.trimIndent())
    }
}
