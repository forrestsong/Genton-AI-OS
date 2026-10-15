package com.ainative.systemui.scheduleprep

/**
 * D7.2 日程预准备呈现 - 数据模型
 *
 * 对齐 A3.6 内部动作 sys.schedule_prep 和 C6.2 activate_suggestion schema。
 * 描述日程事件的预准备状态、凭据注入进度、创作任务等。
 */

/** 日程紧急程度分级 */
enum class UrgencyLevel {
    /** 常规：提前 1 小时以上 */
    NORMAL,
    /** 较高：提前 15 分钟 - 1 小时 */
    ELEVATED,
    /** 紧急：15 分钟内或进行中 */
    URGENT
}

/** 预准备状态 */
enum class PrepStatus {
    /** 等待中：日程还远，未开始准备 */
    WAITING,
    /** 准备中：正在执行预准备动作 */
    PREPARING,
    /** 已就绪：所有预准备完成 */
    READY,
    /** 已跳过：用户手动跳过或无需准备 */
    SKIPPED,
    /** 已失败：某项预准备失败 */
    FAILED
}

/** 凭据注入步骤状态 */
enum class CredentialStepStatus {
    PENDING,
    INJECTING,
    COMPLETED,
    FAILED,
    SKIPPED
}

/** 日程预准备事件描述（上游 A3.6 sys.schedule_prep 产物） */
data class SchedulePrepEvent(
    /** 唯一事件 ID */
    val eventId: String,
    /** 日程标题（脱敏） */
    val titleMasked: String,
    /** 日程开始时间戳（ms） */
    val startTimeMs: Long,
    /** 日程地点（脱敏，可能为空） */
    val locationMasked: String?,
    /** 日程类型（会议/出行/提醒/创作等） */
    val scheduleType: String,
    /** 紧急程度 */
    val urgency: UrgencyLevel,
    /** 预准备状态 */
    val status: PrepStatus,
    /** 预计还有多少秒开始 */
    val secondsUntilStart: Long
)

/** 凭据注入步骤（敏感信息值不出屏） */
data class CredentialInjectionStep(
    /** 步骤唯一 ID */
    val stepId: String,
    /** 凭据类型描述（脱敏，如"日历凭据"/"会议凭据"） */
    val credentialTypeMasked: String,
    /** 注入目标（脱敏，如"Zoom 应用"/"Calendar App"） */
    val targetMasked: String,
    /** 当前状态 */
    val status: CredentialStepStatus,
    /** 进度百分比 0-100 */
    val progress: Int,
    /** 是否已完成（便捷查询） */
    val isDone: Boolean get() = status == CredentialStepStatus.COMPLETED || status == CredentialStepStatus.SKIPPED,
    /** 是否失败 */
    val isFailed: Boolean get() = status == CredentialStepStatus.FAILED
)

/** 创作任务（日程预准备触发的创作类动作，如"准备会议议程"） */
data class CreativeTask(
    /** 任务唯一 ID */
    val taskId: String,
    /** 任务标题 */
    val title: String,
    /** 任务描述/目标 */
    val descriptionMasked: String,
    /** 进度 0-100 */
    val progress: Int,
    /** 是否已完成 */
    val isCompleted: Boolean,
    /** 关联的 ActionCardData cardId（供 D2.1 会话流联动） */
    val actionCardId: String?
)

/** 完整的预准备状态快照（供 UI 渲染） */
data class SchedulePrepSnapshot(
    /** 关联的日程事件 */
    val event: SchedulePrepEvent,
    /** 凭据注入步骤列表 */
    val credentialSteps: List<CredentialInjectionStep>,
    /** 创作任务列表 */
    val creativeTasks: List<CreativeTask>,
    /** 总体就绪进度 0-100 */
    val overallProgress: Int,
    /** 是否允许一键关停 */
    val canStop: Boolean,
    /** 关停原因（如果已关停） */
    val stopReason: String?
)
