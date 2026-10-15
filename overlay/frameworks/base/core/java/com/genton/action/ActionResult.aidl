package com.genton.action;

import android.os.Bundle;

/**
 * ActionResult Parcelable
 * 动作执行结果（A3.2 执行中介 / D2 草案 §3.2）
 *
 * 仅终态（SUCCEEDED/FAILED/ABORTED/EXPIRED）挂载在 ActionExecutionStatus.result 上。
 * resultCode 语义见 IAgentActionService.RESULT_* 常量（对 SUCCEEDED 为 RESULT_OK=0）。
 *
 * @hide
 */
parcelable ActionResult {
    /** 结果码（RESULT_OK/RESULT_EXECUTION_ERROR/RESULT_CONFIRMATION_DENIED/...） */
    int resultCode;

    /** 失败原因（含 ERROR_STALE_TARGET 等语义；成功时可为 null） */
    String error;

    /** 成功负载（例：tap 的命中节点 id → {"hitNodeId": "..."}） */
    Bundle data;

    /** 关联审计链事件（AgentAuditService.recordEvent 返回的事件哈希） */
    String auditEventId;

    /** 证据引用（截图/轨迹 id，A5.2/5.3 接线后填充） */
    String evidenceRef;
}
