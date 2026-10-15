package com.ainative.brain.prediction

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ainative.audit.IAgentAuditService
import com.ainative.context.IAgentContextService
import com.ainative.events.IAgentEventStreamService
import com.ainative.memory.IAgentMemoryService
import kotlinx.coroutines.*

/**
 * S1 操作意图预测服务
 * 
 * 集成所有预测组件，提供统一的预测服务接口：
 * 1. 观察系统事件和上下文变化
 * 2. 提取状态特征并预测下一步动作
 * 3. 门控判断后执行预取
 * 4. 管理可回收资源池
 * 5. 收集经验并本地训练
 * 
 * @author AI Native OS Team
 * @since A5
 */
class PredictionService(
    private val context: Context
) {
    // 核心组件
    private val stateExtractor: StateExtractor
    private val rlEngine: RLInferenceEngine
    private val placeholderEngine: PlaceholderRLEngine
    private val gateControl: GateControl
    private val recyclePool: RecyclePool
    private val localTrainer: LocalTrainer
    private val actionSpaceManager: ActionSpaceManager
    private val rewardCalculator: RewardCalculator
    
    // 系统服务（需要注入）
    private var contextService: IAgentContextService? = null
    private var eventStreamService: IAgentEventStreamService? = null
    private var memoryService: IAgentMemoryService? = null
    private var auditService: IAgentAuditService? = null
    
    // 配置
    private val policy: PrefetchPolicy
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 状态
    private var isRunning = false
    private val pendingPredictions = mutableMapOf<String, PendingPrediction>()
    
    companion object {
        const val MODEL_PATH = "models/s1_rl_policy.tflite"
        const val PREDICTION_INTERVAL_MS = 5000L  // 每 5 秒预测一次
        
        private var instance: PredictionService? = null
        
        fun getInstance(context: Context): PredictionService {
            return instance ?: synchronized(this) {
                instance ?: PredictionService(context).also { instance = it }
            }
        }
    }
    
    init {
        val embeddingProvider = EmbeddingProvider(context)
        stateExtractor = StateExtractor(context, embeddingProvider)
        
        // 尝试加载真实 RL 模型，失败则使用占位引擎
        rlEngine = try {
            RLInferenceEngine(context, MODEL_PATH)
        } catch (e: Exception) {
            android.util.Log.w("PredictionService", "Failed to load RL model, using placeholder", e)
            null
        } as RLInferenceEngine
        
        // 白名单 App（TODO: 从配置加载）
        val whitelistApps = ActionSpaceManager.SYSTEM_APPS + ActionSpaceManager.COMMON_APPS
        actionSpaceManager = ActionSpaceManager(whitelistApps)
        placeholderEngine = PlaceholderRLEngine(context, actionSpaceManager)
        
        policy = PrefetchPolicy()
        gateControl = GateControl(context, policy)
        recyclePool = RecyclePool(context)
        localTrainer = LocalTrainer(context, MODEL_PATH)
        rewardCalculator = RewardCalculator()
    }
    
    /**
     * 启动预测服务
     */
    fun start() {
        if (isRunning) return
        
        isRunning = true
        android.util.Log.i("PredictionService", "Prediction service started")
        
        // 定期预测
        schedulePrediction()
    }
    
    /**
     * 停止预测服务
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        recyclePool.clear()
        localTrainer.shutdown()
        
        android.util.Log.i("PredictionService", "Prediction service stopped")
    }
    
    /**
     * 调度预测任务
     */
    private fun schedulePrediction() {
        if (!isRunning) return
        
        scope.launch {
            try {
                predict()
            } catch (e: Exception) {
                android.util.Log.e("PredictionService", "Prediction failed", e)
            }
        }
        
        // 下一次预测
        handler.postDelayed({ schedulePrediction() }, PREDICTION_INTERVAL_MS)
    }
    
    /**
     * 执行预测
     */
    private suspend fun predict() = withContext(Dispatchers.Default) {
        // 1. 获取当前上下文
        val contextFrame = getCurrentContext() ?: return@withContext
        
        // 2. 提取状态特征
        val stateFeatures = stateExtractor.extractFeatures(contextFrame)
        val stateVector = stateExtractor.featuresToVector(stateFeatures)
        
        // 3. RL 模型推理
        val predictionResult = try {
            rlEngine.predict(stateVector)
        } catch (e: Exception) {
            // 回退到占位引擎
            placeholderEngine.predict(stateVector)
        }
        
        // 4. 解码动作
        val predictedAction = actionSpaceManager.decodeAction(
            predictionResult.actionIndex,
            predictionResult.confidence
        )
        
        // 5. 门控判断
        val gateDecision = gateControl.shouldExecute(predictedAction, contextFrame)
        
        if (!gateDecision.allowed) {
            // 不执行，但记录用于学习
            android.util.Log.d("PredictionService", "Prediction blocked: ${gateDecision.reason}")
            auditPrediction(predictedAction, gateDecision, null)
            return@withContext
        }
        
        // 6. 执行预取
        executePrefetch(predictedAction, stateVector, predictionResult, contextFrame)
    }
    
    /**
     * 执行预取动作
     */
    private fun executePrefetch(
        predictedAction: PredictedAction,
        stateVector: FloatArray,
        predictionResult: PredictionResult,
        contextFrame: ContextFrame
    ) {
        // 创建可回收资源
        val resource = RecyclableResource.fromPredictedAction(predictedAction)
        
        if (!recyclePool.create(resource)) {
            android.util.Log.w("PredictionService", "Failed to create resource: ${resource.resourceId}")
            return
        }
        
        // 记录待验证的预测
        val pendingPrediction = PendingPrediction(
            predictedAction = predictedAction,
            stateVector = stateVector,
            predictionResult = predictionResult,
            resourceId = resource.resourceId,
            timestamp = System.currentTimeMillis()
        )
        pendingPredictions[resource.resourceId] = pendingPrediction
        
        // 执行实际预取（占位实现）
        performPrefetch(predictedAction, resource)
        
        android.util.Log.i("PredictionService", 
            "Prefetch executed: ${predictedAction.actionType} for ${predictedAction.targetApp} (confidence=${predictedAction.confidence})")
    }
    
    /**
     * 实际执行预取（占位实现）
     */
    private fun performPrefetch(action: PredictedAction, resource: RecyclableResource) {
        // TODO: 集成到动作服务，执行真实的预取动作
        when (action.actionType) {
            PrefetchActionType.PREWARM_APP -> {
                // 预热 App 进程
                android.util.Log.d("PredictionService", "Prewarm app: ${action.targetApp}")
            }
            PrefetchActionType.PRECREATE_ACTIVITY -> {
                // 预创建 Activity
                android.util.Log.d("PredictionService", "Precreate activity: ${action.targetApp}/${action.targetActivity}")
            }
            PrefetchActionType.PREFETCH_DATA -> {
                // 预取数据
                android.util.Log.d("PredictionService", "Prefetch data: ${action.targetResource}")
            }
            PrefetchActionType.PRELOAD_MACRO -> {
                // 预装载宏
                android.util.Log.d("PredictionService", "Preload macro: ${action.targetResource}")
            }
            PrefetchActionType.PRESUBSCRIBE_UI -> {
                // 预订阅 UI 树
                android.util.Log.d("PredictionService", "Presubscribe UI: ${action.targetApp}")
            }
            else -> {}
        }
    }
    
    /**
     * 通知实际动作发生（用于验证预测）
     */
    fun onActualAction(actualAction: ActualAction) {
        scope.launch {
            // 查找匹配的预测
            val matchedPredictions = pendingPredictions.values.filter { pending ->
                actualAction.matches(pending.predictedAction) &&
                System.currentTimeMillis() - pending.timestamp < 60000  // 60秒内
            }
            
            matchedPredictions.forEach { pending ->
                // 命中预测
                val hit = recyclePool.hit(pending.resourceId)
                if (hit) {
                    val timeDelta = actualAction.timestamp - pending.timestamp
                    
                    // 计算奖励
                    val reward = rewardCalculator.calculateReward(
                        predicted = pending.predictedAction,
                        actual = actualAction,
                        resourceStatus = ResourceStatus.HIT,
                        timeDeltaMs = timeDelta
                    )
                    
                    // 添加经验用于训练
                    val experience = Experience(
                        state = pending.stateVector,
                        action = pending.predictionResult.actionIndex,
                        actionProb = pending.predictionResult.confidence,
                        reward = reward,
                        nextState = FloatArray(StateExtractor.STATE_VECTOR_DIM),  // TODO: 获取下一状态
                        stateValue = pending.predictionResult.stateValue
                    )
                    localTrainer.addExperience(experience)
                    
                    // 更新统计
                    recyclePool.updateMetrics(pending.predictedAction.confidence, timeDelta)
                    
                    android.util.Log.i("PredictionService", 
                        "Prediction HIT: ${pending.predictedAction.actionType} for ${actualAction.targetApp}, latency reduced by ${timeDelta}ms")
                    
                    pendingPredictions.remove(pending.resourceId)
                }
            }
        }
    }
    
    /**
     * 获取当前上下文（占位实现）
     */
    private fun getCurrentContext(): ContextFrame? {
        // TODO: 从 IAgentContextService 获取真实上下文
        return ContextFrame(
            foregroundApp = "com.android.launcher3",
            lastInteractionNode = null,
            recentEvents = emptyList(),
            recentActions = emptyList(),
            currentEntityId = null,
            locationHint = "home"
        )
    }
    
    /**
     * 审计预测
     */
    private fun auditPrediction(
        predictedAction: PredictedAction,
        gateDecision: GateDecision,
        resourceId: String?
    ) {
        // TODO: 集成到 IAgentAuditService
        android.util.Log.d("PredictionService", 
            "Audit: action=${predictedAction.actionType}, allowed=${gateDecision.allowed}, reason=${gateDecision.reason}")
    }
    
    /**
     * 获取统计指标
     */
    fun getMetrics(): PredictionMetrics {
        return recyclePool.getMetrics()
    }
    
    /**
     * 设置系统服务
     */
    fun setContextService(service: IAgentContextService) {
        this.contextService = service
    }
    
    fun setEventStreamService(service: IAgentEventStreamService) {
        this.eventStreamService = service
    }
    
    fun setMemoryService(service: IAgentMemoryService) {
        this.memoryService = service
    }
    
    fun setAuditService(service: IAgentAuditService) {
        this.auditService = service
    }
}

/**
 * 待验证的预测
 */
private data class PendingPrediction(
    val predictedAction: PredictedAction,
    val stateVector: FloatArray,
    val predictionResult: PredictionResult,
    val resourceId: String,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PendingPrediction

        if (predictedAction != other.predictedAction) return false
        if (!stateVector.contentEquals(other.stateVector)) return false
        if (predictionResult != other.predictionResult) return false
        if (resourceId != other.resourceId) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = predictedAction.hashCode()
        result = 31 * result + stateVector.contentHashCode()
        result = 31 * result + predictionResult.hashCode()
        result = 31 * result + resourceId.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
