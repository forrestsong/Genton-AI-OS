// StreamCoordinator.kt
// B3.2 流式协调器：onTurnDelta 合帧节流
package com.genton.brain.session

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.genton.agent.IAgentSessionListener
import com.genton.agent.SessionHandle
import com.genton.agent.TurnDelta
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 流式协调器
 * 
 * 核心职责：
 * 1. 合帧节流：避免过于频繁的 onTurnDelta 回调（最小间隔 100ms）
 * 2. 文本流合并：type=1 的文本流式增量合并后批量推送
 * 3. thinking/tool_activity 立即推送（用户体验优先）
 * 4. 轮次结束时刷新待发送缓冲
 * 
 * 设计原则：
 * - thinking/tool_activity（type=0/2）：立即推送，无延迟
 * - text_stream（type=1）：合帧推送，100ms 节流窗口
 * - 轮次结束时强制刷新缓冲区
 */
class StreamCoordinator {
    companion object {
        private const val TAG = "StreamCoordinator"
        
        // 节流窗口：100ms（避免过于频繁的 Binder 调用）
        private const val THROTTLE_WINDOW_MS = 100L
        
        // 增量类型常量
        const val TYPE_THINKING = 0      // 思考中
        const val TYPE_TEXT_STREAM = 1   // 文本流式输出
        const val TYPE_TOOL_ACTIVITY = 2 // 工具调用活动
    }
    
    // Handler（UI 线程）
    private val handler = Handler(Looper.getMainLooper())
    
    // 轮次流式状态
    private val turnStreamStates = ConcurrentHashMap<String, TurnStreamState>()
    
    /**
     * 推送流式增量
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param turnId 轮次 ID
     * @param delta 增量数据
     */
    fun pushDelta(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        turnId: String,
        delta: TurnDelta
    ) {
        when (delta.type) {
            TYPE_THINKING, TYPE_TOOL_ACTIVITY -> {
                // thinking/tool_activity 立即推送（用户体验优先）
                immediatelyPush(listener, handle, turnId, delta)
            }
            TYPE_TEXT_STREAM -> {
                // text_stream 合帧节流推送
                throttledPush(listener, handle, turnId, delta)
            }
            else -> {
                Log.w(TAG, "Unknown delta type: ${delta.type}, ignored")
            }
        }
    }
    
    /**
     * 刷新待发送缓冲（轮次结束时调用）
     * 
     * @param listener 会话监听器
     * @param handle 会话句柄
     * @param turnId 轮次 ID
     */
    fun flush(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        turnId: String
    ) {
        val state = turnStreamStates[turnId] ?: return
        
        // 取消待执行的节流任务
        handler.removeCallbacks(state.pendingFlushTask)
        
        // 立即刷新缓冲区
        if (state.bufferedText.isNotEmpty()) {
            val mergedDelta = TurnDelta().apply {
                type = TYPE_TEXT_STREAM
                text = state.bufferedText.toString()
                toolKind = 0
            }
            
            try {
                listener.onTurnDelta(handle, turnId, mergedDelta)
                Log.d(TAG, "Flushed buffered text for turn: $turnId, length: ${mergedDelta.text.length}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush buffered delta for turn: $turnId", e)
            }
            
            state.bufferedText.clear()
        }
        
        // 清理状态
        turnStreamStates.remove(turnId)
    }
    
    /**
     * 清理轮次状态（会话结束/取消时调用）
     * 
     * @param turnId 轮次 ID
     */
    fun clear(turnId: String) {
        val state = turnStreamStates.remove(turnId)
        if (state != null) {
            handler.removeCallbacks(state.pendingFlushTask)
        }
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 立即推送（thinking/tool_activity）
     */
    private fun immediatelyPush(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        turnId: String,
        delta: TurnDelta
    ) {
        try {
            listener.onTurnDelta(handle, turnId, delta)
            Log.d(TAG, "Immediately pushed delta for turn: $turnId, type: ${delta.type}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push delta for turn: $turnId", e)
        }
    }
    
    /**
     * 节流推送（text_stream）
     */
    private fun throttledPush(
        listener: IAgentSessionListener,
        handle: SessionHandle,
        turnId: String,
        delta: TurnDelta
    ) {
        // 获取或创建轮次流式状态
        val state = turnStreamStates.computeIfAbsent(turnId) {
            TurnStreamState(listener, handle, turnId)
        }
        
        // 追加文本到缓冲区
        state.bufferedText.append(delta.text ?: "")
        
        val now = System.currentTimeMillis()
        val elapsed = now - state.lastPushTime.get()
        
        if (elapsed >= THROTTLE_WINDOW_MS) {
            // 已超过节流窗口，立即推送
            flushBuffer(state)
        } else {
            // 未超过节流窗口，延迟推送
            handler.removeCallbacks(state.pendingFlushTask)
            handler.postDelayed(state.pendingFlushTask, THROTTLE_WINDOW_MS - elapsed)
        }
    }
    
    /**
     * 刷新缓冲区
     */
    private fun flushBuffer(state: TurnStreamState) {
        if (state.bufferedText.isEmpty()) {
            return
        }
        
        val mergedDelta = TurnDelta().apply {
            type = TYPE_TEXT_STREAM
            text = state.bufferedText.toString()
            toolKind = 0
        }
        
        try {
            state.listener.onTurnDelta(state.handle, state.turnId, mergedDelta)
            Log.d(TAG, "Throttled pushed text for turn: ${state.turnId}, length: ${mergedDelta.text.length}")
            
            state.bufferedText.clear()
            state.lastPushTime.set(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push buffered delta for turn: ${state.turnId}", e)
        }
    }
    
    // ========== 内部数据类 ==========
    
    /**
     * 轮次流式状态
     */
    private inner class TurnStreamState(
        val listener: IAgentSessionListener,
        val handle: SessionHandle,
        val turnId: String
    ) {
        // 缓冲区（累积的文本）
        val bufferedText = StringBuilder()
        
        // 上次推送时间
        val lastPushTime = AtomicLong(0L)
        
        // 待执行的刷新任务
        val pendingFlushTask = Runnable {
            flushBuffer(this)
        }
    }
}
