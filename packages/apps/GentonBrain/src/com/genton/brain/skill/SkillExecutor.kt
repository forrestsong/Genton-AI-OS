package com.genton.brain.skill

import android.util.Log
import com.genton.brain.dmi.DmiBridge
import com.genton.brain.dmi.DmiDeterministicValidator
import com.genton.brain.dmi.DmiFallbackManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.*
import kotlin.math.min

/**
 * Skill 执行引擎（F3.1 编排模板实现）
 * 
 * 功能：
 * 1. 解析 Skill 声明（skill-asset-schema.json）
 * 2. 执行 4 种模式：sequential/parallel/conditional/fallback
 * 3. 支持 6 种步骤类型：dmi/macro/skill/system/condition/loop
 * 4. 条件判定、结果聚合、错误重试
 * 5. JSONPath 参数引用与输出映射
 */
class SkillExecutor(
    private val dmiBridge: DmiBridge,
    private val dmiValidator: DmiDeterministicValidator,
    private val fallbackManager: DmiFallbackManager
) {
    
    companion object {
        private const val TAG = "SkillExecutor"
        
        // 执行模式
        const val MODE_SEQUENTIAL = "sequential"
        const val MODE_PARALLEL = "parallel"
        const val MODE_CONDITIONAL = "conditional"
        const val MODE_FALLBACK = "fallback"
        
        // 步骤类型
        const val STEP_TYPE_DMI = "dmi"
        const val STEP_TYPE_MACRO = "macro"
        const val STEP_TYPE_SKILL = "skill"
        const val STEP_TYPE_SYSTEM = "system"
        const val STEP_TYPE_CONDITION = "condition"
        const val STEP_TYPE_LOOP = "loop"
        
        // 错误处理策略
        const val ON_FAILURE_ABORT = "abort"
        const val ON_FAILURE_CONTINUE = "continue"
        const val ON_FAILURE_RETRY = "retry"
        const val ON_FAILURE_FALLBACK = "fallback"
        
        // 结果聚合策略
        const val AGGREGATION_MERGE = "merge"
        const val AGGREGATION_FIRST_SUCCESS = "first_success"
        const val AGGREGATION_ALL_RESULTS = "all_results"
        
        // 结果码
        const val RESULT_SUCCESS = 0
        const val RESULT_STEP_FAILED = 1001
        const val RESULT_TIMEOUT = 1002
        const val RESULT_CONDITION_NOT_MET = 1003
        const val RESULT_AGGREGATION_FAILED = 1004
    }
    
    private val executor = Executors.newCachedThreadPool()
    private val executionContext = ConcurrentHashMap<String, Any>()
    
    // 步骤执行结果（key=步骤 ID），挂载于 executionContext["step"] 供 JSONPath 引用
    private val stepResults = ConcurrentHashMap<String, Any>()
    
    /**
     * 执行 Skill
     * @param skillDeclaration Skill 声明 JSON（符合 skill-asset-schema.json）
     * @param parameters 输入参数
     * @return 执行结果
     */
    fun execute(skillDeclaration: JSONObject, parameters: Map<String, Any>): SkillExecutionResult {
        val skillId = skillDeclaration.getJSONObject("skill").getString("id")
        Log.i(TAG, "执行 Skill: $skillId")
        
        try {
            // 初始化执行上下文
            executionContext.clear()
            stepResults.clear()
            executionContext["input"] = parameters
            executionContext["step"] = stepResults
            
            // 解析执行配置
            val execution = skillDeclaration.getJSONObject("execution")
            val mode = execution.getString("mode")
            val timeout = execution.optInt("timeout", 10000).toLong()
            val steps = execution.getJSONArray("steps")
            
            // 执行步骤
            val result = executeWithTimeout(timeout) {
                when (mode) {
                    MODE_SEQUENTIAL -> executeSequential(steps, execution)
                    MODE_PARALLEL -> executeParallel(steps, execution)
                    MODE_CONDITIONAL -> executeConditional(steps, execution)
                    MODE_FALLBACK -> executeFallback(steps, execution)
                    else -> throw IllegalArgumentException("不支持的执行模式: $mode")
                }
            }
            
            return if (result != null) {
                result
            } else {
                SkillExecutionResult(
                    success = false,
                    errorCode = RESULT_TIMEOUT,
                    errorMessage = "Skill 执行超时（${timeout}ms）",
                    data = emptyMap()
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Skill 执行失败: $skillId", e)
            return SkillExecutionResult(
                success = false,
                errorCode = RESULT_STEP_FAILED,
                errorMessage = e.message ?: "未知错误",
                data = emptyMap()
            )
        }
    }
    
    // ===== 执行模式 =====
    
    private fun executeSequential(steps: JSONArray, execution: JSONObject): SkillExecutionResult {
        val errorHandling = execution.optJSONObject("errorHandling")
        val results = mutableMapOf<String, Any>()
        
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val stepId = step.optString("id", "step_$i")
            
            val stepResult = executeStep(step, errorHandling)
            results[stepId] = stepResult
            stepResults[stepId] = stepResult
            
            if (!stepResult.success) {
                val onFailure = errorHandling?.optString("onStepFailure", ON_FAILURE_ABORT)
                when (onFailure) {
                    ON_FAILURE_ABORT -> {
                        return SkillExecutionResult(
                            success = false,
                            errorCode = RESULT_STEP_FAILED,
                            errorMessage = "步骤 $stepId 失败: ${stepResult.errorMessage}",
                            data = results
                        )
                    }
                    ON_FAILURE_CONTINUE -> {
                        Log.w(TAG, "步骤 $stepId 失败，继续执行: ${stepResult.errorMessage}")
                        continue
                    }
                    ON_FAILURE_RETRY -> {
                        val retryResult = retryStep(step, errorHandling)
                        if (retryResult.success) {
                            results[stepId] = retryResult
                            stepResults[stepId] = retryResult
                        } else {
                            return SkillExecutionResult(
                                success = false,
                                errorCode = RESULT_STEP_FAILED,
                                errorMessage = "步骤 $stepId 重试失败",
                                data = results
                            )
                        }
                    }
                }
            }
        }
        
        return SkillExecutionResult(
            success = true,
            errorCode = RESULT_SUCCESS,
            errorMessage = null,
            data = results
        )
    }
    
    private fun executeParallel(steps: JSONArray, execution: JSONObject): SkillExecutionResult {
        val futures = mutableListOf<Future<Pair<String, StepExecutionResult>>>()
        
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val stepId = step.optString("id", "step_$i")
            
            val future = executor.submit(Callable {
                val result = executeStep(step, execution.optJSONObject("errorHandling"))
                Pair(stepId, result)
            })
            futures.add(future)
        }
        
        val results = mutableMapOf<String, Any>()
        var hasFailure = false
        
        for (future in futures) {
            try {
                val (stepId, result) = future.get()
                results[stepId] = result
                stepResults[stepId] = result
                
                if (!result.success) {
                    hasFailure = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "并行步骤执行异常", e)
                hasFailure = true
            }
        }
        
        // 结果聚合
        val aggregationStrategy = execution.optJSONObject("resultAggregation")
            ?.optString("strategy", AGGREGATION_MERGE) ?: AGGREGATION_MERGE
        
        val aggregatedData = aggregateResults(results, aggregationStrategy)
        
        return SkillExecutionResult(
            success = !hasFailure,
            errorCode = if (hasFailure) RESULT_STEP_FAILED else RESULT_SUCCESS,
            errorMessage = if (hasFailure) "部分并行步骤执行失败" else null,
            data = aggregatedData
        )
    }
    
    private fun executeConditional(steps: JSONArray, execution: JSONObject): SkillExecutionResult {
        // conditional 模式下 steps 应只有一个顶层 condition 步骤
        if (steps.length() != 1) {
            return SkillExecutionResult(
                success = false,
                errorCode = RESULT_STEP_FAILED,
                errorMessage = "conditional 模式应只有一个顶层 condition 步骤",
                data = emptyMap()
            )
        }
        
        val conditionStep = steps.getJSONObject(0)
        return executeStep(conditionStep, execution.optJSONObject("errorHandling"))
    }
    
    private fun executeFallback(steps: JSONArray, execution: JSONObject): SkillExecutionResult {
        // 按序尝试所有步骤，直到有一个成功
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val stepId = step.optString("id", "fallback_$i")
            
            val result = executeStep(step, execution.optJSONObject("errorHandling"))
            stepResults[stepId] = result
            
            if (result.success) {
                return SkillExecutionResult(
                    success = true,
                    errorCode = RESULT_SUCCESS,
                    errorMessage = null,
                    data = mapOf("selectedFallback" to stepId, "result" to result.data)
                )
            } else {
                Log.w(TAG, "Fallback 步骤 $stepId 失败: ${result.errorMessage}")
            }
        }
        
        return SkillExecutionResult(
            success = false,
            errorCode = RESULT_STEP_FAILED,
            errorMessage = "所有 fallback 步骤均失败",
            data = emptyMap()
        )
    }
    
    // ===== 步骤执行 =====
    
    private fun executeStep(step: JSONObject, errorHandling: JSONObject?): StepExecutionResult {
        val stepType = step.getString("type")
        val config = step.getJSONObject("config")
        
        // 检查前置条件
        if (step.has("condition")) {
            val condition = step.getJSONObject("condition")
            if (!evaluateCondition(condition)) {
                return StepExecutionResult(
                    success = false,
                    errorCode = RESULT_CONDITION_NOT_MET,
                    errorMessage = "步骤前置条件不满足",
                    data = emptyMap()
                )
            }
        }
        
        // 执行步骤
        val result = when (stepType) {
            STEP_TYPE_DMI -> executeDmiStep(config)
            STEP_TYPE_MACRO -> executeMacroStep(config)
            STEP_TYPE_SKILL -> executeSkillStep(config)
            STEP_TYPE_SYSTEM -> executeSystemStep(config)
            STEP_TYPE_CONDITION -> executeConditionStep(config)
            STEP_TYPE_LOOP -> executeLoopStep(config)
            else -> throw IllegalArgumentException("不支持的步骤类型: $stepType")
        }
        
        // 输出映射
        if (step.has("outputMapping") && result.success) {
            val outputMapping = step.getJSONObject("outputMapping")
            val mappedData = applyOutputMapping(result.data, outputMapping)
            return result.copy(data = mappedData)
        }
        
        return result
    }
    
    private fun executeDmiStep(config: JSONObject): StepExecutionResult {
        val dmiId = config.getString("dmiId")
        val operation = config.getString("operation")
        val parameters = resolveParameters(config.optJSONObject("parameters") ?: JSONObject())
        val timeout = config.optInt("timeout", 5000)
        
        Log.d(TAG, "执行 DMI: $dmiId.$operation")
        
        // 调用 DMI 桥接器
        val result = dmiBridge.executeBridge(dmiId, operation, parameters)
        
        return StepExecutionResult(
            success = result.resultCode == DmiBridge.RESULT_SUCCESS,
            errorCode = result.resultCode,
            errorMessage = result.errorMessage,
            data = result.data
        )
    }
    
    private fun executeMacroStep(config: JSONObject): StepExecutionResult {
        val macroId = config.getString("macroId")
        val parameters = resolveParameters(config.optJSONObject("parameters") ?: JSONObject())
        
        Log.d(TAG, "执行 Macro: $macroId")
        
        // TODO: 集成 Macro 执行器（F1.1）
        return StepExecutionResult(
            success = false,
            errorCode = 9999,
            errorMessage = "Macro 执行器尚未实现（待 F1.1）",
            data = emptyMap()
        )
    }
    
    private fun executeSkillStep(config: JSONObject): StepExecutionResult {
        val skillId = config.getString("skillId")
        val parameters = resolveParameters(config.optJSONObject("parameters") ?: JSONObject())
        
        Log.d(TAG, "嵌套执行 Skill: $skillId")
        
        // TODO: 加载嵌套 Skill 声明并递归执行
        return StepExecutionResult(
            success = false,
            errorCode = 9999,
            errorMessage = "嵌套 Skill 执行尚未实现",
            data = emptyMap()
        )
    }
    
    private fun executeSystemStep(config: JSONObject): StepExecutionResult {
        val service = config.getString("service")
        val method = config.getString("method")
        val parameters = resolveParameters(config.optJSONObject("parameters") ?: JSONObject())
        
        Log.d(TAG, "执行系统服务: $service.$method")
        
        // 系统步骤统一走 DMI 系统能力桥：service.method → dmi.service.method
        val result = dmiBridge.executeBridge("dmi.$service.$method", method, parameters)
        
        return StepExecutionResult(
            success = result.resultCode == DmiBridge.RESULT_SUCCESS,
            errorCode = result.resultCode,
            errorMessage = result.errorMessage,
            data = result.data
        )
    }
    
    private fun executeConditionStep(config: JSONObject): StepExecutionResult {
        val condition = config.getJSONObject("condition")
        val thenSteps = config.getJSONArray("then")
        val elseSteps = config.optJSONArray("else")
        
        val conditionMet = evaluateCondition(condition)
        Log.d(TAG, "条件判定结果: $conditionMet")
        
        val stepsToExecute = if (conditionMet) thenSteps else (elseSteps ?: JSONArray())
        
        if (stepsToExecute.length() == 0) {
            return StepExecutionResult(
                success = true,
                errorCode = RESULT_SUCCESS,
                errorMessage = null,
                data = mapOf("conditionMet" to conditionMet, "branchExecuted" to "none")
            )
        }
        
        // 顺序执行分支步骤
        val results = mutableMapOf<String, Any>()
        for (i in 0 until stepsToExecute.length()) {
            val step = stepsToExecute.getJSONObject(i)
            val stepId = step.optString("id", "branch_$i")
            
            val result = executeStep(step, null)
            results[stepId] = result
            
            if (!result.success) {
                return StepExecutionResult(
                    success = false,
                    errorCode = result.errorCode,
                    errorMessage = "条件分支步骤 $stepId 失败: ${result.errorMessage}",
                    data = results
                )
            }
        }
        
        return StepExecutionResult(
            success = true,
            errorCode = RESULT_SUCCESS,
            errorMessage = null,
            data = mapOf(
                "conditionMet" to conditionMet,
                "branchExecuted" to if (conditionMet) "then" else "else",
                "results" to results
            )
        )
    }
    
    private fun executeLoopStep(config: JSONObject): StepExecutionResult {
        val iterateOver = config.getString("iterateOver")
        val itemVariable = config.optString("itemVariable", "item")
        val indexVariable = config.optString("indexVariable", "index")
        val steps = config.getJSONArray("steps")
        val maxIterations = config.optInt("maxIterations", 10)
        
        // 解析迭代数据源（JSONPath）
        val iterableData = resolveJsonPath(iterateOver)
        if (iterableData !is List<*>) {
            return StepExecutionResult(
                success = false,
                errorCode = RESULT_STEP_FAILED,
                errorMessage = "iterateOver 必须解析为数组: $iterateOver",
                data = emptyMap()
            )
        }
        
        val actualIterations = min(iterableData.size, maxIterations)
        val loopResults = mutableListOf<Map<String, Any>>()
        
        for (index in 0 until actualIterations) {
            val item = iterableData[index]
            
            // 设置循环变量
            executionContext[itemVariable] = item ?: ""
            executionContext[indexVariable] = index
            
            // 执行循环体
            val iterationResults = mutableMapOf<String, Any>()
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val stepId = step.optString("id", "loop_step_$i")
                
                val result = executeStep(step, null)
                iterationResults[stepId] = result
                
                if (!result.success) {
                    return StepExecutionResult(
                        success = false,
                        errorCode = result.errorCode,
                        errorMessage = "循环迭代 $index 步骤 $stepId 失败",
                        data = mapOf("completedIterations" to loopResults)
                    )
                }
            }
            
            loopResults.add(iterationResults)
        }
        
        return StepExecutionResult(
            success = true,
            errorCode = RESULT_SUCCESS,
            errorMessage = null,
            data = mapOf(
                "iterations" to actualIterations,
                "results" to loopResults
            )
        )
    }
    
    // ===== 条件判定 =====
    
    private fun evaluateCondition(condition: JSONObject): Boolean {
        val type = condition.getString("type")
        
        return when (type) {
            "equals" -> {
                val left = resolveValue(condition.getString("left"))
                val right = resolveValue(condition.get("right"))
                left == right
            }
            "not_equals" -> {
                val left = resolveValue(condition.getString("left"))
                val right = resolveValue(condition.get("right"))
                left != right
            }
            "greater_than" -> {
                val left = resolveValue(condition.getString("left")) as? Number
                val right = resolveValue(condition.get("right")) as? Number
                (left?.toDouble() ?: 0.0) > (right?.toDouble() ?: 0.0)
            }
            "less_than" -> {
                val left = resolveValue(condition.getString("left")) as? Number
                val right = resolveValue(condition.get("right")) as? Number
                (left?.toDouble() ?: 0.0) < (right?.toDouble() ?: 0.0)
            }
            "contains" -> {
                val left = resolveValue(condition.getString("left")).toString()
                val right = resolveValue(condition.get("right")).toString()
                left.contains(right)
            }
            "exists" -> {
                val left = condition.getString("left")
                resolveJsonPath(left) != null
            }
            "and" -> {
                val conditions = condition.getJSONArray("conditions")
                (0 until conditions.length()).all { i ->
                    evaluateCondition(conditions.getJSONObject(i))
                }
            }
            "or" -> {
                val conditions = condition.getJSONArray("conditions")
                (0 until conditions.length()).any { i ->
                    evaluateCondition(conditions.getJSONObject(i))
                }
            }
            "not" -> {
                val subCondition = condition.getJSONArray("conditions").getJSONObject(0)
                !evaluateCondition(subCondition)
            }
            else -> {
                Log.w(TAG, "不支持的条件类型: $type")
                false
            }
        }
    }
    
    // ===== 工具方法 =====
    
    private fun resolveParameters(parameters: JSONObject): Map<String, Any> {
        val resolved = mutableMapOf<String, Any>()
        for (key in parameters.keys()) {
            // 未解析成功的引用（如可选参数缺省）不下发，交由能力桥按缺省处理
            val value = resolveValue(parameters.get(key)) ?: continue
            resolved[key] = value
        }
        return resolved
    }
    
    private fun resolveValue(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> {
                when {
                    // JSONPath 引用
                    value.startsWith("$.") -> resolveJsonPath(value)
                    // 模板变量
                    value.startsWith("{{") && value.endsWith("}}") -> {
                        val path = value.substring(2, value.length - 2).trim()
                        resolveJsonPath(path)
                    }
                    else -> value
                }
            }
            else -> value
        }
    }
    
    private fun resolveJsonPath(path: String): Any? {
        // 简化的 JSONPath 实现（支持 $.xxx.yyy、$.xxx[0].yyy 与 .length）
        if (!path.startsWith("$.")) return null
        return resolveTokens(executionContext, tokenizePath(path.substring(2)))
    }
    
    /**
     * 将路径拆分为 token（支持 `.` 与 `[index]` 两种分隔）
     */
    private fun tokenizePath(path: String): List<String> {
        val tokens = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in path) {
            when (ch) {
                '.', '[', ']' -> {
                    if (buffer.isNotEmpty()) {
                        tokens.add(buffer.toString())
                        buffer.clear()
                    }
                }
                else -> buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) tokens.add(buffer.toString())
        return tokens
    }
    
    /**
     * 按 token 逐级解析（支持 Map / List / StepExecutionResult / String 的 length）
     */
    private fun resolveTokens(root: Any?, tokens: List<String>): Any? {
        var current: Any? = root
        for (token in tokens) {
            current = when {
                current == null -> return null
                current is StepExecutionResult -> when (token) {
                    "success" -> current.success
                    "errorCode" -> current.errorCode
                    "errorMessage" -> current.errorMessage
                    "data" -> current.data
                    else -> current.data[token]
                }
                current is Map<*, *> -> if (token == "length" || token == "size") {
                    current.size
                } else {
                    current.entries.firstOrNull { it.key == token }?.value
                }
                current is List<*> -> if (token == "length" || token == "size") {
                    current.size
                } else {
                    token.toIntOrNull()?.let { index -> current.getOrNull(index) }
                }
                current is String -> if (token == "length" || token == "size") current.length else null
                else -> null
            }
        }
        return current
    }
    
    private fun applyOutputMapping(data: Map<String, Any>, mapping: JSONObject): Map<String, Any> {
        val mapped = mutableMapOf<String, Any>()
        for (key in mapping.keys()) {
            val sourcePath = mapping.getString(key)
            // 输出映射路径相对当前步骤 data 解析（如 $.data.events → data["events"]）
            val tokens = tokenizePath(sourcePath.removePrefix("$.")).let {
                if (it.firstOrNull() == "data") it.drop(1) else it
            }
            val value = resolveTokens(data, tokens)
            if (value != null) {
                mapped[key] = value
            }
        }
        return mapped
    }
    
    private fun aggregateResults(results: Map<String, Any>, strategy: String): Map<String, Any> {
        return when (strategy) {
            AGGREGATION_MERGE -> {
                val merged = mutableMapOf<String, Any>()
                for ((stepId, result) in results) {
                    if (result is StepExecutionResult && result.success) {
                        merged[stepId] = result.data
                    }
                }
                merged
            }
            AGGREGATION_FIRST_SUCCESS -> {
                for ((stepId, result) in results) {
                    if (result is StepExecutionResult && result.success) {
                        return mapOf("selectedStep" to stepId, "result" to result.data)
                    }
                }
                emptyMap()
            }
            AGGREGATION_ALL_RESULTS -> {
                results
            }
            else -> results
        }
    }
    
    private fun retryStep(step: JSONObject, errorHandling: JSONObject?): StepExecutionResult {
        val maxRetries = errorHandling?.optInt("maxRetries", 0) ?: 0
        val retryDelay = errorHandling?.optInt("retryDelayMs", 1000)?.toLong() ?: 1000L
        
        repeat(maxRetries) { attempt ->
            Log.d(TAG, "步骤重试 ${attempt + 1}/$maxRetries")
            Thread.sleep(retryDelay)
            
            val result = executeStep(step, null)
            if (result.success) {
                return result
            }
        }
        
        return executeStep(step, null)
    }
    
    private fun <T> executeWithTimeout(timeoutMs: Long, block: () -> T): T? {
        val future = executor.submit(Callable { block() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            null
        }
    }
}

/**
 * Skill 执行结果
 */
data class SkillExecutionResult(
    val success: Boolean,
    val errorCode: Int,
    val errorMessage: String?,
    val data: Map<String, Any>
)

/**
 * 步骤执行结果
 */
data class StepExecutionResult(
    val success: Boolean,
    val errorCode: Int,
    val errorMessage: String?,
    val data: Map<String, Any>
)
