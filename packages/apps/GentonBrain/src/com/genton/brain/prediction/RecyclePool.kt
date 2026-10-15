package com.genton.brain.prediction

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * 可回收预创建池
 * 
 * 管理所有预取资源的生命周期：
 * 1. 创建预取资源（Activity/进程/数据）
 * 2. 追踪资源状态（PENDING/HIT/EXPIRED/RECYCLED）
 * 3. 自动回收未命中的资源（TTL 到期）
 * 4. 统计命中率和浪费率
 * 
 * @author AI Native OS Team
 * @since A5
 */
class RecyclePool(private val context: Context) {
    private val resources = ConcurrentHashMap<String, RecyclableResource>()
    private val handler = Handler(Looper.getMainLooper())
    private val metrics = PredictionMetrics()
    
    companion object {
        const val DEFAULT_TTL_MS = 60_000L       // 默认 60 秒过期
        const val PREWARM_TTL_MS = 30_000L       // 预热进程 30 秒
        const val PREFETCH_DATA_TTL_MS = 120_000L // 预取数据 2 分钟
    }
    
    /**
     * 创建预取资源
     */
    fun create(resource: RecyclableResource): Boolean {
        // 检查资源是否已存在
        if (resources.containsKey(resource.resourceId)) {
            return false
        }
        
        resources[resource.resourceId] = resource
        metrics.totalPredictions++
        
        // 调度自动回收
        scheduleRecycle(resource)
        
        return true
    }
    
    /**
     * 命中资源（预测成功）
     */
    fun hit(resourceId: String): Boolean {
        val resource = resources[resourceId] ?: return false
        
        synchronized(resource) {
            if (resource.status == ResourceStatus.PENDING) {
                resource.status = ResourceStatus.HIT
                resource.hitAt = System.currentTimeMillis()
                metrics.hits++
                
                // 取消回收调度
                handler.removeCallbacksAndMessages(resource)
                
                return true
            }
        }
        
        return false
    }
    
    /**
     * 主动回收资源
     */
    fun recycle(resourceId: String): Boolean {
        val resource = resources.remove(resourceId) ?: return false
        return doRecycle(resource)
    }
    
    /**
     * 调度自动回收
     */
    private fun scheduleRecycle(resource: RecyclableResource) {
        handler.postDelayed({
            synchronized(resource) {
                if (resource.status == ResourceStatus.PENDING) {
                    // 未命中，标记为过期并回收
                    resource.status = ResourceStatus.EXPIRED
                    metrics.misses++
                    doRecycle(resource)
                    resources.remove(resource.resourceId)
                }
            }
        }, resource.ttl)
    }
    
    /**
     * 执行资源回收
     */
    private fun doRecycle(resource: RecyclableResource): Boolean {
        try {
            when (resource.type) {
                PrefetchActionType.PRECREATE_ACTIVITY -> {
                    // 回收预创建的 Activity
                    destroyActivity(resource.targetApp, resource.targetActivity)
                }
                PrefetchActionType.PREWARM_APP -> {
                    // 回收预热的进程
                    killBackgroundProcess(resource.targetApp)
                }
                PrefetchActionType.PREFETCH_DATA -> {
                    // 清除预取的数据缓存
                    clearCache(resource.resourceId)
                }
                PrefetchActionType.PRELOAD_MACRO -> {
                    // 卸载预装载的宏/DMI
                    unloadMacro(resource.targetResource)
                }
                PrefetchActionType.PRESUBSCRIBE_UI -> {
                    // 取消 UI 树订阅
                    unsubscribeUiTree(resource.targetApp)
                }
                else -> {}
            }
            
            resource.status = ResourceStatus.RECYCLED
            resource.recycledAt = System.currentTimeMillis()
            
            return true
        } catch (e: Exception) {
            // 回收失败，记录错误
            android.util.Log.e("RecyclePool", "Failed to recycle resource: ${resource.resourceId}", e)
            return false
        }
    }
    
    /**
     * 获取资源状态
     */
    fun getStatus(resourceId: String): ResourceStatus? {
        return resources[resourceId]?.status
    }
    
    /**
     * 获取所有资源
     */
    fun getAllResources(): List<RecyclableResource> {
        return resources.values.toList()
    }
    
    /**
     * 获取统计指标
     */
    fun getMetrics(): PredictionMetrics {
        synchronized(metrics) {
            return metrics.copy(
                avgConfidence = if (metrics.totalPredictions > 0) {
                    metrics.totalConfidence / metrics.totalPredictions
                } else 0.0f,
                avgLatencyReduction = if (metrics.hits > 0) {
                    metrics.totalLatencyReduction / metrics.hits
                } else 0L
            )
        }
    }
    
    /**
     * 更新统计指标
     */
    fun updateMetrics(confidence: Float, latencyReduction: Long) {
        synchronized(metrics) {
            metrics.totalConfidence += confidence
            metrics.totalLatencyReduction += latencyReduction
        }
    }
    
    /**
     * 清理所有过期资源
     */
    fun cleanupExpired() {
        val expiredResources = resources.values.filter { 
            it.status == ResourceStatus.EXPIRED ||
            (it.status == ResourceStatus.PENDING && 
             System.currentTimeMillis() - it.createdAt > it.ttl * 2)
        }
        
        expiredResources.forEach { resource ->
            doRecycle(resource)
            resources.remove(resource.resourceId)
        }
    }
    
    /**
     * 清空所有资源（用于测试或重置）
     */
    fun clear() {
        resources.values.forEach { doRecycle(it) }
        resources.clear()
        handler.removeCallbacksAndMessages(null)
    }
    
    // ==================== 资源回收实现（占位） ====================
    
    private fun destroyActivity(packageName: String, activityName: String?) {
        // TODO: 集成 ActivityManager，销毁预创建的 Activity
        android.util.Log.d("RecyclePool", "Destroy activity: $packageName/$activityName")
    }
    
    private fun killBackgroundProcess(packageName: String) {
        // TODO: 集成 ActivityManager，终止后台进程
        android.util.Log.d("RecyclePool", "Kill background process: $packageName")
    }
    
    private fun clearCache(resourceId: String) {
        // TODO: 清除数据缓存
        android.util.Log.d("RecyclePool", "Clear cache: $resourceId")
    }
    
    private fun unloadMacro(macroId: String?) {
        // TODO: 卸载宏/DMI
        android.util.Log.d("RecyclePool", "Unload macro: $macroId")
    }
    
    private fun unsubscribeUiTree(packageName: String) {
        // TODO: 取消 UI 树订阅
        android.util.Log.d("RecyclePool", "Unsubscribe UI tree: $packageName")
    }
}

/**
 * 可回收资源
 */
data class RecyclableResource(
    val resourceId: String,
    val type: PrefetchActionType,
    val targetApp: String,
    val targetActivity: String? = null,
    val targetResource: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val ttl: Long = RecyclePool.DEFAULT_TTL_MS,
    var status: ResourceStatus = ResourceStatus.PENDING,
    var hitAt: Long? = null,
    var recycledAt: Long? = null
) {
    companion object {
        fun fromPredictedAction(action: PredictedAction): RecyclableResource {
            val ttl = when (action.actionType) {
                PrefetchActionType.PREWARM_APP -> RecyclePool.PREWARM_TTL_MS
                PrefetchActionType.PREFETCH_DATA -> RecyclePool.PREFETCH_DATA_TTL_MS
                else -> RecyclePool.DEFAULT_TTL_MS
            }
            
            return RecyclableResource(
                resourceId = generateResourceId(action),
                type = action.actionType,
                targetApp = action.targetApp ?: "",
                targetActivity = action.targetActivity,
                targetResource = action.targetResource,
                ttl = ttl
            )
        }
        
        private fun generateResourceId(action: PredictedAction): String {
            return "${action.actionType.name}_${action.targetApp}_${System.currentTimeMillis()}"
        }
    }
}

/**
 * 资源状态
 */
enum class ResourceStatus {
    PENDING,    // 已创建，等待命中
    HIT,        // 已命中使用
    EXPIRED,    // 超时未命中
    RECYCLED    // 已回收
}

/**
 * 预测统计指标
 */
data class PredictionMetrics(
    var totalPredictions: Int = 0,
    var hits: Int = 0,                      // 命中次数
    var misses: Int = 0,                    // 未命中次数
    var totalConfidence: Float = 0.0f,      // 累计置信度
    var totalLatencyReduction: Long = 0L,   // 累计延迟降低 (ms)
    var avgConfidence: Float = 0.0f,        // 平均置信度
    var avgLatencyReduction: Long = 0L      // 平均延迟降低 (ms)
) {
    val falsePositives: Int get() = misses  // 误预取（浪费）
    
    val hitRate: Float get() = if (totalPredictions > 0) {
        hits.toFloat() / totalPredictions
    } else 0.0f
    
    val wasteRate: Float get() = if (totalPredictions > 0) {
        falsePositives.toFloat() / totalPredictions
    } else 0.0f
}
