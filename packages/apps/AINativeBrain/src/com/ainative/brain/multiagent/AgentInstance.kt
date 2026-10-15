package com.ainative.brain.multiagent

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * Agent 实例
 * 
 * 表示一个 Agent 的运行时实例
 * 
 * B3.3 多 Agent 托管核心数据结构
 */
data class AgentInstance(
    val agentId: String,
    val displayName: String,
    val packageName: String,
    val type: AgentType,
    var state: AgentState,
    
    // 会话与执行状态
    var sessionId: String? = null,
    var acbPointer: String? = null,
    var currentTurnId: String? = null,
    
    // UI 状态
    var surfaceToken: String? = null,
    var surfaceSnapshot: Bitmap? = null,
    
    // Checkpoint 状态
    var checkpointId: String? = null,
    
    // 优先级与活跃度
    var priority: Int = 0,  // 优先级（0-100，数值越大优先级越高）
    var lastActiveAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    
    // 资源配额
    var memoryQuotaMb: Int = 512,  // 内存配额（MB）
    var tokenBudget: Int = 10000,  // Token 预算
    var tokenUsed: Int = 0,  // 已使用 Token
    
    // 隔离配置
    var isolationLevel: IsolationLevel = IsolationLevel.PROCESS,
    var storageIsolated: Boolean = true,
    
    // 元数据
    var metadata: MutableMap<String, Any> = mutableMapOf()
) {
    
    /**
     * 判断是否前台
     */
    fun isForeground(): Boolean = state == AgentState.FOREGROUND
    
    /**
     * 判断是否后台
     */
    fun isBackground(): Boolean = state == AgentState.BACKGROUND
    
    /**
     * 判断是否挂起
     */
    fun isSuspended(): Boolean = state == AgentState.SUSPENDED
    
    /**
     * 判断是否正在初始化
     */
    fun isInitializing(): Boolean = state == AgentState.INITIALIZING
    
    /**
     * 判断是否已销毁
     */
    fun isDestroyed(): Boolean = state == AgentState.DESTROYED
    
    /**
     * 更新活跃时间
     */
    fun updateActivity() {
        lastActiveAt = System.currentTimeMillis()
    }
    
    /**
     * 判断是否可以切换到前台
     */
    fun canSwitchToForeground(): Boolean {
        return state == AgentState.BACKGROUND || 
               state == AgentState.SUSPENDED ||
               state == AgentState.INITIALIZING
    }
    
    /**
     * 获取内存使用率
     */
    fun getMemoryUsagePercent(): Float {
        // TODO: 实现真实内存统计
        return 0.5f
    }
    
    /**
     * 获取 Token 使用率
     */
    fun getTokenUsagePercent(): Float {
        return if (tokenBudget > 0) {
            (tokenUsed.toFloat() / tokenBudget) * 100
        } else {
            0f
        }
    }
    
    /**
     * 判断是否超出资源配额
     */
    fun isOverQuota(): Boolean {
        return tokenUsed >= tokenBudget
    }
    
    /**
     * 序列化为 JSON
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("agentId", agentId)
            put("displayName", displayName)
            put("packageName", packageName)
            put("type", type.name)
            put("state", state.name)
            put("sessionId", sessionId)
            put("priority", priority)
            put("lastActiveAt", lastActiveAt)
            put("createdAt", createdAt)
            put("tokenUsed", tokenUsed)
            put("tokenBudget", tokenBudget)
            put("isolationLevel", isolationLevel.name)
        }
    }
    
    companion object {
        /**
         * 从 JSON 反序列化
         */
        fun fromJson(json: JSONObject): AgentInstance {
            return AgentInstance(
                agentId = json.getString("agentId"),
                displayName = json.getString("displayName"),
                packageName = json.getString("packageName"),
                type = AgentType.valueOf(json.getString("type")),
                state = AgentState.valueOf(json.getString("state")),
                sessionId = json.optString("sessionId", null),
                priority = json.optInt("priority", 0),
                lastActiveAt = json.optLong("lastActiveAt", System.currentTimeMillis()),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                tokenUsed = json.optInt("tokenUsed", 0),
                tokenBudget = json.optInt("tokenBudget", 10000),
                isolationLevel = IsolationLevel.valueOf(
                    json.optString("isolationLevel", IsolationLevel.PROCESS.name)
                )
            )
        }
    }
}

/**
 * 隔离级别
 */
enum class IsolationLevel {
    THREAD,   // 线程级隔离（最轻量）
    PROCESS,  // 进程级隔离（默认）
    SANDBOX   // 沙箱隔离（最严格）
}

/**
 * Agent 类型
 */
enum class AgentType {
    /** 系统默认 Agent */
    SYSTEM_DEFAULT,
    
    /** 用户安装的第三方 Agent */
    THIRD_PARTY,
    
    /** 专用任务 Agent（如日程、通讯） */
    SPECIALIZED
}

/**
 * Agent 状态
 */
enum class AgentState {
    /** 初始化中 */
    INITIALIZING,
    
    /** 前台运行 */
    FOREGROUND,
    
    /** 后台运行（保留内存） */
    BACKGROUND,
    
    /** 挂起（checkpoint 保存到存储） */
    SUSPENDED,
    
    /** 已销毁 */
    DESTROYED
}

/**
 * 资源配额
 */
data class ResourceQuota(
    /** 内存配额（MB） */
    var memoryMb: Int = 512,
    
    /** CPU 配额（百分比，0-100） */
    var cpuPercent: Int = 50,
    
    /** 推理 Token 配额 */
    var tokenQuota: Long = 10000,
    
    /** 已使用 Token */
    var tokenUsed: Long = 0
) {
    /**
     * 是否超出内存配额
     */
    fun isMemoryExceeded(currentMb: Int): Boolean = currentMb > memoryMb
    
    /**
     * 是否超出 Token 配额
     */
    fun isTokenExceeded(): Boolean = tokenUsed >= tokenQuota
    
    /**
     * 重置使用量
     */
    fun reset() {
        tokenUsed = 0
    }
}
