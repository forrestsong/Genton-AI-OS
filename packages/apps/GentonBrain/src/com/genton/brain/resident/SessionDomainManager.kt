package com.genton.brain.resident

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * B4.1 常驻小模型域 - 域会话管理器
 * 
 * 职责：
 * 1. 管理常驻推理域的会话状态（唤醒词检测、意图分类、快速响应）
 * 2. 跟踪当前活跃的常驻任务
 * 3. 协调多个常驻模型的生命周期
 * 4. 管理域资源分配（NPU 小核、内存、功耗预算）
 * 5. 提供会话上下文给常驻模型（提升识别准确率）
 * 
 * 设计要点：
 * - 域会话是轻量级的（不同于完整的 Agent 会话）
 * - 仅保留最近 N 次交互的上下文（短期记忆）
 * - 支持多域并行（唤醒词检测 + 意图分类同时运行）
 * - 自动清理过期会话（防止内存泄漏）
 */
class SessionDomainManager {
    
    companion object {
        private const val TAG = "SessionDomainManager"
        
        // 域类型
        const val DOMAIN_WAKE_WORD = "wake_word"  // 唤醒词检测域
        const val DOMAIN_INTENT_CLASSIFICATION = "intent_classification"  // 意图分类域
        const val DOMAIN_QUICK_RESPONSE = "quick_response"  // 快速响应域（无需大模型）
        
        // 会话配置
        private const val MAX_ACTIVE_SESSIONS = 10  // 最多活跃会话数
        private const val SESSION_TIMEOUT_MS = 300000L  // 会话超时 5分钟
        private const val CONTEXT_HISTORY_SIZE = 5  // 上下文历史大小
        
        // 会话状态
        const val STATE_IDLE = 0  // 空闲
        const val STATE_ACTIVE = 1  // 活跃
        const val STATE_SUSPENDED = 2  // 挂起（低功耗）
        const val STATE_TERMINATED = 3  // 已终止
    }
    
    // 活跃会话映射（域类型 -> 会话）
    private val activeSessions = ConcurrentHashMap<String, DomainSession>()
    
    // 会话锁
    private val lock = ReentrantReadWriteLock()
    
    // 会话 ID 生成器
    private var sessionIdCounter = 0L
    
    // 启动状态
    @Volatile
    private var isStarted = false
    
    // 清理线程
    private var cleanupThread: Thread? = null
    
    /**
     * 启动域会话管理器
     */
    fun start() {
        if (isStarted) {
            Log.w(TAG, "SessionDomainManager already started")
            return
        }
        
        Log.i(TAG, "Starting SessionDomainManager...")
        
        try {
            // 启动清理线程
            startCleanupThread()
            
            isStarted = true
            Log.i(TAG, "SessionDomainManager started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SessionDomainManager", e)
            throw e
        }
    }
    
    /**
     * 停止域会话管理器
     */
    fun stop() {
        if (!isStarted) {
            return
        }
        
        Log.i(TAG, "Stopping SessionDomainManager...")
        
        try {
            // 停止清理线程
            stopCleanupThread()
            
            // 终止所有会话
            terminateAllSessions()
            
            isStarted = false
            Log.i(TAG, "SessionDomainManager stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop SessionDomainManager", e)
        }
    }
    
    /**
     * 创建域会话
     */
    fun createSession(domainType: String, metadata: Map<String, String> = emptyMap()): String {
        lock.write {
            // 检查会话数量限制
            if (activeSessions.size >= MAX_ACTIVE_SESSIONS) {
                Log.w(TAG, "Cannot create session: max active sessions reached")
                // 尝试清理过期会话
                cleanupExpiredSessions()
                
                if (activeSessions.size >= MAX_ACTIVE_SESSIONS) {
                    throw IllegalStateException("Max active sessions limit reached")
                }
            }
            
            // 生成会话 ID
            val sessionId = generateSessionId(domainType)
            
            // 创建会话
            val session = DomainSession(
                sessionId = sessionId,
                domainType = domainType,
                state = STATE_IDLE,
                createdTime = System.currentTimeMillis(),
                lastActivityTime = System.currentTimeMillis(),
                metadata = metadata.toMutableMap()
            )
            
            activeSessions[sessionId] = session
            
            Log.i(TAG, "Domain session created: id=$sessionId, domain=$domainType")
            return sessionId
        }
    }
    
    /**
     * 激活会话
     */
    fun activateSession(sessionId: String): Boolean {
        lock.write {
            val session = activeSessions[sessionId]
            if (session == null) {
                Log.w(TAG, "Session not found: $sessionId")
                return false
            }
            
            if (session.state == STATE_TERMINATED) {
                Log.w(TAG, "Cannot activate terminated session: $sessionId")
                return false
            }
            
            session.state = STATE_ACTIVE
            session.lastActivityTime = System.currentTimeMillis()
            
            Log.d(TAG, "Session activated: $sessionId")
            return true
        }
    }
    
    /**
     * 挂起会话（低功耗模式）
     */
    fun suspendSession(sessionId: String): Boolean {
        lock.write {
            val session = activeSessions[sessionId]
            if (session == null) {
                Log.w(TAG, "Session not found: $sessionId")
                return false
            }
            
            if (session.state != STATE_ACTIVE) {
                Log.w(TAG, "Can only suspend active sessions: $sessionId")
                return false
            }
            
            session.state = STATE_SUSPENDED
            session.lastActivityTime = System.currentTimeMillis()
            
            Log.d(TAG, "Session suspended: $sessionId")
            return true
        }
    }
    
    /**
     * 恢复会话
     */
    fun resumeSession(sessionId: String): Boolean {
        lock.write {
            val session = activeSessions[sessionId]
            if (session == null) {
                Log.w(TAG, "Session not found: $sessionId")
                return false
            }
            
            if (session.state != STATE_SUSPENDED) {
                Log.w(TAG, "Can only resume suspended sessions: $sessionId")
                return false
            }
            
            session.state = STATE_ACTIVE
            session.lastActivityTime = System.currentTimeMillis()
            
            Log.d(TAG, "Session resumed: $sessionId")
            return true
        }
    }
    
    /**
     * 终止会话
     */
    fun terminateSession(sessionId: String): Boolean {
        lock.write {
            val session = activeSessions[sessionId]
            if (session == null) {
                Log.w(TAG, "Session not found: $sessionId")
                return false
            }
            
            session.state = STATE_TERMINATED
            session.lastActivityTime = System.currentTimeMillis()
            
            // 清理上下文历史
            session.contextHistory.clear()
            
            // 从活跃会话中移除
            activeSessions.remove(sessionId)
            
            Log.i(TAG, "Session terminated: $sessionId")
            return true
        }
    }
    
    /**
     * 更新会话上下文
     */
    fun updateContext(sessionId: String, context: SessionContext) {
        lock.write {
            val session = activeSessions[sessionId]
            if (session == null) {
                Log.w(TAG, "Session not found: $sessionId")
                return
            }
            
            // 添加到上下文历史
            session.contextHistory.add(context)
            
            // 限制历史大小
            while (session.contextHistory.size > CONTEXT_HISTORY_SIZE) {
                session.contextHistory.removeAt(0)
            }
            
            // 更新活动时间
            session.lastActivityTime = System.currentTimeMillis()
            
            Log.d(TAG, "Session context updated: $sessionId (history size=${session.contextHistory.size})")
        }
    }
    
    /**
     * 获取会话上下文历史
     */
    fun getContextHistory(sessionId: String): List<SessionContext> {
        return lock.read {
            val session = activeSessions[sessionId]
            session?.contextHistory?.toList() ?: emptyList()
        }
    }
    
    /**
     * 获取会话
     */
    fun getSession(sessionId: String): DomainSession? {
        return lock.read {
            activeSessions[sessionId]?.copy()
        }
    }
    
    /**
     * 获取所有活跃会话
     */
    fun getActiveSessions(): List<DomainSession> {
        return lock.read {
            activeSessions.values.map { it.copy() }
        }
    }
    
    /**
     * 按域类型获取会话
     */
    fun getSessionsByDomain(domainType: String): List<DomainSession> {
        return lock.read {
            activeSessions.values
                .filter { it.domainType == domainType }
                .map { it.copy() }
        }
    }
    
    /**
     * 生成会话 ID
     */
    private fun generateSessionId(domainType: String): String {
        val id = sessionIdCounter++
        return "${domainType}_${System.currentTimeMillis()}_${id}"
    }
    
    /**
     * 启动清理线程
     */
    private fun startCleanupThread() {
        cleanupThread = Thread({
            Log.i(TAG, "Cleanup thread started")
            
            try {
                while (isStarted && !Thread.currentThread().isInterrupted) {
                    try {
                        // 每分钟清理一次过期会话
                        Thread.sleep(60000)
                        
                        cleanupExpiredSessions()
                        
                    } catch (e: InterruptedException) {
                        Log.i(TAG, "Cleanup thread interrupted")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in cleanup thread", e)
                    }
                }
            } finally {
                Log.i(TAG, "Cleanup thread stopped")
            }
        }, "SessionDomainCleanup")
        
        cleanupThread?.start()
    }
    
    /**
     * 停止清理线程
     */
    private fun stopCleanupThread() {
        cleanupThread?.interrupt()
        cleanupThread?.join(1000)
        cleanupThread = null
    }
    
    /**
     * 清理过期会话
     */
    private fun cleanupExpiredSessions() {
        lock.write {
            val now = System.currentTimeMillis()
            val expiredSessions = mutableListOf<String>()
            
            for ((sessionId, session) in activeSessions) {
                val inactiveTime = now - session.lastActivityTime
                if (inactiveTime > SESSION_TIMEOUT_MS) {
                    expiredSessions.add(sessionId)
                }
            }
            
            for (sessionId in expiredSessions) {
                Log.i(TAG, "Cleaning up expired session: $sessionId")
                terminateSession(sessionId)
            }
            
            if (expiredSessions.isNotEmpty()) {
                Log.i(TAG, "Cleaned up ${expiredSessions.size} expired sessions")
            }
        }
    }
    
    /**
     * 终止所有会话
     */
    private fun terminateAllSessions() {
        lock.write {
            val sessionIds = activeSessions.keys.toList()
            for (sessionId in sessionIds) {
                terminateSession(sessionId)
            }
        }
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): SessionDomainStatistics {
        return lock.read {
            val byDomain = mutableMapOf<String, Int>()
            val byState = mutableMapOf<Int, Int>()
            
            for (session in activeSessions.values) {
                byDomain[session.domainType] = (byDomain[session.domainType] ?: 0) + 1
                byState[session.state] = (byState[session.state] ?: 0) + 1
            }
            
            SessionDomainStatistics(
                totalActiveSessions = activeSessions.size,
                sessionsByDomain = byDomain,
                sessionsByState = byState,
                maxSessions = MAX_ACTIVE_SESSIONS
            )
        }
    }
}

/**
 * 域会话数据类
 */
data class DomainSession(
    val sessionId: String,
    val domainType: String,
    @Volatile var state: Int,
    val createdTime: Long,
    @Volatile var lastActivityTime: Long,
    val metadata: MutableMap<String, String>,
    val contextHistory: MutableList<SessionContext> = mutableListOf()
)

/**
 * 会话上下文（轻量级）
 */
data class SessionContext(
    val timestamp: Long,
    val eventType: String,  // "wake_word", "intent", "response"
    val data: Map<String, Any>  // 上下文数据
)

/**
 * 域会话统计信息
 */
data class SessionDomainStatistics(
    val totalActiveSessions: Int,
    val sessionsByDomain: Map<String, Int>,
    val sessionsByState: Map<Int, Int>,
    val maxSessions: Int
)
