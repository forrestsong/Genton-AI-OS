package com.ainative.action.service

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * A3.2 执行中介 - 动作请求
 * 
 * 职责：
 * 1. 封装动作执行请求的完整信息
 * 2. 支持参数来源标记（taint规则）
 * 3. 关联意图一致性校验
 * 4. 记录请求上下文（agentId、reason）
 */
data class ActionRequest(
    /**
     * 动作名称（必须在注册表中存在）
     */
    val actionName: String,
    
    /**
     * 动作参数（键值对）
     */
    val arguments: Bundle,
    
    /**
     * 污染参数键列表
     * Agent 将从 APP_CONTENT/WEB_CONTENT 提取的参数 key 放入此数组
     * 用于参数来源校验
     */
    val taintedArgumentKeys: Array<String>,
    
    /**
     * 源意图ID
     * 对应目标图（Goal DAG）子目标，用于意图一致性校验
     */
    val sourceIntentId: String?,
    
    /**
     * 发起Agent的ID
     */
    val agentId: String,
    
    /**
     * 执行原因（可选）
     * 用于审计和用户确认界面展示
     */
    val reason: String?,
    
    /**
     * 请求时间戳
     */
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {
    
    /**
     * 检查参数是否被标记为污染
     */
    fun isTainted(argumentKey: String): Boolean {
        return taintedArgumentKeys.contains(argumentKey)
    }
    
    /**
     * 获取所有污染参数的键值对
     */
    fun getTaintedArguments(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        taintedArgumentKeys.forEach { key ->
            if (arguments.containsKey(key)) {
                result[key] = arguments.get(key)
            }
        }
        return result
    }
    
    /**
     * 获取所有非污染参数的键值对
     */
    fun getCleanArguments(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        arguments.keySet().forEach { key ->
            if (!isTainted(key)) {
                result[key] = arguments.get(key)
            }
        }
        return result
    }
    
    // Parcelable 实现
    constructor(parcel: Parcel) : this(
        actionName = parcel.readString()!!,
        arguments = parcel.readBundle(Bundle::class.java.classLoader)!!,
        taintedArgumentKeys = parcel.createStringArray()!!,
        sourceIntentId = parcel.readString(),
        agentId = parcel.readString()!!,
        reason = parcel.readString(),
        timestamp = parcel.readLong()
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(actionName)
        parcel.writeBundle(arguments)
        parcel.writeStringArray(taintedArgumentKeys)
        parcel.writeString(sourceIntentId)
        parcel.writeString(agentId)
        parcel.writeString(reason)
        parcel.writeLong(timestamp)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<ActionRequest> {
        override fun createFromParcel(parcel: Parcel): ActionRequest {
            return ActionRequest(parcel)
        }
        
        override fun newArray(size: Int): Array<ActionRequest?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * A3.2 执行中介 - 动作结果
 * 
 * 职责：
 * 1. 封装动作执行结果
 * 2. 记录成功/失败状态和错误信息
 * 3. 返回执行结果数据
 * 4. 关联审计事件和证据
 */
data class ActionResult(
    /**
     * 结果码
     * 0 = 成功
     * 非0 = 失败（具体错误见 error 字段）
     */
    val resultCode: Int,
    
    /**
     * 错误信息（失败时）
     * 语义化错误类型，如：ERROR_STALE_TARGET、ERROR_PERMISSION_DENIED 等
     */
    val error: String?,
    
    /**
     * 执行结果数据
     * 成功时返回动作输出数据
     */
    val data: Bundle?,
    
    /**
     * 审计事件ID
     * 关联审计服务中的事件记录
     */
    val auditEventId: String?,
    
    /**
     * 证据引用
     * 指向执行证据（如截图、录屏片段）的URI
     */
    val evidenceRef: String?
) : Parcelable {
    
    /**
     * 是否成功
     */
    fun isSuccess(): Boolean {
        return resultCode == RESULT_SUCCESS
    }
    
    /**
     * 获取错误消息
     */
    fun getErrorMessage(): String {
        return error ?: "Unknown error"
    }
    
    companion object {
        // 结果码常量
        const val RESULT_SUCCESS = 0
        const val RESULT_FAILED = 1
        const val RESULT_CONFIRMATION_DENIED = 2
        const val RESULT_STALE_TARGET = 3
        const val RESULT_PERMISSION_DENIED = 4
        const val RESULT_TIMEOUT = 5
        
        // 错误类型常量
        const val ERROR_STALE_TARGET = "ERROR_STALE_TARGET"
        const val ERROR_PERMISSION_DENIED = "ERROR_PERMISSION_DENIED"
        const val ERROR_TIMEOUT = "ERROR_TIMEOUT"
        const val ERROR_INVALID_STATE = "ERROR_INVALID_STATE"
        const val ERROR_EXECUTOR_FAILURE = "ERROR_EXECUTOR_FAILURE"
        const val ERROR_CONFIRMATION_DENIED = "ERROR_CONFIRMATION_DENIED"
        
        /**
         * 创建成功结果
         */
        fun success(data: Bundle? = null, auditEventId: String? = null, evidenceRef: String? = null): ActionResult {
            return ActionResult(
                resultCode = RESULT_SUCCESS,
                error = null,
                data = data,
                auditEventId = auditEventId,
                evidenceRef = evidenceRef
            )
        }
        
        /**
         * 创建失败结果
         */
        fun failure(error: String, auditEventId: String? = null): ActionResult {
            return ActionResult(
                resultCode = RESULT_FAILED,
                error = error,
                data = null,
                auditEventId = auditEventId,
                evidenceRef = null
            )
        }
        
        /**
         * 创建确认拒绝结果
         */
        fun confirmationDenied(auditEventId: String? = null): ActionResult {
            return ActionResult(
                resultCode = RESULT_CONFIRMATION_DENIED,
                error = ERROR_CONFIRMATION_DENIED,
                data = null,
                auditEventId = auditEventId,
                evidenceRef = null
            )
        }
        
        /**
         * 创建目标过期结果
         */
        fun staleTarget(auditEventId: String? = null): ActionResult {
            return ActionResult(
                resultCode = RESULT_STALE_TARGET,
                error = ERROR_STALE_TARGET,
                data = null,
                auditEventId = auditEventId,
                evidenceRef = null
            )
        }
        
        // Parcelable Creator
        @JvmField
        val CREATOR = object : Parcelable.Creator<ActionResult> {
            override fun createFromParcel(parcel: Parcel): ActionResult {
                return ActionResult(
                    resultCode = parcel.readInt(),
                    error = parcel.readString(),
                    data = parcel.readBundle(Bundle::class.java.classLoader),
                    auditEventId = parcel.readString(),
                    evidenceRef = parcel.readString()
                )
            }
            
            override fun newArray(size: Int): Array<ActionResult?> {
                return arrayOfNulls(size)
            }
        }
    }
    
    // Parcelable 实现
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(resultCode)
        parcel.writeString(error)
        parcel.writeBundle(data)
        parcel.writeString(auditEventId)
        parcel.writeString(evidenceRef)
    }
    
    override fun describeContents(): Int = 0
}

/**
 * A3.2 执行中介 - 动作执行状态
 * 
 * 职责：
 * 1. 封装动作执行的当前状态
 * 2. 记录状态转换历史
 * 3. 提供结果查询
 */
data class ActionExecutionStatus(
    /**
     * 请求ID
     */
    val actionRequestId: String,
    
    /**
     * 当前状态（8态之一）
     */
    val currentState: Int,
    
    /**
     * 动作名称
     */
    val actionName: String,
    
    /**
     * 提交时间戳
     */
    val submitTime: Long,
    
    /**
     * 最后更新时间戳
     */
    val lastUpdateTime: Long,
    
    /**
     * 执行结果（终态时）
     */
    val result: ActionResult?,
    
    /**
     * 进度信息（可选）
     */
    val progressMessage: String?
) : Parcelable {
    
    /**
     * 是否处于终态
     */
    fun isTerminal(): Boolean {
        return currentState in arrayOf(
            ActionState.STATE_SUCCEEDED,
            ActionState.STATE_FAILED,
            ActionState.STATE_ABORTED,
            ActionState.STATE_EXPIRED
        )
    }
    
    /**
     * 是否可取消
     */
    fun isCancellable(): Boolean {
        return currentState in arrayOf(
            ActionState.STATE_QUEUED,
            ActionState.STATE_POLICY_CHECK,
            ActionState.STATE_CONFIRMATION,
            ActionState.STATE_EXECUTING
        )
    }
    
    /**
     * 获取状态描述
     */
    fun getStateDescription(): String {
        return ActionState.getStateDescription(currentState)
    }
    
    // Parcelable 实现
    constructor(parcel: Parcel) : this(
        actionRequestId = parcel.readString()!!,
        currentState = parcel.readInt(),
        actionName = parcel.readString()!!,
        submitTime = parcel.readLong(),
        lastUpdateTime = parcel.readLong(),
        result = parcel.readParcelable(ActionResult::class.java.classLoader),
        progressMessage = parcel.readString()
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(actionRequestId)
        parcel.writeInt(currentState)
        parcel.writeString(actionName)
        parcel.writeLong(submitTime)
        parcel.writeLong(lastUpdateTime)
        parcel.writeParcelable(result, flags)
        parcel.writeString(progressMessage)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<ActionExecutionStatus> {
        override fun createFromParcel(parcel: Parcel): ActionExecutionStatus {
            return ActionExecutionStatus(parcel)
        }
        
        override fun newArray(size: Int): Array<ActionExecutionStatus?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * 动作状态常量（8态）
 */
object ActionState {
    const val STATE_QUEUED = 1         // 排队中
    const val STATE_POLICY_CHECK = 2   // 策略校验中
    const val STATE_CONFIRMATION = 3   // 等待用户确认
    const val STATE_EXECUTING = 4      // 执行中
    const val STATE_SUCCEEDED = 5      // 成功
    const val STATE_FAILED = 6         // 失败
    const val STATE_ABORTED = 7        // 取消
    const val STATE_EXPIRED = 8        // 超时
    
    /**
     * 获取状态描述
     */
    fun getStateDescription(state: Int): String {
        return when (state) {
            STATE_QUEUED -> "排队中"
            STATE_POLICY_CHECK -> "策略校验中"
            STATE_CONFIRMATION -> "等待用户确认"
            STATE_EXECUTING -> "执行中"
            STATE_SUCCEEDED -> "成功"
            STATE_FAILED -> "失败"
            STATE_ABORTED -> "已取消"
            STATE_EXPIRED -> "已超时"
            else -> "未知状态($state)"
        }
    }
}

/**
 * 提交结果码
 */
object SubmitResult {
    const val SUBMIT_ACCEPTED = 0                // 已受理
    const val SUBMIT_UNKNOWN_ACTION = 1          // 动作不存在
    const val SUBMIT_POLICY_DENIED = 2           // 策略拒绝
    const val SUBMIT_ARGUMENT_INVALID = 3        // 参数无效
    const val SUBMIT_TAINT_DENIED = 4            // 参数来源不合规
    const val SUBMIT_CONFIRMATION_REQUIRED = 5   // 需要用户确认
}
