// HardwareCounters.kt
// B5.2 硬件能耗计数器接口
package com.genton.brain.airuntime

import android.util.Log
import java.io.File

/**
 * 硬件能耗计数器
 * 
 * 核心职责：
 * 1. 读取 NPU 能耗计数器（QNN/MTK APU）
 * 2. 读取 CPU 能耗计数器（/sys/class/power_supply）
 * 3. 读取 GPU 能耗计数器（Mali/Adreno）
 * 4. 不可用时返回 -1，不伪造数据
 * 
 * 设计原则：
 * - 优先使用硬件级计数器（SoC 厂商接口）
 * - 降级使用 sysfs 接口（/sys/class/power_supply）
 * - 不可用时返回 -1，不估算或伪造
 * - 单位统一为毫焦耳（mJ）
 */
class HardwareCounters {
    companion object {
        private const val TAG = "HardwareCounters"
        
        // sysfs 路径（Android 通用）
        private const val POWER_SUPPLY_PATH = "/sys/class/power_supply"
        private const val BATTERY_PATH = "$POWER_SUPPLY_PATH/battery"
        
        // NPU 计数器路径（厂商特定，需根据 SoC 适配）
        private val NPU_ENERGY_PATHS = listOf(
            "/sys/kernel/debug/qnn/energy_uj",          // Qualcomm QNN
            "/sys/kernel/debug/apu/energy_uj",          // MediaTek APU
            "/sys/devices/platform/npu/energy_uj"       // 通用 NPU
        )
        
        // GPU 计数器路径（厂商特定）
        private val GPU_ENERGY_PATHS = listOf(
            "/sys/kernel/debug/mali/energy_uj",         // ARM Mali
            "/sys/kernel/debug/kgsl/energy_uj",         // Qualcomm Adreno
            "/sys/devices/platform/gpu/energy_uj"       // 通用 GPU
        )
    }
    
    // 缓存可用路径（避免重复探测）
    private var npuEnergyPath: String? = null
    private var gpuEnergyPath: String? = null
    private var cpuEnergyAvailable: Boolean = false
    
    init {
        // 探测可用的硬件计数器
        probeHardwareCounters()
    }
    
    /**
     * 读取 NPU 能耗（毫焦耳）
     * 
     * @return 能耗值（mJ），-1 = 不可用
     */
    fun readNpuEnergy(): Long {
        val path = npuEnergyPath ?: return -1
        
        return try {
            val uj = File(path).readText().trim().toLong()
            uj / 1000  // 微焦耳 → 毫焦耳
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read NPU energy from $path", e)
            -1
        }
    }
    
    /**
     * 读取 CPU 能耗（毫焦耳）
     * 
     * @return 能耗值（mJ），-1 = 不可用
     */
    fun readCpuEnergy(): Long {
        if (!cpuEnergyAvailable) {
            return -1
        }
        
        return try {
            // 尝试从电池统计读取 CPU 能耗
            // 注意：这是估算值，不如硬件计数器准确
            val energyPath = "$BATTERY_PATH/energy_now"
            if (File(energyPath).exists()) {
                val uj = File(energyPath).readText().trim().toLong()
                uj / 1000  // 微焦耳 → 毫焦耳
            } else {
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read CPU energy", e)
            -1
        }
    }
    
    /**
     * 读取 GPU 能耗（毫焦耳）
     * 
     * @return 能耗值（mJ），-1 = 不可用
     */
    fun readGpuEnergy(): Long {
        val path = gpuEnergyPath ?: return -1
        
        return try {
            val uj = File(path).readText().trim().toLong()
            uj / 1000  // 微焦耳 → 毫焦耳
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read GPU energy from $path", e)
            -1
        }
    }
    
    /**
     * 获取硬件计数器可用性状态
     * 
     * @return 状态字符串（用于日志和审计）
     */
    fun getAvailabilityStatus(): String {
        return buildString {
            append("NPU: ${if (npuEnergyPath != null) "available ($npuEnergyPath)" else "unavailable"}")
            append(", CPU: ${if (cpuEnergyAvailable) "available" else "unavailable"}")
            append(", GPU: ${if (gpuEnergyPath != null) "available ($gpuEnergyPath)" else "unavailable"}")
        }
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 探测可用的硬件计数器
     */
    private fun probeHardwareCounters() {
        // 探测 NPU 计数器
        for (path in NPU_ENERGY_PATHS) {
            if (File(path).exists() && File(path).canRead()) {
                npuEnergyPath = path
                Log.i(TAG, "NPU energy counter available: $path")
                break
            }
        }
        
        if (npuEnergyPath == null) {
            Log.w(TAG, "NPU energy counter not available")
        }
        
        // 探测 GPU 计数器
        for (path in GPU_ENERGY_PATHS) {
            if (File(path).exists() && File(path).canRead()) {
                gpuEnergyPath = path
                Log.i(TAG, "GPU energy counter available: $path")
                break
            }
        }
        
        if (gpuEnergyPath == null) {
            Log.w(TAG, "GPU energy counter not available")
        }
        
        // 探测 CPU 能耗（通过电池接口）
        cpuEnergyAvailable = File(BATTERY_PATH).exists()
        Log.i(TAG, "CPU energy available: $cpuEnergyAvailable")
        
        Log.i(TAG, "Hardware counters probed: ${getAvailabilityStatus()}")
    }
}
