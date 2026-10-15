package com.genton.brain.checkpoint

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Checkpoint 管理器
 * 
 * 职责：
 * 1. 创建 checkpoint（Agent 状态快照）
 * 2. 恢复 checkpoint
 * 3. 验证 checkpoint 完整性
 * 4. 清理过期 checkpoint
 */
class CheckpointManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CheckpointManager"
        private const val CHECKPOINT_DIR = "checkpoints"
        private const val MAX_CHECKPOINT_AGE_MS = 24 * 60 * 60 * 1000L // 24 小时
        private const val MAX_CHECKPOINT_COUNT = 10
    }
    
    private val checkpointDir: File = File(context.filesDir, CHECKPOINT_DIR).apply {
        if (!exists()) {
            mkdirs()
        }
    }
    
    /**
     * 创建 checkpoint
     */
    fun createCheckpoint(agentId: String, data: String): Checkpoint {
        Log.i(TAG, "Creating checkpoint for agent: $agentId")
        
        val checkpointId = generateCheckpointId(agentId)
        val timestamp = System.currentTimeMillis()
        
        // 计算数据哈希（完整性校验）
        val dataHash = calculateHash(data)
        
        val checkpoint = Checkpoint(
            checkpointId = checkpointId,
            agentId = agentId,
            timestamp = timestamp,
            data = data,
            dataHash = dataHash
        )
        
        // 保存 checkpoint 到文件
        saveCheckpoint(checkpoint)
        
        // 清理旧 checkpoint
        cleanupOldCheckpoints()
        
        Log.i(TAG, "Checkpoint created: $checkpointId")
        return checkpoint
    }
    
    /**
     * 恢复 checkpoint
     */
    fun restoreCheckpoint(checkpointId: String): Checkpoint? {
        Log.i(TAG, "Restoring checkpoint: $checkpointId")
        
        val file = File(checkpointDir, "$checkpointId.json")
        if (!file.exists()) {
            Log.w(TAG, "Checkpoint file not found: $checkpointId")
            return null
        }
        
        try {
            val json = file.readText()
            val checkpoint = parseCheckpoint(json)
            
            // 验证完整性
            if (!verifyCheckpoint(checkpoint)) {
                Log.e(TAG, "Checkpoint verification failed: $checkpointId")
                return null
            }
            
            Log.i(TAG, "Checkpoint restored: $checkpointId")
            return checkpoint
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore checkpoint: $checkpointId", e)
            return null
        }
    }
    
    /**
     * 验证 checkpoint 完整性
     */
    fun verifyCheckpoint(checkpoint: Checkpoint): Boolean {
        val calculatedHash = calculateHash(checkpoint.data)
        return calculatedHash == checkpoint.dataHash
    }
    
    /**
     * 列出所有 checkpoint
     */
    fun listCheckpoints(): List<Checkpoint> {
        val checkpoints = mutableListOf<Checkpoint>()
        
        checkpointDir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                try {
                    val json = file.readText()
                    val checkpoint = parseCheckpoint(json)
                    checkpoints.add(checkpoint)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse checkpoint file: ${file.name}", e)
                }
            }
        }
        
        return checkpoints.sortedByDescending { it.timestamp }
    }
    
    /**
     * 标记 checkpoint 为已恢复
     */
    fun markCheckpointRecovered(checkpointId: String) {
        val file = File(checkpointDir, "$checkpointId.json")
        if (file.exists()) {
            val recoveredFile = File(checkpointDir, "$checkpointId.recovered")
            file.renameTo(recoveredFile)
            Log.i(TAG, "Checkpoint marked as recovered: $checkpointId")
        }
    }
    
    /**
     * 删除 checkpoint
     */
    fun deleteCheckpoint(checkpointId: String): Boolean {
        val file = File(checkpointDir, "$checkpointId.json")
        val deleted = file.delete()
        
        if (deleted) {
            Log.i(TAG, "Checkpoint deleted: $checkpointId")
        }
        
        return deleted
    }
    
    /**
     * 清理失败的 checkpoint
     */
    fun cleanupFailedCheckpoints() {
        Log.i(TAG, "Cleaning up failed checkpoints...")
        
        val checkpoints = listCheckpoints()
        for (checkpoint in checkpoints) {
            if (!verifyCheckpoint(checkpoint)) {
                deleteCheckpoint(checkpoint.checkpointId)
                Log.w(TAG, "Deleted corrupted checkpoint: ${checkpoint.checkpointId}")
            }
        }
    }
    
    /**
     * 清理旧 checkpoint
     */
    private fun cleanupOldCheckpoints() {
        val now = System.currentTimeMillis()
        val checkpoints = listCheckpoints()
        
        // 1. 删除过期的 checkpoint
        checkpoints.forEach { checkpoint ->
            if (now - checkpoint.timestamp > MAX_CHECKPOINT_AGE_MS) {
                deleteCheckpoint(checkpoint.checkpointId)
                Log.i(TAG, "Deleted expired checkpoint: ${checkpoint.checkpointId}")
            }
        }
        
        // 2. 保留最新的 N 个 checkpoint
        val remaining = listCheckpoints()
        if (remaining.size > MAX_CHECKPOINT_COUNT) {
            remaining.drop(MAX_CHECKPOINT_COUNT).forEach { checkpoint ->
                deleteCheckpoint(checkpoint.checkpointId)
                Log.i(TAG, "Deleted old checkpoint (quota): ${checkpoint.checkpointId}")
            }
        }
    }
    
    /**
     * 保存 checkpoint 到文件
     */
    private fun saveCheckpoint(checkpoint: Checkpoint) {
        val file = File(checkpointDir, "${checkpoint.checkpointId}.json")
        val json = serializeCheckpoint(checkpoint)
        file.writeText(json)
    }
    
    /**
     * 生成 checkpoint ID
     */
    private fun generateCheckpointId(agentId: String): String {
        return "cp_${agentId}_${UUID.randomUUID().toString().substring(0, 8)}"
    }
    
    /**
     * 计算哈希
     */
    private fun calculateHash(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 序列化 checkpoint
     */
    private fun serializeCheckpoint(checkpoint: Checkpoint): String {
        val json = JSONObject()
        json.put("checkpoint_id", checkpoint.checkpointId)
        json.put("agent_id", checkpoint.agentId)
        json.put("timestamp", checkpoint.timestamp)
        json.put("data", checkpoint.data)
        json.put("data_hash", checkpoint.dataHash)
        return json.toString(2)
    }
    
    /**
     * 解析 checkpoint
     */
    private fun parseCheckpoint(json: String): Checkpoint {
        val obj = JSONObject(json)
        return Checkpoint(
            checkpointId = obj.getString("checkpoint_id"),
            agentId = obj.getString("agent_id"),
            timestamp = obj.getLong("timestamp"),
            data = obj.getString("data"),
            dataHash = obj.getString("data_hash")
        )
    }
}

/**
 * Checkpoint 数据类
 */
data class Checkpoint(
    val checkpointId: String,
    val agentId: String,
    val timestamp: Long,
    val data: String,
    val dataHash: String
)
