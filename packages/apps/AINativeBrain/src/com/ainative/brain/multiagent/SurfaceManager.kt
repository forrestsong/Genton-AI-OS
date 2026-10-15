package com.ainative.brain.multiagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream

/**
 * Surface 管理器
 * 
 * 职责：
 * 1. Surface 创建与销毁
 * 2. Surface 截图（用于冷切换时减少白屏）
 * 3. Surface 换显（配合 SurfaceControl 事务）
 * 4. Surface 状态保存/恢复
 * 
 * 集成点：
 * - 与 IAgentUiTreeService 集成（捕获 UI 树）
 * - 与 IAgentScreenService 集成（帧捕获）
 * - 与 WindowManager/SurfaceControl 集成（Surface 管理）
 */
class SurfaceManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SurfaceManager"
        private const val SNAPSHOT_DIR = "agent_snapshots"
        private const val SNAPSHOT_QUALITY = 85  // JPEG 质量
    }
    
    private val snapshotDir: File = File(context.filesDir, SNAPSHOT_DIR).apply {
        if (!exists()) {
            mkdirs()
        }
    }
    
    /**
     * 创建 Agent Surface Token
     */
    fun createSurfaceToken(agentId: String): String {
        Log.i(TAG, "Creating surface token for agent: $agentId")
        
        // TODO: 通过 WindowManager 创建真实 Surface
        // 当前返回模拟 token
        val token = "surface_${agentId}_${System.currentTimeMillis()}"
        
        Log.i(TAG, "Surface token created: $token")
        return token
    }
    
    /**
     * 销毁 Agent Surface
     */
    fun destroySurface(surfaceToken: String): Boolean {
        Log.i(TAG, "Destroying surface: $surfaceToken")
        
        try {
            // TODO: 通过 WindowManager 销毁真实 Surface
            
            Log.i(TAG, "Surface destroyed: $surfaceToken")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy surface: $surfaceToken", e)
            return false
        }
    }
    
    /**
     * 捕获 Agent Surface 截图
     * 
     * 用于冷切换时：
     * 1. 挂起前保存当前 UI 状态
     * 2. 恢复时先显示截图，再重建 UI（减少白屏）
     */
    fun captureAgentSnapshot(agentId: String, surfaceToken: String?): Bitmap? {
        Log.i(TAG, "Capturing snapshot for agent: $agentId")
        
        if (surfaceToken == null) {
            Log.w(TAG, "No surface token, cannot capture snapshot")
            return null
        }
        
        try {
            // TODO: 通过 IAgentScreenService 捕获真实帧
            // 当前创建一个占位 Bitmap
            val bitmap = createPlaceholderBitmap(agentId)
            
            // 保存到磁盘
            saveSnapshotToDisk(agentId, bitmap)
            
            Log.i(TAG, "Snapshot captured: $agentId")
            return bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture snapshot: $agentId", e)
            return null
        }
    }
    
    /**
     * 加载 Agent 截图
     */
    fun loadAgentSnapshot(agentId: String): Bitmap? {
        Log.i(TAG, "Loading snapshot for agent: $agentId")
        
        val snapshotFile = File(snapshotDir, "$agentId.jpg")
        if (!snapshotFile.exists()) {
            Log.w(TAG, "Snapshot file not found: ${snapshotFile.path}")
            return null
        }
        
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(snapshotFile.path)
            Log.i(TAG, "Snapshot loaded: $agentId")
            return bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot: $agentId", e)
            return null
        }
    }
    
    /**
     * 删除 Agent 截图
     */
    fun deleteAgentSnapshot(agentId: String): Boolean {
        Log.i(TAG, "Deleting snapshot for agent: $agentId")
        
        val snapshotFile = File(snapshotDir, "$agentId.jpg")
        val deleted = snapshotFile.delete()
        
        if (deleted) {
            Log.i(TAG, "Snapshot deleted: $agentId")
        }
        
        return deleted
    }
    
    /**
     * 执行 Surface 换显（使用 SurfaceControl 事务）
     * 
     * 性能优化：
     * 1. 使用 SurfaceControl.Transaction 批量更新
     * 2. 设置正确的 Z-order
     * 3. 使用硬件合成器加速
     */
    fun swapSurfaceDisplay(
        currentSurfaceToken: String?,
        targetSurfaceToken: String
    ): Boolean {
        Log.i(TAG, "Swapping surface display: $currentSurfaceToken -> $targetSurfaceToken")
        
        try {
            // TODO: 使用 SurfaceControl.Transaction 实现真实换显
            // 伪代码：
            // val transaction = SurfaceControl.Transaction()
            // if (currentSurfaceToken != null) {
            //     transaction.hide(currentSurface)
            // }
            // transaction.show(targetSurface)
            // transaction.setLayer(targetSurface, Z_ORDER_FOREGROUND)
            // transaction.apply()
            
            Log.i(TAG, "Surface display swapped")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to swap surface display", e)
            return false
        }
    }
    
    /**
     * 保存截图到磁盘
     */
    private fun saveSnapshotToDisk(agentId: String, bitmap: Bitmap) {
        val snapshotFile = File(snapshotDir, "$agentId.jpg")
        
        FileOutputStream(snapshotFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, SNAPSHOT_QUALITY, out)
        }
        
        Log.d(TAG, "Snapshot saved to disk: ${snapshotFile.path}")
    }
    
    /**
     * 创建占位 Bitmap（用于测试）
     */
    private fun createPlaceholderBitmap(agentId: String): Bitmap {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // 绘制占位背景
        canvas.drawColor(Color.parseColor("#F5F5F5"))
        
        // TODO: 绘制 Agent 名称等信息
        
        return bitmap
    }
    
    /**
     * 清理所有截图
     */
    fun cleanupAllSnapshots() {
        Log.i(TAG, "Cleaning up all snapshots...")
        
        var count = 0
        snapshotDir.listFiles()?.forEach { file ->
            if (file.delete()) {
                count++
            }
        }
        
        Log.i(TAG, "Cleaned up $count snapshots")
    }
}
