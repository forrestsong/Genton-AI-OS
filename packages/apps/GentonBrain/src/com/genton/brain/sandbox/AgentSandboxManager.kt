// AgentSandboxManager.kt
// B6.1 沙箱宿主：隔离进程/存储/网络/资源配额/SE 收紧
package com.genton.brain.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.genton.sandbox.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Agent 沙箱管理器
 * 
 * 核心职责：
 * 1. 启动/停止沙箱进程（isolatedProcess）
 * 2. 校验 SandboxManifest（签名+能力声明）
 * 3. 用户授权记录管理
 * 4. 沙箱生命周期管理
 * 5. 资源配额管理（CPU/内存/网络/调用次数）
 * 
 * 隔离机制：
 * - isolatedProcess：独立 UID，无共享权限
 * - 存储隔离：独立加密目录
 * - 网络白名单：netd iptables 规则
 * - CPU/内存配额：cgroup 限制
 * - SE 收紧：neverallow 非白名单 Binder 调用
 * 
 * 安全约束：
 * - 第三方无 signature|privileged 权限
 * - 能力逐项授权，deny 默认
 * - 所有动作仍过策略引擎
 * - 审计全覆盖
 */
class AgentSandboxManager(
    private val context: Context
) : IAgentSandboxManager.Stub() {
    
    companion object {
        private const val TAG = "AgentSandboxManager"
        
        // 默认配额
        private const val DEFAULT_MAX_CALLS_PER_HOUR = 100
        private const val DEFAULT_MAX_WALLTIME_MS = 60000L  // 60 秒
        private const val DEFAULT_CPU_QUOTA_PERCENT = 10    // 10% CPU
        private const val DEFAULT_MEMORY_LIMIT_MB = 256     // 256MB
        
        // 网络白名单 UID 起始范围（isolatedProcess）
        private const val ISOLATED_UID_START = 99000
        private const val ISOLATED_UID_END = 99999
    }
    
    // 活跃沙箱表（handleId -> SandboxContext）
    private val activeSandboxes = ConcurrentHashMap<String, SandboxContext>()
    
    // 授权记录表（agentId -> 已授权能力列表）
    private val consentStore = ConcurrentHashMap<String, MutableSet<String>>()
    
    // 执行器（异步任务）
    private val executor = Executors.newCachedThreadPool()
    
    // 网关实例引用
    private var gateway: AgentSandboxGateway? = null
    
    /**
     * 设置网关引用（依赖注入）
     */
    fun setGateway(gateway: AgentSandboxGateway) {
        this.gateway = gateway
    }
    
    /**
     * 启动沙箱
     */
    override fun startSandbox(manifest: SandboxManifest, handle: SandboxHandle): Int {
        Log.i(TAG, "Starting sandbox: ${manifest.agentId}")
        
        try {
            // 1. 校验签名
            if (!verifyManifestSignature(manifest)) {
                Log.e(TAG, "Manifest signature verification failed: ${manifest.agentId}")
                return IAgentSandboxManager.STATUS_UNSIGNED
            }
            
            // 2. 检查用户授权记录
            val consentGranted = checkConsentRecords(manifest)
            if (!consentGranted) {
                Log.w(TAG, "User consent not granted for: ${manifest.agentId}")
                // 实际应弹出授权 UI，这里模拟授权通过
                grantInitialConsent(manifest)
            }
            
            // 3. 分配 isolatedProcess UID
            val isolatedUid = allocateIsolatedUid()
            if (isolatedUid == -1) {
                Log.e(TAG, "Failed to allocate isolated UID")
                return IAgentSandboxManager.STATUS_OVER_QUOTA
            }
            
            // 4. 创建沙箱句柄
            val handleId = generateHandleId(manifest.agentId)
            handle.handleId = handleId
            handle.agentId = manifest.agentId
            handle.sandboxPid = 0  // 未启动前为 0
            handle.sandboxUid = isolatedUid
            handle.createdAtMs = System.currentTimeMillis()
            handle.sessionId = null
            handle.grantedCapabilities = getGrantedCapabilities(manifest.agentId)
            handle.remainingCalls = manifest.maxCallsPerHour
            handle.remainingWalltimeMs = manifest.maxWalltimeMs
            
            // 5. 创建沙箱上下文
            val sandboxContext = SandboxContext(
                handleId = handleId,
                manifest = manifest,
                isolatedUid = isolatedUid,
                handle = handle,
                state = SandboxState.INITIALIZING,
                createdAtMs = System.currentTimeMillis()
            )
            
            // 6. 配置网络白名单（netd）
            configureNetworkWhitelist(isolatedUid, manifest.networkDomains)
            
            // 7. 配置 cgroup 资源配额
            configureCgroupLimits(isolatedUid, DEFAULT_CPU_QUOTA_PERCENT, DEFAULT_MEMORY_LIMIT_MB)
            
            // 8. 创建隔离存储目录
            createIsolatedStorage(handleId)
            
            // 9. 绑定沙箱进程服务
            val bindSuccess = bindSandboxService(sandboxContext)
            if (!bindSuccess) {
                Log.e(TAG, "Failed to bind sandbox service: ${manifest.agentId}")
                cleanupSandbox(sandboxContext)
                return IAgentSandboxManager.STATUS_INTERNAL_ERROR
            }
            
            // 10. 注册到活跃表
            activeSandboxes[handleId] = sandboxContext
            sandboxContext.state = SandboxState.RUNNING
            
            Log.i(TAG, "Sandbox started: ${manifest.agentId}, handleId=$handleId, uid=$isolatedUid")
            return IAgentSandboxManager.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sandbox", e)
            return IAgentSandboxManager.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 停止沙箱
     */
    override fun stopSandbox(handle: SandboxHandle, reason: Int): Int {
        Log.i(TAG, "Stopping sandbox: ${handle.handleId}, reason=$reason")
        
        val sandbox = activeSandboxes[handle.handleId]
        if (sandbox == null) {
            Log.e(TAG, "Sandbox not found: ${handle.handleId}")
            return IAgentSandboxManager.STATUS_NOT_FOUND
        }
        
        try {
            // 1. 通知第三方 Agent 停止
            sandbox.agentBinder?.stop(reason)
            
            // 2. 解绑服务
            unbindSandboxService(sandbox)
            
            // 3. 清理资源
            cleanupSandbox(sandbox)
            
            // 4. 从活跃表移除
            activeSandboxes.remove(handle.handleId)
            
            sandbox.state = SandboxState.STOPPED
            
            Log.i(TAG, "Sandbox stopped: ${handle.handleId}")
            return IAgentSandboxManager.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop sandbox", e)
            return IAgentSandboxManager.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 暂停/恢复沙箱
     */
    override fun pauseSandbox(handle: SandboxHandle, pause: Boolean): Int {
        Log.i(TAG, "Pause sandbox: ${handle.handleId}, pause=$pause")
        
        val sandbox = activeSandboxes[handle.handleId]
        if (sandbox == null) {
            Log.e(TAG, "Sandbox not found: ${handle.handleId}")
            return IAgentSandboxManager.STATUS_NOT_FOUND
        }
        
        try {
            if (pause) {
                // 冻结进程（发送 SIGSTOP）
                if (sandbox.pid > 0) {
                    Process.sendSignal(sandbox.pid, Process.SIGNAL_STOP)
                }
                sandbox.state = SandboxState.PAUSED
            } else {
                // 恢复进程（发送 SIGCONT）
                if (sandbox.pid > 0) {
                    Process.sendSignal(sandbox.pid, Process.SIGNAL_CONT)
                }
                sandbox.state = SandboxState.RUNNING
            }
            
            Log.i(TAG, "Sandbox pause state changed: ${handle.handleId}, paused=$pause")
            return IAgentSandboxManager.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause/resume sandbox", e)
            return IAgentSandboxManager.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 撤销授权（一键停用）
     */
    override fun revokeConsent(handle: SandboxHandle): Int {
        Log.i(TAG, "Revoking consent: ${handle.handleId}")
        
        val sandbox = activeSandboxes[handle.handleId]
        if (sandbox == null) {
            Log.e(TAG, "Sandbox not found: ${handle.handleId}")
            return IAgentSandboxManager.STATUS_NOT_FOUND
        }
        
        try {
            // 1. 清空授权能力
            consentStore[sandbox.manifest.agentId]?.clear()
            
            // 2. 更新句柄状态
            handle.grantedCapabilities = emptyArray()
            
            // 3. 标记授权已撤销
            sandbox.consentRevoked = true
            
            Log.i(TAG, "Consent revoked: ${handle.handleId}")
            return IAgentSandboxManager.STATUS_OK
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to revoke consent", e)
            return IAgentSandboxManager.STATUS_INTERNAL_ERROR
        }
    }
    
    /**
     * 查询沙箱状态
     */
    override fun getStatus(handle: SandboxHandle): SandboxStatus {
        val sandbox = activeSandboxes[handle.handleId]
        if (sandbox == null) {
            return SandboxStatus().apply {
                handleId = handle.handleId
                state = SandboxState.STOPPED.ordinal
            }
        }
        
        return sandbox.toStatus()
    }
    
    /**
     * 列出活跃沙箱
     */
    override fun listActiveSandboxes(): Array<SandboxStatus> {
        return activeSandboxes.values.map { it.toStatus() }.toTypedArray()
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 校验清单签名
     */
    private fun verifyManifestSignature(manifest: SandboxManifest): Boolean {
        // TODO: 实际实现需校验 APK 签名与 manifest.signatureRef 匹配
        // 1. 获取 APK 签名证书
        // 2. 计算签名哈希
        // 3. 与 manifest.signatureRef 比对
        Log.i(TAG, "Verifying manifest signature: ${manifest.agentId}")
        return true  // 模拟通过
    }
    
    /**
     * 检查用户授权记录
     */
    private fun checkConsentRecords(manifest: SandboxManifest): Boolean {
        val granted = consentStore[manifest.agentId]
        if (granted == null || granted.isEmpty()) {
            return false
        }
        
        // 检查是否所有请求的能力都已授权
        return manifest.requestedCapabilities.all { it in granted }
    }
    
    /**
     * 授予初始授权（首启）
     */
    private fun grantInitialConsent(manifest: SandboxManifest) {
        // TODO: 实际实现需弹出授权 UI，逐项确认
        // 这里模拟用户全部授权
        val granted = consentStore.getOrPut(manifest.agentId) { mutableSetOf() }
        granted.addAll(manifest.requestedCapabilities)
        Log.i(TAG, "Initial consent granted: ${manifest.agentId}, capabilities=${manifest.requestedCapabilities.size}")
    }
    
    /**
     * 获取已授权能力
     */
    private fun getGrantedCapabilities(agentId: String): Array<String> {
        val granted = consentStore[agentId] ?: return emptyArray()
        return granted.toTypedArray()
    }
    
    /**
     * 分配 isolatedProcess UID
     */
    private fun allocateIsolatedUid(): Int {
        // TODO: 实际实现需与 ActivityManagerService 协调分配
        // 这里简单递增分配
        val usedUids = activeSandboxes.values.map { it.isolatedUid }.toSet()
        for (uid in ISOLATED_UID_START..ISOLATED_UID_END) {
            if (uid !in usedUids) {
                return uid
            }
        }
        return -1  // 无可用 UID
    }
    
    /**
     * 生成句柄 ID
     */
    private fun generateHandleId(agentId: String): String {
        return "sandbox_${agentId}_${UUID.randomUUID().toString().take(8)}"
    }
    
    /**
     * 配置网络白名单（netd）
     */
    private fun configureNetworkWhitelist(uid: Int, domains: Array<String>) {
        // TODO: 实际实现需调用 netd 配置 iptables 规则
        // 1. 默认 deny 该 UID 所有出网
        // 2. 逐个域名添加 allow 规则
        Log.i(TAG, "Configuring network whitelist: uid=$uid, domains=${domains.size}")
    }
    
    /**
     * 配置 cgroup 资源配额
     */
    private fun configureCgroupLimits(uid: Int, cpuPercent: Int, memoryMb: Int) {
        // TODO: 实际实现需写入 cgroup 配置文件
        // /sys/fs/cgroup/cpu/uid_$uid/cpu.shares
        // /sys/fs/cgroup/memory/uid_$uid/memory.limit_in_bytes
        Log.i(TAG, "Configuring cgroup limits: uid=$uid, cpu=$cpuPercent%, mem=${memoryMb}MB")
    }
    
    /**
     * 创建隔离存储目录
     */
    private fun createIsolatedStorage(handleId: String) {
        // TODO: 实际实现需创建独立加密目录
        // /data/system/genton/sandbox/$handleId/
        Log.i(TAG, "Creating isolated storage: $handleId")
    }
    
    /**
     * 绑定沙箱进程服务
     */
    private fun bindSandboxService(sandbox: SandboxContext): Boolean {
        // TODO: 实际实现需绑定第三方 App 的 ISandboxedAgent 服务
        // 1. 创建 Intent（指定 isolatedProcess 启动）
        // 2. bindService（BIND_ISOLATED_PROCESS）
        // 3. 保存 ServiceConnection
        Log.i(TAG, "Binding sandbox service: ${sandbox.manifest.agentId}")
        return true  // 模拟成功
    }
    
    /**
     * 解绑沙箱服务
     */
    private fun unbindSandboxService(sandbox: SandboxContext) {
        // TODO: unbindService
        Log.i(TAG, "Unbinding sandbox service: ${sandbox.handleId}")
    }
    
    /**
     * 清理沙箱资源
     */
    private fun cleanupSandbox(sandbox: SandboxContext) {
        // 1. 清理网络白名单（netd）
        // 2. 清理 cgroup 配置
        // 3. 清理隔离存储（可选，保留用于审计）
        // 4. 终止进程（如未自动退出）
        Log.i(TAG, "Cleaning up sandbox: ${sandbox.handleId}")
        
        if (sandbox.pid > 0) {
            try {
                Process.killProcess(sandbox.pid)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to kill sandbox process: ${sandbox.pid}", e)
            }
        }
    }
}

/**
 * 沙箱上下文（内部状态）
 */
data class SandboxContext(
    val handleId: String,
    val manifest: SandboxManifest,
    val isolatedUid: Int,
    val handle: SandboxHandle,
    var state: SandboxState,
    val createdAtMs: Long,
    var pid: Int = 0,
    var agentBinder: ISandboxedAgent? = null,
    var serviceConnection: ServiceConnection? = null,
    var consentRevoked: Boolean = false,
    var totalCalls: Long = 0,
    var totalWalltimeMs: Long = 0,
    var lastActiveMs: Long = System.currentTimeMillis()
) {
    /**
     * 转换为状态对象
     */
    fun toStatus(): SandboxStatus {
        return SandboxStatus().apply {
            this.handleId = this@SandboxContext.handleId
            this.agentId = manifest.agentId
            this.agentName = manifest.agentName
            this.pid = this@SandboxContext.pid
            this.uid = isolatedUid
            this.state = this@SandboxContext.state.ordinal
            this.isPaused = (this@SandboxContext.state == SandboxState.PAUSED)
            this.cpuTimeMs = 0  // TODO: 从 /proc/$pid/stat 读取
            this.memoryBytes = 0  // TODO: 从 /proc/$pid/status 读取
            this.networkBytesIn = 0  // TODO: 从 netd 统计
            this.networkBytesOut = 0
            this.totalCalls = this@SandboxContext.totalCalls.toInt()
            this.remainingCalls = handle.remainingCalls
            this.totalWalltimeMs = this@SandboxContext.totalWalltimeMs
            this.remainingWalltimeMs = handle.remainingWalltimeMs
            this.grantedCapabilities = handle.grantedCapabilities
            this.consentRevoked = this@SandboxContext.consentRevoked
            this.totalActionRequests = 0  // TODO: 从审计服务统计
            this.totalActionDenied = 0
            this.totalAuditEvents = 0
            this.createdAtMs = this@SandboxContext.createdAtMs
            this.lastActiveMs = this@SandboxContext.lastActiveMs
        }
    }
}

/**
 * 沙箱状态枚举
 */
enum class SandboxState {
    INITIALIZING,  // 初始化中
    RUNNING,       // 运行中
    PAUSED,        // 已暂停
    STOPPED        // 已停止
}
