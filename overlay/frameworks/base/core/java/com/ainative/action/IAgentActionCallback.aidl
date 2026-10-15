package com.ainative.action;

import com.ainative.action.ActionExecutionStatus;

/**
 * IAgentActionCallback
 * 动作执行回调（A3.2 执行中介 / D2 草案 §3.2）
 *
 * 契约：
 * 1. 单向（oneway）：状态推进即回调，不阻塞中介状态机线程
 * 2. 每次状态流转（QUEUED→POLICY_CHECK→CONFIRMATION/EXECUTING→终态）各回调一次
 * 3. 终态（SUCCEEDED/FAILED/ABORTED/EXPIRED）回调后，服务端自动解绑本回调
 *    （不再有后续回调；调用方持 actionRequestId 走 getActionStatus 取终态结果）
 */
interface IAgentActionCallback {

    /**
     * 状态推进回调（含终态）
     *
     * @param actionRequestId 动作请求标识符
     * @param status 最新状态（终态时携带 result）
     */
    oneway void onStatusChanged(in String actionRequestId, in ActionExecutionStatus status);
}
