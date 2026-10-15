package com.ainative.brain.intent

import android.util.Log
import com.ainative.brain.BrainApplication
import org.json.JSONObject

/**
 * Turn 管理器 - 统一 Turn 入口
 * 
 * 职责：
 * 1. 管理所有 Turn 的生命周期
 * 2. 跟踪 Turn 状态（pending/planning/executing/completed/failed）
 * 3. 提供 Turn 查询和取消接口
 * 4. 记录 Turn 执行历史
 */
class TurnManager(private val application: BrainApplication) {
    
    companion object {
        private const val TAG = "TurnManager"
        private const val MAX_TURN_HISTORY = 100
    }
    
    // 活跃 Turn 表
    private val activeTurns = mutableMapOf<String, TurnInfo>()
    
    // Turn 历史记录（FIFO）
    private val turnHistory = mutableListOf<TurnInfo>()
    
    /**
     * 创建 Turn
     */
    fun createTurn(
        turnId: String,
        source: String,
        sourceData: Map<String, Any>
    ): TurnInfo {
        Log.i(TAG, "Creating turn: turnId=$turnId, source=$source")
        
        val turn = TurnInfo(
            turnId = turnId,
            source = source,
            sourceData = sourceData,
            state = TurnState.PENDING,
            createdAt = System.currentTimeMillis()
        )
        
        activeTurns[turnId] = turn
        
        Log.i(TAG, "Turn created: $turnId")
        return turn
    }
    
    /**
     * 更新 Turn 状态
     */
    fun updateTurnState(
        turnId: String,
        newState: TurnState,
        stateDetail: String? = null
    ): Boolean {
        Log.i(TAG, "Updating turn state: turnId=$turnId, newState=$newState")
        
        val turn = activeTurns[turnId]
        if (turn == null) {
            Log.w(TAG, "Turn not found: $turnId")
            return false
        }
        
        // 记录状态转换
        turn.stateHistory.add(
            StateTransition(
                fromState = turn.state,
                toState = newState,
                timestamp = System.currentTimeMillis(),
                detail = stateDetail
            )
        )
        
        // 更新状态
        turn.state = newState
        turn.lastUpdateAt = System.currentTimeMillis()
        
        // 如果是终态，移到历史记录
        if (newState == TurnState.COMPLETED || newState == TurnState.FAILED || newState == TurnState.CANCELLED) {
            archiveTurn(turn)
        }
        
        Log.i(TAG, "Turn state updated: $turnId -> $newState")
        return true
    }
    
    /**
     * 获取 Turn 信息
     */
    fun getTurn(turnId: String): TurnInfo? {
        return activeTurns[turnId] ?: turnHistory.find { it.turnId == turnId }
    }
    
    /**
     * 取消 Turn
     */
    fun cancelTurn(turnId: String, reason: String): Boolean {
        Log.i(TAG, "Cancelling turn: turnId=$turnId, reason=$reason")
        
        val turn = activeTurns[turnId]
        if (turn == null) {
            Log.w(TAG, "Turn not found: $turnId")
            return false
        }
        
        // 只能取消未完成的 Turn
        if (turn.state == TurnState.COMPLETED || turn.state == TurnState.FAILED || turn.state == TurnState.CANCELLED) {
            Log.w(TAG, "Turn already in terminal state: $turnId (${turn.state})")
            return false
        }
        
        // TODO: 通知规划器/执行器取消
        // planner.cancelTurn(turnId)
        
        // 更新状态
        updateTurnState(turnId, TurnState.CANCELLED, reason)
        
        Log.i(TAG, "Turn cancelled: $turnId")
        return true
    }
    
    /**
     * 获取活跃 Turn 列表
     */
    fun getActiveTurns(): List<TurnInfo> {
        return activeTurns.values.toList()
    }
    
    /**
     * 获取 Turn 历史
     */
    fun getTurnHistory(limit: Int = 10): List<TurnInfo> {
        return turnHistory.takeLast(limit).reversed()
    }
    
    /**
     * 归档 Turn 到历史记录
     */
    private fun archiveTurn(turn: TurnInfo) {
        Log.d(TAG, "Archiving turn: ${turn.turnId}")
        
        // 从活跃表移除
        activeTurns.remove(turn.turnId)
        
        // 添加到历史记录
        turnHistory.add(turn)
        
        // 限制历史记录大小
        if (turnHistory.size > MAX_TURN_HISTORY) {
            val removed = turnHistory.removeAt(0)
            Log.d(TAG, "Removed old turn from history: ${removed.turnId}")
        }
    }
    
    /**
     * 清理过期 Turn
     */
    fun cleanupStaleTurns() {
        val now = System.currentTimeMillis()
        val staleThreshold = 10 * 60 * 1000L // 10 分钟
        
        val staleTurns = activeTurns.values.filter { turn ->
            now - turn.lastUpdateAt > staleThreshold &&
            turn.state != TurnState.COMPLETED &&
            turn.state != TurnState.FAILED &&
            turn.state != TurnState.CANCELLED
        }
        
        if (staleTurns.isEmpty()) {
            return
        }
        
        Log.w(TAG, "Found ${staleTurns.size} stale turns, cleaning up...")
        
        for (turn in staleTurns) {
            updateTurnState(
                turn.turnId,
                TurnState.FAILED,
                "Turn timeout after ${(now - turn.lastUpdateAt) / 1000}s"
            )
        }
    }
    
    /**
     * Turn 信息
     */
    data class TurnInfo(
        val turnId: String,
        val source: String,
        val sourceData: Map<String, Any>,
        var state: TurnState,
        val createdAt: Long,
        var lastUpdateAt: Long = createdAt,
        val stateHistory: MutableList<StateTransition> = mutableListOf()
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("turn_id", turnId)
            json.put("source", source)
            json.put("source_data", JSONObject(sourceData))
            json.put("state", state.name)
            json.put("created_at", createdAt)
            json.put("last_update_at", lastUpdateAt)
            
            val historyArray = org.json.JSONArray()
            for (transition in stateHistory) {
                val transitionJson = JSONObject()
                transitionJson.put("from_state", transition.fromState.name)
                transitionJson.put("to_state", transition.toState.name)
                transitionJson.put("timestamp", transition.timestamp)
                transitionJson.put("detail", transition.detail)
                historyArray.put(transitionJson)
            }
            json.put("state_history", historyArray)
            
            return json.toString(2)
        }
    }
    
    /**
     * 状态转换记录
     */
    data class StateTransition(
        val fromState: TurnState,
        val toState: TurnState,
        val timestamp: Long,
        val detail: String?
    )
    
    /**
     * Turn 状态
     */
    enum class TurnState {
        PENDING,      // 待处理
        PLANNING,     // 规划中
        EXECUTING,    // 执行中
        COMPLETED,    // 已完成
        FAILED,       // 失败
        CANCELLED     // 已取消
    }
}
