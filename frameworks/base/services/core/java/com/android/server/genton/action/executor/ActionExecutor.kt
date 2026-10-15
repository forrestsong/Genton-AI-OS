package com.android.server.genton.action.executor

import com.genton.action.ActionModels.*

/**
 * A3.3 动作执行器接口
 *
 * 所有具体执行器的基类接口
 *
 * 职责：
 * 1. 定义执行器的标准接口
 * 2. 提供能力描述方法（用于动作注册表匹配）
 * 3. 提供取消操作接口（如果支持）
 *
 * 实现类：
 * - ActivityExecutor：Activity启动
 * - InputExecutor：UI交互注入
 * - DeviceControlExecutor：系统设置控制
 * - ContentExecutor：内容提供者操作（待实现）
 * - NotificationExecutor：通知操作（待实现）
 * - TelephonyExecutor：电话/短信操作（待实现）
 */
interface ActionExecutor {
    /**
     * 执行动作
     *
     * @param request 动作请求
     * @return 执行结果
     */
    fun execute(request: ActionRequest): ActionExecutionResult

    /**
     * 取消正在执行的动作
     *
     * @param actionRequestId 动作请求ID
     * @return 是否成功取消
     */
    fun cancel(actionRequestId: String): Boolean

    /**
     * 描述执行器能力
     *
     * 用于调试和能力发现
     *
     * @return 能力描述字符串
     */
    fun describeCapabilities(): String
}

/**
 * 执行器注册表
 *
 * 管理所有执行器的注册和分发
 */
class ActionExecutorRegistry {
    private val executors = mutableMapOf<String, ActionExecutor>()

    /**
     * 注册执行器
     *
     * @param actionPrefix 动作前缀（如"activity"匹配"activity.launch"）
     * @param executor 执行器实例
     */
    fun registerExecutor(actionPrefix: String, executor: ActionExecutor) {
        executors[actionPrefix] = executor
    }

    /**
     * 根据动作名称查找执行器
     *
     * @param actionName 完整动作名称（如"activity.launch"）
     * @return 执行器实例，如果未找到返回null
     */
    fun getExecutor(actionName: String): ActionExecutor? {
        // 提取前缀（第一个点之前的部分）
        val prefix = actionName.substringBefore(".", "")
        return executors[prefix]
    }

    /**
     * 列出所有已注册的执行器
     */
    fun listExecutors(): Map<String, String> {
        return executors.mapValues { (_, executor) ->
            executor.describeCapabilities()
        }
    }

    /**
     * 取消指定请求的动作
     */
    fun cancelAction(actionRequestId: String, actionName: String): Boolean {
        val executor = getExecutor(actionName) ?: return false
        return executor.cancel(actionRequestId)
    }
}
