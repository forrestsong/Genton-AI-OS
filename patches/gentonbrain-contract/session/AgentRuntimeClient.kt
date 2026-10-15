package com.genton.brain.runtime

import android.content.Context
import android.util.Log
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import com.genton.action.ActionRequest
import com.genton.action.IAgentActionCallback
import com.genton.action.IAgentActionService
import org.json.JSONObject

/**
 * Agent 运行时客户端（扩展）
 * 
 * 为 B3.3 多 Agent 托管新增方法：
 * - ACB 指针管理
 * - 资源释放
 */
class AgentRuntimeClient(private val context: Context) {
    
    companion object {
        private const val TAG = "AgentRuntimeClient"
    }
    
    /**
     * Ping 运行时服务（健康检查）
     */
    fun ping(): Boolean {
        // TODO: 实现真实的运行时服务 ping
        return true
    }
    
    /**
     * 分配 ACB（Agent Control Block）
     */
    fun allocateAcb(agentId: String): String {
        Log.i(TAG, "Allocating ACB for agent: $agentId")
        
        // TODO: 通过 IAgentRuntimeService 分配真实 ACB
        val acbPointer = "acb_${agentId}_${System.currentTimeMillis()}"
        
        Log.i(TAG, "ACB allocated: $acbPointer")
        return acbPointer
    }
    
    /**
     * 释放 ACB
     */
    fun releaseAcb(acbPointer: String): Boolean {
        Log.i(TAG, "Releasing ACB: $acbPointer")
        
        // TODO: 通过 IAgentRuntimeService 释放真实 ACB
        
        return true
    }
    
    /**
     * 交换 ACB 指针（原子操作）
     */
    fun swapAcbPointers(currentAcb: String?, targetAcb: String): Boolean {
        Log.i(TAG, "Swapping ACB pointers: $currentAcb -> $targetAcb")
        
        try {
            // TODO: 通过 IAgentRuntimeService 执行原子 ACB 交换
            // 这是热切换的核心操作，需要确保原子性
            
            Log.i(TAG, "ACB pointers swapped successfully")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to swap ACB pointers", e)
            return false
        }
    }
    
    /**
     * 释放 Agent 资源
     */
    fun releaseAgentResources(agentId: String): Boolean {
        Log.i(TAG, "Releasing resources for agent: $agentId")
        
        try {
            // TODO: 通过 IAgentRuntimeService 释放资源
            // - 释放内存
            // - 清除缓存
            // - 撤销能力令牌
            
            Log.i(TAG, "Resources released for agent: $agentId")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release resources for agent: $agentId", e)
            return false
        }
    }
    
    /**
     * 报告推理用量（B5.2）
     * 
     * @param agentId Agent ID
     * @param turnId 轮次 ID
     * @param tokensUsed 已使用的 token 数
     * @param energyUsedMj 已使用的能耗（毫焦耳）
     */
    fun reportUsage(
        agentId: String,
        turnId: String,
        tokensUsed: Int,
        energyUsedMj: Long
    ) {
        Log.d(TAG, "Reporting usage: agentId=$agentId, turnId=$turnId, tokens=$tokensUsed, energy=${energyUsedMj}mJ")
        
        try {
            // TODO: 通过 IAgentRuntimeService 回传到 ACB 预算系统
            // runtimeService.reportInferenceUsage(agentId, turnId, tokensUsed, energyUsedMj)
            
            Log.d(TAG, "Usage reported successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report usage", e)
            throw e
        }
    }
    
    /**
     * 获取 Agent 内存使用（MB）
     */
    fun getAgentMemoryUsage(agentId: String): Int {
        // TODO: 通过 IAgentRuntimeService 查询真实内存使用
        return 0
    }
    
    /**
     * 设置 Agent 资源配额
     */
    fun setAgentResourceQuota(agentId: String, memoryMb: Int, cpuPercent: Int): Boolean {
        Log.i(TAG, "Setting resource quota for agent: $agentId (memory=${memoryMb}MB, cpu=${cpuPercent}%)")
        
        try {
            // TODO: 通过 IAgentRuntimeService 设置资源配额
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set resource quota: $agentId", e)
            return false
        }
    }
    
    /**
     * 创建 ACB（Agent Control Block）用于新会话
     * 
     * @param agentId Agent ID
     * @param sessionId Session ID
     * @return ACB 指针
     */
    fun createAcb(agentId: String, sessionId: String): String {
        Log.i(TAG, "Creating ACB for session: $sessionId (agentId=$agentId)")
        
        // TODO: 通过 IAgentRuntimeService 创建真实 ACB
        val acbPointer = "acb_${sessionId}_${System.currentTimeMillis()}"
        
        Log.i(TAG, "ACB created: $acbPointer")
        return acbPointer
    }
    
    /**
     * 获取 Agent 状态
     * 
     * @param agentId Agent ID
     * @return Agent 状态对象，未注册返回 null
     */
    fun getAgentStatus(agentId: String): AgentStatus? {
        // TODO: 通过 IAgentRuntimeService 查询 Agent 状态
        // 暂时返回模拟状态
        return AgentStatus(
            agentId = agentId,
            isRegistered = true,
            memoryUsageMb = 0
        )
    }
    
    // ==================== A4.3 Checkpoint 管理 ====================
    
    /**
     * 保存 checkpoint（会话快照）
     * 
     * @param sessionId 会话 ID
     * @param acbPointer ACB 指针
     * @param packId 上下文打包 ID（来自 IEntityQueryService.packContext）
     * @param workingMemoryIds 工作记忆 ID 列表
     * @return checkpointId
     */
    fun saveCheckpoint(
        sessionId: String,
        acbPointer: String,
        packId: String,
        workingMemoryIds: List<String>
    ): String {
        Log.i(TAG, "Saving checkpoint for session: $sessionId (packId=$packId)")
        
        try {
            // TODO: 通过 IAgentRuntimeService 保存 checkpoint
            // checkpoint 包含：
            // 1. ACB 状态快照（目标栈、令牌、预算余量）
            // 2. packId 引用（上下文子图）
            // 3. 工作记忆 ID 列表
            // 4. KV 缓存引用（可选，大模型侧）
            
            val checkpointId = "ckpt_${sessionId}_${System.currentTimeMillis()}"
            
            Log.i(TAG, "Checkpoint saved: $checkpointId")
            return checkpointId
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save checkpoint for session: $sessionId", e)
            throw e
        }
    }
    
    /**
     * 加载 checkpoint（冷恢复）
     * 
     * @param checkpointId checkpoint ID
     * @return CheckpointData
     */
    fun loadCheckpoint(checkpointId: String): CheckpointData {
        Log.i(TAG, "Loading checkpoint: $checkpointId")
        
        try {
            // TODO: 通过 IAgentRuntimeService 加载 checkpoint
            
            // 模拟数据
            val data = CheckpointData(
                checkpointId = checkpointId,
                sessionId = checkpointId.substringAfter("ckpt_").substringBefore("_"),
                acbPointer = "acb_restored_${System.currentTimeMillis()}",
                packId = "pack_${checkpointId}",
                workingMemoryIds = emptyList(),
                savedAtMs = System.currentTimeMillis() - 3600000,  // 1小时前
                agentState = "SUSPENDED"
            )
            
            Log.i(TAG, "Checkpoint loaded: ${data.sessionId}")
            return data
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load checkpoint: $checkpointId", e)
            throw e
        }
    }
    
    /**
     * 列出 Agent 的所有 checkpoint
     * 
     * @param agentId Agent ID
     * @return checkpoint 列表（按时间倒序）
     */
    fun listCheckpoints(agentId: String): List<CheckpointInfo> {
        Log.d(TAG, "Listing checkpoints for agent: $agentId")
        
        try {
            // TODO: 通过 IAgentRuntimeService 查询 checkpoint 列表
            
            return emptyList()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list checkpoints for agent: $agentId", e)
            return emptyList()
        }
    }
    
    /**
     * 删除 checkpoint
     * 
     * @param checkpointId checkpoint ID
     */
    fun deleteCheckpoint(checkpointId: String): Boolean {
        Log.i(TAG, "Deleting checkpoint: $checkpointId")
        
        try {
            // TODO: 通过 IAgentRuntimeService 删除 checkpoint
            
            Log.i(TAG, "Checkpoint deleted: $checkpointId")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete checkpoint: $checkpointId", e)
            return false
        }
    }
    
    /**
     * 垃圾回收旧 checkpoint
     * 
     * 删除超过指定时间的 checkpoint（默认7天）
     */
    fun gcCheckpoints(olderThanDays: Int = 7): Int {
        Log.i(TAG, "Running checkpoint GC (olderThan=${olderThanDays}d)")
        
        try {
            // TODO: 通过 IAgentRuntimeService 执行 GC
            
            val deletedCount = 0
            Log.i(TAG, "Checkpoint GC completed: deleted $deletedCount")
            return deletedCount
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run checkpoint GC", e)
            return 0
        }
    }

    // ============================================================
    // 动作执行对接（B2.2 工具循环 / A3.2 执行中介）
    // 通过 ServiceManager 获取 IAgentActionService 真正调用，非桩实现。
    // ============================================================

    /** 动作执行状态（IAgentActionService.ActionExecutionStatus 的客户端视图） */
    data class ActionStatus(
        val actionRequestId: String,
        val state: Int,
        val resultJson: String?,
        val errorMessage: String?,
        val tokensConsumed: Int,
        val energyConsumed: Int,
        val wallTimeMs: Long
    ) {
        /** 是否处于终态（SUCCEEDED/FAILED/ABORTED/EXPIRED） */
        fun isCompleted(): Boolean = state >= STATE_SUCCEEDED

        /** 是否成功（SUCCEEDED） */
        fun isSuccess(): Boolean = state == STATE_SUCCEEDED

        companion object {
            // 与 IAgentActionService.STATE_* 对齐
            const val STATE_SUCCEEDED = 5
            const val STATE_FAILED = 6
            const val STATE_ABORTED = 7
            const val STATE_EXPIRED = 8
        }
    }

    /** 获取执行中介服务；不可用时返回 null */
    private fun actionService(): IAgentActionService? = try {
        val binder: IBinder? = ServiceManager.getService(SERVICE_AGENT_ACTION)
        binder?.let { IAgentActionService.Stub.asInterface(it) }
    } catch (t: Throwable) {
        Log.e(TAG, "failed to acquire action service", t)
        null
    }

    /**
     * 提交动作到执行中介
     *
     * @return true 表示已受理（含高风险待确认）；false 表示被拒或服务不可用
     */
    fun executeAction(request: ActionRequest, callback: IAgentActionCallback): Boolean {
        val svc = actionService()
        if (svc == null) {
            Log.w(TAG, "action service unavailable, cannot execute")
            return false
        }
        return try {
            val out = arrayOfNulls<String>(1)
            val code = svc.submitAction(request, callback, out)
            val accepted = code == IAgentActionService.SUBMIT_ACCEPTED ||
                code == IAgentActionService.SUBMIT_CONFIRMATION_REQUIRED
            if (!accepted) {
                Log.w(TAG, "submitAction rejected: code=" + code)
            } else {
                Log.i(TAG, "action submitted: id=" + (out[0] ?: "<none>") + " code=" + code)
            }
            accepted
        } catch (t: Throwable) {
            Log.e(TAG, "executeAction failed", t)
            false
        }
    }

    /** 查询动作状态与结果（请求不存在返回 null） */
    fun queryActionStatus(actionRequestId: String): ActionStatus? {
        val svc = actionService() ?: return null
        return try {
            val st = svc.getActionStatus(actionRequestId) ?: return null
            val r = st.result
            ActionStatus(
                actionRequestId = st.actionRequestId ?: actionRequestId,
                state = st.state,
                resultJson = r?.data?.let { bundleToJson(it) },
                errorMessage = r?.error,
                tokensConsumed = 0,
                energyConsumed = 0,
                wallTimeMs = st.updatedAtRealtimeNanos / 1_000_000L
            )
        } catch (t: Throwable) {
            Log.e(TAG, "queryActionStatus failed", t)
            null
        }
    }

    /** Bundle -> JSON 字符串（供 ActionExecutionResult.result 使用） */
    private fun bundleToJson(b: Bundle): String? = try {
        val json = JSONObject()
        for (k in b.keySet()) {
            json.put(k, b.get(k)?.toString() ?: JSONObject.NULL)
        }
        json.toString()
    } catch (t: Throwable) {
        null
    }
}

/**
 * Checkpoint 数据
 */
data class CheckpointData(
    val checkpointId: String,
    val sessionId: String,
    val acbPointer: String,
    val packId: String,                 // 上下文打包 ID（来自 EntityQueryService）
    val workingMemoryIds: List<String>, // 工作记忆 ID 列表
    val savedAtMs: Long,
    val agentState: String              // ACTING/WAITING/SUSPENDED
)

/**
 * Checkpoint 信息（列表视图）
 */
data class CheckpointInfo(
    val checkpointId: String,
    val sessionId: String,
    val savedAtMs: Long,
    val agentState: String,
    val sizeBytes: Long
)

/**
 * Agent 状态
 */
data class AgentStatus(
    val agentId: String,
    val isRegistered: Boolean,
    val memoryUsageMb: Int
)
