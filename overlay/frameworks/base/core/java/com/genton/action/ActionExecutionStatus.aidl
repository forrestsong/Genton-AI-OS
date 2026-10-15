package com.genton.action;

import com.genton.action.ActionResult;

/**
 * ActionExecutionStatus Parcelable
 * 动作执行状态（A3.2 执行中介 / D2 草案 §3.2）
 *
 * 状态码见 IAgentActionService.STATE_* 常量：
 * QUEUED(1)/POLICY_CHECK(2)/CONFIRMATION(3)/EXECUTING(4)/
 * SUCCEEDED(5)/FAILED(6)/ABORTED(7)/EXPIRED(8)。
 *
 * result 仅在终态非空。
 *
 * @hide
 */
parcelable ActionExecutionStatus {
    /** 动作请求标识符（submitAction 的 out actionRequestId） */
    String actionRequestId;

    /** 当前状态（STATE_QUEUED ~ STATE_EXPIRED） */
    int state;

    /** 状态更新时间（SystemClock.elapsedRealtimeNanos，单调时钟） */
    long updatedAtRealtimeNanos;

    /** 终态结果（SUCCEEDED/FAILED/ABORTED/EXPIRED 时非空） */
    @nullable ActionResult result;
}
