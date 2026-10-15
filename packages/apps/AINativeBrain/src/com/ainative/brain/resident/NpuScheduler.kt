package com.ainative.brain.resident

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B4.1 常驻小模型域 - NPU 调度器
 * 
 * 职责：
 * 1. 管理 NPU 小核的功耗模式（高性能、平衡、低功耗）
 * 2. 根据系统状态自动切换功耗档位
 * 3. 协调常驻模型的挂起和恢复
 * 4. 监控 NPU 使用情况和温度
 * 
 * 设计要点：
 * - 三级功耗档位：亮屏前台（全速）、锁屏（降频）、深睡（挂起）
 * - 智能切换：基于屏幕状态、电池电量、温度
 * - 优先保证低延迟体验，其次节省功耗
 * - 支持手动强制模式（测试/调试）
 * 
 * 档位定义（符合 WBS A12.2b）：
 * - HIGH_PERFORMANCE：亮屏前台，全手势检测，NPU 全速
 * - BALANCED：锁屏浅睡，仅关键检测（唤醒词），NPU 降频
 * - LOW_POWER：深睡/敏感场景，挂起所有常驻模型，NPU 休眠
 */
class NpuScheduler(
    private val context: Context,
    private val modelManager: ResidentModelManager
) {
    
    companion object {
        private const val TAG = "NpuScheduler"
        
        // 功耗档位
        const val MODE_HIGH_PERFORMANCE = 0  // 高性能（亮屏前台）
        const val MODE_BALANCED = 1  // 平衡（锁屏）
        const val MODE_LOW_POWER = 2  // 低功耗（深睡）
        
        // 档位切换条件
        private const val LOW_BATTERY_THRESHOLD = 20  // 低电量阈值 20%
        private const val CRITICAL_BATTERY_THRESHOLD = 10  // 极低电量阈值 10%
        private const val HIGH_TEMP_THRESHOLD = 45.0f  // 高温阈值 45°C
        
        // 监控间隔
        private const val MONITOR_INTERVAL_MS = 5000L  // 5秒监控一次
    }
    
    // 当前功耗模式
    @Volatile
    private var currentMode = MODE_HIGH_PERFORMANCE
    
    // 运行状态
    private val isRunning = AtomicBoolean(false)
    
    // 强制模式（调试用）
    @Volatile
    private var forcedMode: Int? = null
    
    // 监控调度器
    private var monitorScheduler: ScheduledExecutorService? = null
    
    // 系统服务
    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    
    private val batteryManager: BatteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }
    
    // 统计信息
    private var modeSwitchCount = 0L
    private var totalSuspendTime = 0L
    private var lastSuspendTime = 0L
    
    /**
     * 启动 NPU 调度器
     */
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "NpuScheduler already running")
            return
        }
        
        Log.i(TAG, "Starting NPU scheduler...")
        
        try {
            // 1. 初始化当前模式（根据屏幕状态）
            currentMode = determineInitialMode()
            applyMode(currentMode)
            
            // 2. 启动监控线程
            startMonitoring()
            
            isRunning.set(true)
            Log.i(TAG, "NPU scheduler started in mode: ${getModeName(currentMode)}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NPU scheduler", e)
            throw e
        }
    }
    
    /**
     * 停止 NPU 调度器
     */
    fun stop() {
        if (!isRunning.get()) {
            return
        }
        
        Log.i(TAG, "Stopping NPU scheduler...")
        
        try {
            // 停止监控
            stopMonitoring()
            
            isRunning.set(false)
            Log.i(TAG, "NPU scheduler stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop NPU scheduler", e)
        }
    }
    
    /**
     * 确定初始功耗模式
     */
    private fun determineInitialMode(): Int {
        val isScreenOn = powerManager.isInteractive
        val batteryLevel = getBatteryLevel()
        
        return when {
            !isScreenOn -> MODE_BALANCED  // 锁屏默认平衡模式
            batteryLevel <= CRITICAL_BATTERY_THRESHOLD -> MODE_LOW_POWER  // 极低电量
            batteryLevel <= LOW_BATTERY_THRESHOLD -> MODE_BALANCED  // 低电量
            else -> MODE_HIGH_PERFORMANCE  // 正常
        }
    }
    
    /**
     * 启动监控
     */
    private fun startMonitoring() {
        monitorScheduler = Executors.newSingleThreadScheduledExecutor()
        
        monitorScheduler?.scheduleAtFixedRate(
            { monitorSystemState() },
            0,
            MONITOR_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }
    
    /**
     * 停止监控
     */
    private fun stopMonitoring() {
        monitorScheduler?.shutdown()
        try {
            monitorScheduler?.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Monitor scheduler shutdown interrupted")
        }
        monitorScheduler = null
    }
    
    /**
     * 监控系统状态
     */
    private fun monitorSystemState() {
        try {
            // 如果有强制模式，不自动切换
            if (forcedMode != null) {
                return
            }
            
            // 1. 收集系统状态
            val isScreenOn = powerManager.isInteractive
            val batteryLevel = getBatteryLevel()
            val temperature = getDeviceTemperature()
            val isCharging = isCharging()
            
            Log.d(TAG, "System state: screen=$isScreenOn, battery=$batteryLevel%, " +
                    "temp=$temperature°C, charging=$isCharging")
            
            // 2. 决定目标模式
            val targetMode = determineTargetMode(
                isScreenOn, batteryLevel, temperature, isCharging
            )
            
            // 3. 如果模式变化，执行切换
            if (targetMode != currentMode) {
                switchMode(targetMode)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error monitoring system state", e)
        }
    }
    
    /**
     * 决定目标功耗模式
     */
    private fun determineTargetMode(
        isScreenOn: Boolean,
        batteryLevel: Int,
        temperature: Float,
        isCharging: Boolean
    ): Int {
        // 规则优先级（从高到低）：
        // 1. 极低电量 → 低功耗
        // 2. 高温 → 低功耗
        // 3. 锁屏且低电量 → 低功耗
        // 4. 锁屏 → 平衡
        // 5. 亮屏充电 → 高性能
        // 6. 亮屏低电量 → 平衡
        // 7. 亮屏正常 → 高性能
        
        return when {
            // 极低电量或高温：强制低功耗
            batteryLevel <= CRITICAL_BATTERY_THRESHOLD -> {
                Log.i(TAG, "Critical battery: $batteryLevel% → LOW_POWER")
                MODE_LOW_POWER
            }
            temperature >= HIGH_TEMP_THRESHOLD -> {
                Log.i(TAG, "High temperature: $temperature°C → LOW_POWER")
                MODE_LOW_POWER
            }
            
            // 锁屏场景
            !isScreenOn -> {
                if (batteryLevel <= LOW_BATTERY_THRESHOLD) {
                    Log.d(TAG, "Screen off + low battery → LOW_POWER")
                    MODE_LOW_POWER
                } else {
                    Log.d(TAG, "Screen off → BALANCED")
                    MODE_BALANCED
                }
            }
            
            // 亮屏场景
            isScreenOn -> {
                when {
                    isCharging -> {
                        Log.d(TAG, "Screen on + charging → HIGH_PERFORMANCE")
                        MODE_HIGH_PERFORMANCE
                    }
                    batteryLevel <= LOW_BATTERY_THRESHOLD -> {
                        Log.d(TAG, "Screen on + low battery → BALANCED")
                        MODE_BALANCED
                    }
                    else -> {
                        Log.d(TAG, "Screen on + normal battery → HIGH_PERFORMANCE")
                        MODE_HIGH_PERFORMANCE
                    }
                }
            }
            
            else -> currentMode  // 保持当前模式
        }
    }
    
    /**
     * 切换功耗模式
     */
    private fun switchMode(targetMode: Int) {
        if (targetMode == currentMode) {
            return
        }
        
        Log.i(TAG, "Switching NPU mode: ${getModeName(currentMode)} → ${getModeName(targetMode)}")
        
        val switchStartTime = System.currentTimeMillis()
        
        try {
            applyMode(targetMode)
            currentMode = targetMode
            modeSwitchCount++
            
            val switchLatency = System.currentTimeMillis() - switchStartTime
            Log.i(TAG, "Mode switched successfully in ${switchLatency}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch mode", e)
        }
    }
    
    /**
     * 应用功耗模式
     */
    private fun applyMode(mode: Int) {
        when (mode) {
            MODE_HIGH_PERFORMANCE -> {
                // 高性能模式：所有常驻模型全速运行
                Log.d(TAG, "Applying HIGH_PERFORMANCE mode")
                
                // 恢复所有挂起的模型
                modelManager.getLoadedModels().forEach { model ->
                    if (model.state == ResidentModelManager.STATE_SUSPENDED) {
                        modelManager.resumeModel(model.modelType)
                    }
                }
                
                // TODO: 设置 NPU 频率为最高档位（通过 HAL）
                // setNpuFrequency(NPU_FREQ_HIGH)
                
                // 记录挂起结束时间
                if (lastSuspendTime > 0) {
                    totalSuspendTime += System.currentTimeMillis() - lastSuspendTime
                    lastSuspendTime = 0
                }
            }
            
            MODE_BALANCED -> {
                // 平衡模式：保持唤醒词检测，挂起其他模型
                Log.d(TAG, "Applying BALANCED mode")
                
                // 恢复唤醒词模型
                modelManager.resumeModel(ResidentModelManager.MODEL_TYPE_WAKE_WORD)
                
                // 挂起意图分类器（按需加载）
                modelManager.suspendModel(ResidentModelManager.MODEL_TYPE_INTENT_CLASSIFIER)
                
                // TODO: 设置 NPU 频率为中档（通过 HAL）
                // setNpuFrequency(NPU_FREQ_MEDIUM)
            }
            
            MODE_LOW_POWER -> {
                // 低功耗模式：挂起所有常驻模型
                Log.d(TAG, "Applying LOW_POWER mode")
                
                // 挂起所有模型
                modelManager.getLoadedModels().forEach { model ->
                    if (model.state == ResidentModelManager.STATE_RUNNING) {
                        modelManager.suspendModel(model.modelType)
                    }
                }
                
                // TODO: 设置 NPU 休眠（通过 HAL）
                // setNpuFrequency(NPU_FREQ_SLEEP)
                
                // 记录挂起开始时间
                if (lastSuspendTime == 0L) {
                    lastSuspendTime = System.currentTimeMillis()
                }
            }
        }
    }
    
    /**
     * 强制设置模式（调试用）
     */
    fun forceMode(mode: Int) {
        if (mode !in listOf(MODE_HIGH_PERFORMANCE, MODE_BALANCED, MODE_LOW_POWER)) {
            throw IllegalArgumentException("Invalid mode: $mode")
        }
        
        Log.i(TAG, "Forcing mode to: ${getModeName(mode)}")
        forcedMode = mode
        switchMode(mode)
    }
    
    /**
     * 取消强制模式
     */
    fun clearForcedMode() {
        Log.i(TAG, "Clearing forced mode")
        forcedMode = null
        
        // 重新评估模式
        val targetMode = determineInitialMode()
        if (targetMode != currentMode) {
            switchMode(targetMode)
        }
    }
    
    /**
     * 获取当前模式
     */
    fun getCurrentMode(): Int = currentMode
    
    /**
     * 获取电池电量
     */
    private fun getBatteryLevel(): Int {
        return try {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery level", e)
            100  // 默认假设满电
        }
    }
    
    /**
     * 获取设备温度
     */
    private fun getDeviceTemperature(): Float {
        // TODO: 实际实现：读取 NPU/CPU 温度传感器
        // 当前简化实现：从系统属性读取（如果可用）
        
        return try {
            val tempStr = android.os.SystemProperties.get("sys.ainative.npu.temperature", "30.0")
            tempStr.toFloat()
        } catch (e: Exception) {
            30.0f  // 默认 30°C
        }
    }
    
    /**
     * 检查是否正在充电
     */
    private fun isCharging(): Boolean {
        return try {
            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get charging status", e)
            false
        }
    }
    
    /**
     * 获取模式名称
     */
    private fun getModeName(mode: Int): String {
        return when (mode) {
            MODE_HIGH_PERFORMANCE -> "HIGH_PERFORMANCE"
            MODE_BALANCED -> "BALANCED"
            MODE_LOW_POWER -> "LOW_POWER"
            else -> "UNKNOWN"
        }
    }
    
    /**
     * 获取统计信息
     */
    fun getStatistics(): NpuSchedulerStatistics {
        return NpuSchedulerStatistics(
            isRunning = isRunning.get(),
            currentMode = getModeName(currentMode),
            forcedMode = forcedMode?.let { getModeName(it) },
            modeSwitchCount = modeSwitchCount,
            totalSuspendTimeMs = totalSuspendTime,
            batteryLevel = getBatteryLevel(),
            deviceTemperature = getDeviceTemperature()
        )
    }
}

/**
 * NPU 调度器统计信息
 */
data class NpuSchedulerStatistics(
    val isRunning: Boolean,
    val currentMode: String,
    val forcedMode: String?,
    val modeSwitchCount: Long,
    val totalSuspendTimeMs: Long,
    val batteryLevel: Int,
    val deviceTemperature: Float
)
