package com.genton.brain

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.genton.brain.lifecycle.BrainBootstrapService
import com.genton.brain.lifecycle.CrashRecoveryService
import com.genton.brain.checkpoint.CheckpointManager
import com.genton.brain.runtime.AgentRuntimeClient
import com.genton.brain.intent.IntentReceiver
import com.genton.brain.intent.TurnManager
import com.genton.brain.event.EventStreamListener
import com.genton.brain.resident.ResidentModelManager
import com.genton.brain.resident.WakeWordDetector
import com.genton.brain.resident.IntentClassifier
import com.genton.brain.resident.NpuScheduler
import com.genton.brain.multiagent.AgentInstanceManager
import com.genton.brain.multiagent.AgentSwitchCoordinator
import com.genton.brain.multiagent.SurfaceManager

/**
 * AI Native OS 大脑特权应用
 * 
 * 职责：
 * 1. 启动自检
 * 2. Crash 恢复（checkpoint resume）
 * 3. 进程管理（主进程 + 子进程隔离）
 * 4. Agent 运行时客户端初始化
 * 5. 多 Agent 托管（B3.3）
 */
class BrainApplication : Application() {
    
    companion object {
        private const val TAG = "BrainApplication"
        
        // 进程名称
        const val PROCESS_MAIN = "com.genton.brain:main"
        const val PROCESS_AGENT_EXECUTOR = "com.genton.brain:agent_executor"
        
        @Volatile
        private var instance: BrainApplication? = null
        
        fun getInstance(): BrainApplication {
            return instance ?: throw IllegalStateException("BrainApplication not initialized")
        }
    }
    
    // 核心组件
    private lateinit var checkpointManager: CheckpointManager
    private lateinit var runtimeClient: AgentRuntimeClient
    
    // B1.2 意图接收模块
    private lateinit var turnManager: TurnManager
    private lateinit var intentReceiver: IntentReceiver
    private lateinit var eventStreamListener: EventStreamListener
    
    // B3.1 会话服务（懒加载，Service 自行管理）
    // AgentSessionService 通过 onBind 获取 IntentReceiver
    
    /**
     * 获取 IntentReceiver 实例（供 AgentSessionService 使用）
     */
    fun getIntentReceiver(): IntentReceiver {
        return intentReceiver
    }
    
    /**
     * 获取 TurnManager 实例
     */
    fun getTurnManager(): TurnManager {
        return turnManager
    }
    
    // B2.1 规划器
    private lateinit var planner: Planner
    
    // B2.2 工具循环
    private lateinit var toolLoop: ToolLoop
    
    // B4.1 常驻小模型域组件
    private lateinit var residentModelManager: ResidentModelManager
    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var npuScheduler: NpuScheduler
    
    // F2.2 DMI 桥接器
    private lateinit var dmiBridge: com.genton.brain.dmi.DmiBridge
    private lateinit var dmiExecutor: com.genton.brain.dmi.DmiExecutor
    
    // B3.3 多 Agent 协调器
    private lateinit var multiAgentCoordinator: com.genton.brain.multiagent.MultiAgentCoordinator
    
    // B3.1 会话服务
    private lateinit var sessionService: com.genton.brain.session.AgentSessionServiceImpl
    
    // B5.2 推理用量计量
    private lateinit var usageCollector: com.genton.brain.airuntime.UsageCollector
    private lateinit var hardwareCounters: com.genton.brain.airuntime.HardwareCounters
    private lateinit var metricsReporter: com.genton.brain.airuntime.MetricsReporter
    
    // 启动状态
    @Volatile
    private var isBootstrapped = false
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        val processName = getCurrentProcessName()
        Log.i(TAG, "BrainApplication started in process: $processName (pid=${Process.myPid()})")
        
        when (processName) {
            PROCESS_MAIN -> {
                // 主进程：启动核心服务
                initializeMainProcess()
            }
            PROCESS_AGENT_EXECUTOR -> {
                // 子进程：Agent 执行器隔离环境
                initializeExecutorProcess()
            }
            else -> {
                Log.w(TAG, "Unknown process: $processName")
            }
        }
    }
    
    /**
     * 初始化主进程
     */
    private fun initializeMainProcess() {
        Log.i(TAG, "Initializing main process...")
        
        try {
            // 1. 启动自检
            val bootstrapService = Intent(this, BrainBootstrapService::class.java)
            startForegroundService(bootstrapService)
            
            // 2. 初始化 Checkpoint 管理器
            checkpointManager = CheckpointManager(this)
            
            // 3. 初始化 Agent 运行时客户端
            runtimeClient = AgentRuntimeClient(this)
            
            // 4. 检查是否有待恢复的 checkpoint（Crash 恢复）
            checkCrashRecovery()
            
            // 5. 初始化意图接收模块（B1.2）
            initializeIntentReceiver()
            
            // 6. 初始化 B2.1 规划器
            initializePlanner()
            
            // 7. 初始化 B2.2 工具循环
            initializeToolLoop()
            
            // 8. 初始化 B4.1 常驻小模型域
            initializeResidentModels()
            
            // 9. 初始化 B3.3 多 Agent 托管
            initializeMultiAgentHosting()
            
            // 10. 初始化 F2.2 DMI 桥接器（已在 initializeIntentReceiver 中初始化）
            
            // 11. 初始化 B3.1 会话服务
            initializeSessionService()
            
            // 12. 初始化 B5.2 推理用量计量回传
            initializeUsageCollector()
            
            isBootstrapped = true
            Log.i(TAG, "Main process initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize main process", e)
            // 记录致命错误到审计日志
            reportFatalError("MainProcessInitFailure", e)
        }
    }
    
    /**
     * 初始化执行器子进程
     */
    private fun initializeExecutorProcess() {
        Log.i(TAG, "Initializing executor process...")
        
        try {
            // 子进程只需要最小化初始化
            // 1. 连接到主进程的运行时服务
            runtimeClient = AgentRuntimeClient(this)
            
            // 2. 等待主进程就绪信号
            waitForMainProcessReady()
            
            Log.i(TAG, "Executor process initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize executor process", e)
            // 子进程初始化失败不应影响主进程
            Process.killProcess(Process.myPid())
        }
    }
    
    /**
     * 检查 Crash 恢复
     */
    private fun checkCrashRecovery() {
        Log.i(TAG, "Checking crash recovery...")
        
        val lastCrashTime = getSharedPreferences("brain_state", MODE_PRIVATE)
            .getLong("last_crash_time", 0)
        
        if (lastCrashTime > 0) {
            val timeSinceCrash = System.currentTimeMillis() - lastCrashTime
            
            if (timeSinceCrash < 5 * 60 * 1000) { // 5 分钟内
                Log.w(TAG, "Detected recent crash (${timeSinceCrash}ms ago), attempting recovery...")
                
                // 启动 Crash 恢复服务
                val recoveryIntent = Intent(this, CrashRecoveryService::class.java)
                recoveryIntent.putExtra("crash_time", lastCrashTime)
                startService(recoveryIntent)
            } else {
                // 清除过期的 crash 标记
                getSharedPreferences("brain_state", MODE_PRIVATE)
                    .edit()
                    .remove("last_crash_time")
                    .apply()
            }
        }
    }
    
    /**
     * 等待主进程就绪
     */
    private fun waitForMainProcessReady() {
        var retryCount = 0
        val maxRetries = 30 // 最多等待 30 秒
        
        while (!isMainProcessReady() && retryCount < maxRetries) {
            Thread.sleep(1000)
            retryCount++
        }
        
        if (retryCount >= maxRetries) {
            throw IllegalStateException("Timeout waiting for main process ready")
        }
        
        Log.i(TAG, "Main process is ready")
    }
    
    /**
     * 检查主进程是否就绪
     */
    private fun isMainProcessReady(): Boolean {
        // 通过检查运行时服务是否可用来判断主进程是否就绪
        return try {
            runtimeClient.ping()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取当前进程名称
     */
    private fun getCurrentProcessName(): String {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.runningAppProcesses
                ?.find { it.pid == Process.myPid() }
                ?.processName
                ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get process name", e)
            "unknown"
        }
    }
    
    /**
     * 报告致命错误
     */
    private fun reportFatalError(errorType: String, error: Exception) {
        try {
            // 记录到本地日志
            getSharedPreferences("brain_errors", MODE_PRIVATE)
                .edit()
                .putString("last_fatal_error", "$errorType: ${error.message}")
                .putLong("last_fatal_error_time", System.currentTimeMillis())
                .apply()
            
            // TODO: 写入审计日志
            // auditService.record(AuditEvent.SYSTEM, "brain_fatal_error", errorType)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report fatal error", e)
        }
    }
    
    /**
     * 初始化意图接收模块（B1.2）
     */
    private fun initializeIntentReceiver() {
        Log.i(TAG, "Initializing intent receiver module...")
        
        try {
            // 1. 初始化 TurnManager
            turnManager = TurnManager(this)
            
            // 2. 初始化 IntentReceiver
            intentReceiver = IntentReceiver(this, turnManager)
            
            // 3. 初始化 EventStreamListener
            eventStreamListener = EventStreamListener(this, intentReceiver)
            
            // 4. 初始化 DMI 桥接器
            dmiBridge = com.genton.brain.dmi.DmiBridge(this)
            dmiBridge.initialize()
            
            // 5. 初始化 DMI 执行器
            dmiExecutor = com.genton.brain.dmi.DmiExecutor(dmiBridge)
            
            Log.i(TAG, "Intent receiver module initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize intent receiver module", e)
            throw e
        }
    }
    
    /**
     * 初始化规划器（B2.1）
     */
    private fun initializePlanner() {
        Log.i(TAG, "Initializing planner...")
        
        try {
            // 1. 创建规划器实例
            planner = Planner(this, turnManager, runtimeClient)
            
            // 2. 将规划器注入到 IntentReceiver（延迟注入避免循环依赖）
            intentReceiver.setPlanner(planner)
            
            Log.i(TAG, "Planner initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize planner", e)
            throw e
        }
    }
    
    /**
     * 获取规划器实例
     */
    fun getPlanner(): Planner {
        if (!::planner.isInitialized) {
            throw IllegalStateException("Planner not initialized")
        }
        return planner
    }
    
    /**
     * 获取意图接收器
     */
    fun getIntentReceiver(): IntentReceiver {
        if (!::intentReceiver.isInitialized) {
            throw IllegalStateException("IntentReceiver not initialized")
        }
        return intentReceiver
    }
    
    /**
     * 获取 Turn 管理器
     */
    fun getTurnManager(): TurnManager {
        if (!::turnManager.isInitialized) {
            throw IllegalStateException("TurnManager not initialized")
        }
        return turnManager
    }
    
    /**
     * 获取事件流监听器
     */
    fun getEventStreamListener(): EventStreamListener {
        if (!::eventStreamListener.isInitialized) {
            throw IllegalStateException("EventStreamListener not initialized")
        }
        return eventStreamListener
    }
    
    /**
     * 获取 Checkpoint 管理器
     */
    fun getCheckpointManager(): CheckpointManager {
        if (!::checkpointManager.isInitialized) {
            throw IllegalStateException("CheckpointManager not initialized")
        }
        return checkpointManager
    }
    
    /**
     * 获取 Agent 运行时客户端
     */
    fun getRuntimeClient(): AgentRuntimeClient {
        if (!::runtimeClient.isInitialized) {
            throw IllegalStateException("AgentRuntimeClient not initialized")
        }
        return runtimeClient
    }
    
    /**
     * 检查是否已启动
     */
    fun isBootstrapped(): Boolean = isBootstrapped
    
    /**
     * 初始化常驻小模型域（B4.1）
     */
    private fun initializeResidentModels() {
        Log.i(TAG, "Initializing resident model domain...")
        
        try {
            // 1. 常驻模型管理器
            residentModelManager = ResidentModelManager(this)
            
            // 2. 唤醒词检测器
            wakeWordDetector = WakeWordDetector(residentModelManager)
            
            // 3. 意图分类器
            intentClassifier = IntentClassifier(residentModelManager)
            
            // 4. NPU 调度器
            npuScheduler = NpuScheduler(this, residentModelManager)
            
            // 5. 启动常驻服务
            residentModelManager.start()
            npuScheduler.start()
            
            // 6. 根据功耗模式决定是否启动唤醒词检测（当前默认启动）
            wakeWordDetector.start()
            
            Log.i(TAG, "Resident model domain initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize resident model domain", e)
            throw e
        }
    }
    
    /**
     * 获取常驻模型管理器
     */
    fun getResidentModelManager(): ResidentModelManager {
        if (!::residentModelManager.isInitialized) {
            throw IllegalStateException("ResidentModelManager not initialized")
        }
        return residentModelManager
    }
    
    /**
     * 获取唤醒词检测器
     */
    fun getWakeWordDetector(): WakeWordDetector {
        if (!::wakeWordDetector.isInitialized) {
            throw IllegalStateException("WakeWordDetector not initialized")
        }
        return wakeWordDetector
    }
    
    /**
     * 获取意图分类器
     */
    fun getIntentClassifier(): IntentClassifier {
        if (!::intentClassifier.isInitialized) {
            throw IllegalStateException("IntentClassifier not initialized")
        }
        return intentClassifier
    }
    
    /**
     * 获取 NPU 调度器
     */
    fun getNpuScheduler(): NpuScheduler {
        if (!::npuScheduler.isInitialized) {
            throw IllegalStateException("NpuScheduler not initialized")
        }
        return npuScheduler
    }
    
    /**
     * 初始化多 Agent 托管（B3.3）
     */
    private fun initializeMultiAgentHosting() {
        Log.i(TAG, "Initializing multi-agent hosting...")
        
        try {
            // 1. Surface 管理器
            surfaceManager = SurfaceManager(this)
            
            // 2. Agent 实例管理器
            agentInstanceManager = AgentInstanceManager(
                context = this,
                checkpointManager = checkpointManager,
                runtimeClient = runtimeClient
            )
            
            // 3. Agent 切换协调器
            agentSwitchCoordinator = AgentSwitchCoordinator(
                context = this,
                instanceManager = agentInstanceManager,
                checkpointManager = checkpointManager,
                runtimeClient = runtimeClient
            )
            
            // 4. 初始化默认 Agent
            val defaultAgent = agentInstanceManager.initializeDefaultAgent()
            if (defaultAgent == null) {
                Log.e(TAG, "Failed to initialize default agent")
                throw IllegalStateException("Default agent initialization failed")
            }
            
            Log.i(TAG, "Multi-agent hosting initialized (default agent: ${defaultAgent.agentId})")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize multi-agent hosting", e)
            throw e
        }
    }
    
    /**
     * 获取 Agent 实例管理器
     */
    fun getAgentInstanceManager(): AgentInstanceManager {
        if (!::agentInstanceManager.isInitialized) {
            throw IllegalStateException("AgentInstanceManager not initialized")
        }
        return agentInstanceManager
    }
    
    /**
     * 获取 Agent 切换协调器
     */
    fun getAgentSwitchCoordinator(): AgentSwitchCoordinator {
        if (!::agentSwitchCoordinator.isInitialized) {
            throw IllegalStateException("AgentSwitchCoordinator not initialized")
        }
        return agentSwitchCoordinator
    }
    
    /**
     * 获取 Surface 管理器
     */
    fun getSurfaceManager(): SurfaceManager {
        if (!::surfaceManager.isInitialized) {
            throw IllegalStateException("SurfaceManager not initialized")
        }
        return surfaceManager
    }
    
    /**
     * 获取 DmiExecutor 实例
     */
    fun getDmiExecutor(): com.genton.brain.dmi.DmiExecutor {
        return dmiExecutor
    }
    
    /**
     * 获取 MultiAgentCoordinator 实例
     */
    fun getMultiAgentCoordinator(): com.genton.brain.multiagent.MultiAgentCoordinator {
        return multiAgentCoordinator
    }
    
    /**
     * 获取会话服务实例
     */
    fun getSessionService(): com.genton.brain.session.AgentSessionServiceImpl {
        if (!::sessionService.isInitialized) {
            throw IllegalStateException("SessionService not initialized")
        }
        return sessionService
    }
    
    /**
     * 初始化会话服务（B3.1）
     */
    private fun initializeSessionService() {
        Log.i(TAG, "Initializing session service...")
        
        try {
            sessionService = com.genton.brain.session.AgentSessionServiceImpl(this, this)
            
            Log.i(TAG, "Session service initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize session service", e)
            throw e
        }
    }
    
    /**
     * 初始化推理用量计量收集器（B5.2）
     */
    private fun initializeUsageCollector() {
        Log.i(TAG, "Initializing usage collector...")
        
        try {
            // 1. 初始化硬件计数器
            hardwareCounters = com.genton.brain.airuntime.HardwareCounters()
            
            // 2. 初始化用量收集器
            usageCollector = com.genton.brain.airuntime.UsageCollector(hardwareCounters)
            
            // 3. 初始化计量回传器
            metricsReporter = com.genton.brain.airuntime.MetricsReporter(this)
            
            // 4. 打印硬件计数器可用性状态
            Log.i(TAG, "Hardware counters: ${hardwareCounters.getAvailabilityStatus()}")
            
            Log.i(TAG, "Usage collector initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize usage collector", e)
            throw e
        }
    }
    
    /**
     * 获取用量收集器
     */
    fun getUsageCollector(): com.genton.brain.airuntime.UsageCollector {
        if (!::usageCollector.isInitialized) {
            throw IllegalStateException("UsageCollector not initialized")
        }
        return usageCollector
    }
    
    /**
     * 获取硬件计数器
     */
    fun getHardwareCounters(): com.genton.brain.airuntime.HardwareCounters {
        if (!::hardwareCounters.isInitialized) {
            throw IllegalStateException("HardwareCounters not initialized")
        }
        return hardwareCounters
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "BrainApplication terminated")
    }
}
