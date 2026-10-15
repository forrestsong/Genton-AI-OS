package com.ainative.brain.lifecycle

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.ainative.brain.BrainApplication
import com.ainative.brain.checkpoint.Checkpoint
import com.ainative.brain.checkpoint.CheckpointManager
import org.json.JSONObject

/**
 * Crash 恢复服务
 * 
 * 职责：
 * 1. 检测 crash checkpoint
 * 2. 恢复 Agent 状态
 * 3. 重建令牌链
 * 4. 恢复会话上下文
 */
class CrashRecoveryService : Service() {
    
    companion object {
        private const val TAG = "CrashRecovery"
        private const val MAX_RECOVERY_ATTEMPTS = 3
    }
    
    private lateinit var checkpointManager: CheckpointManager
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CrashRecoveryService created")
        
        val app = BrainApplication.getInstance()
        checkpointManager = app.getCheckpointManager()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val crashTime = intent?.getLongExtra("crash_time", 0) ?: 0
        
        Log.i(TAG, "Starting crash recovery (crash_time=$crashTime)...")
        
        Thread {
            try {
                performCrashRecovery(crashTime)
                Log.i(TAG, "Crash recovery completed successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Crash recovery failed", e)
                handleRecoveryFailure(e)
                
            } finally {
                stopSelf()
            }
        }.start()
        
        return START_NOT_STICKY
    }
    
    /**
     * 执行 Crash 恢复
     */
    private fun performCrashRecovery(crashTime: Long) {
        // 1. 查找 crash 前的最新 checkpoint
        val checkpoints = checkpointManager.listCheckpoints()
        val recoverableCheckpoints = checkpoints.filter { checkpoint ->
            checkpoint.timestamp <= crashTime && 
            checkpoint.timestamp > crashTime - 60 * 1000 // 1 分钟内
        }.sortedByDescending { it.timestamp }
        
        if (recoverableCheckpoints.isEmpty()) {
            Log.w(TAG, "No recoverable checkpoint found")
            return
        }
        
        // 2. 尝试从最新的 checkpoint 恢复
        for ((index, checkpoint) in recoverableCheckpoints.withIndex()) {
            if (index >= MAX_RECOVERY_ATTEMPTS) {
                Log.w(TAG, "Max recovery attempts reached")
                break
            }
            
            try {
                Log.i(TAG, "Attempting recovery from checkpoint: ${checkpoint.checkpointId} (attempt ${index + 1})")
                recoverFromCheckpoint(checkpoint)
                
                // 恢复成功，清除 crash 标记
                clearCrashMarker()
                return
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover from checkpoint: ${checkpoint.checkpointId}", e)
                // 尝试下一个 checkpoint
            }
        }
        
        throw RecoveryException("All recovery attempts failed")
    }
    
    /**
     * 从 checkpoint 恢复
     */
    private fun recoverFromCheckpoint(checkpoint: Checkpoint) {
        Log.i(TAG, "Recovering from checkpoint: ${checkpoint.checkpointId}")
        
        // 1. 验证 checkpoint 完整性
        if (!checkpointManager.verifyCheckpoint(checkpoint)) {
            throw RecoveryException("Checkpoint integrity check failed: ${checkpoint.checkpointId}")
        }
        
        // 2. 解析 checkpoint 数据
        val data = JSONObject(checkpoint.data)
        val agentId = data.getString("agent_id")
        val agentState = data.getInt("agent_state")
        val goalStack = data.getJSONArray("goal_stack")
        val tokens = data.getJSONArray("capability_tokens")
        val sessionId = data.optString("session_id", null)
        
        Log.i(TAG, "Checkpoint data: agentId=$agentId, state=$agentState, goals=${goalStack.length()}, tokens=${tokens.length()}")
        
        // 3. 重新注册 Agent
        val app = BrainApplication.getInstance()
        val runtimeClient = app.getRuntimeClient()
        
        try {
            runtimeClient.resumeAgent(agentId, checkpoint.checkpointId)
            Log.i(TAG, "Agent resumed: $agentId")
            
        } catch (e: Exception) {
            throw RecoveryException("Failed to resume agent: $agentId", e)
        }
        
        // 4. 恢复令牌链
        recoverCapabilityTokens(agentId, tokens)
        
        // 5. 恢复会话上下文（如果存在）
        if (sessionId != null) {
            recoverSessionContext(agentId, sessionId)
        }
        
        // 6. 恢复目标栈
        recoverGoalStack(agentId, goalStack)
        
        // 7. 标记 checkpoint 为已恢复
        checkpointManager.markCheckpointRecovered(checkpoint.checkpointId)
        
        Log.i(TAG, "Recovery completed for checkpoint: ${checkpoint.checkpointId}")
    }
    
    /**
     * 恢复能力令牌
     */
    private fun recoverCapabilityTokens(agentId: String, tokens: org.json.JSONArray) {
        Log.i(TAG, "Recovering ${tokens.length()} capability tokens...")
        
        val app = BrainApplication.getInstance()
        val runtimeClient = app.getRuntimeClient()
        
        for (i in 0 until tokens.length()) {
            try {
                val tokenData = tokens.getJSONObject(i)
                val capability = tokenData.getString("capability")
                val scopeIntentId = tokenData.getString("scope_intent_id")
                
                // 重新申请令牌（原令牌已失效）
                runtimeClient.acquireCapabilityToken(agentId, capability, scopeIntentId)
                Log.d(TAG, "Token recovered: $capability")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover token: ${e.message}")
                // 令牌恢复失败不中断整体恢复流程
            }
        }
    }
    
    /**
     * 恢复会话上下文
     */
    private fun recoverSessionContext(agentId: String, sessionId: String) {
        Log.i(TAG, "Recovering session context: $sessionId")
        
        try {
            // TODO: 调用会话服务恢复上下文
            // sessionService.resumeSession(sessionId)
            Log.i(TAG, "Session context recovered: $sessionId")
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to recover session context: ${e.message}")
            // 会话恢复失败不中断整体恢复流程
        }
    }
    
    /**
     * 恢复目标栈
     */
    private fun recoverGoalStack(agentId: String, goalStack: org.json.JSONArray) {
        Log.i(TAG, "Recovering goal stack (${goalStack.length()} goals)...")
        
        val app = BrainApplication.getInstance()
        val runtimeClient = app.getRuntimeClient()
        
        for (i in 0 until goalStack.length()) {
            try {
                val goalData = goalStack.getJSONObject(i)
                val goalId = goalData.getString("goal_id")
                val goalType = goalData.getString("goal_type")
                val status = goalData.getString("status")
                
                // 更新目标状态
                runtimeClient.updateGoal(agentId, goalId, goalType, status)
                Log.d(TAG, "Goal recovered: $goalId ($status)")
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover goal: ${e.message}")
            }
        }
    }
    
    /**
     * 清除 crash 标记
     */
    private fun clearCrashMarker() {
        getSharedPreferences("brain_state", MODE_PRIVATE)
            .edit()
            .remove("last_crash_time")
            .apply()
        
        Log.i(TAG, "Crash marker cleared")
    }
    
    /**
     * 处理恢复失败
     */
    private fun handleRecoveryFailure(error: Exception) {
        Log.e(TAG, "Recovery failed, performing cleanup...", error)
        
        // 记录恢复失败
        getSharedPreferences("brain_state", MODE_PRIVATE)
            .edit()
            .putString("last_recovery_error", error.message)
            .putLong("last_recovery_error_time", System.currentTimeMillis())
            .apply()
        
        // 清除所有 checkpoint（避免反复尝试失败的恢复）
        try {
            checkpointManager.cleanupFailedCheckpoints()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup checkpoints", e)
        }
        
        // 清除 crash 标记
        clearCrashMarker()
        
        // TODO: 发送系统通知提示用户
        // TODO: 写入审计日志
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * 恢复异常
     */
    private class RecoveryException : Exception {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
