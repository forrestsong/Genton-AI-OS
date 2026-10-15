package com.genton.action;

import com.genton.action.ActionRequest;
import com.genton.action.ActionInfo;
import com.genton.action.ActionExecutionStatus;
import com.genton.action.ActionResult;
import com.genton.action.IAgentActionCallback;

/**
 * IAgentActionService
 * AI Native OS 动作执行中介（A3.2 / D2 草案 §3）
 *
 * 第一原则：副作用动作只有一个入口（执行中介），模型永远不能直触
 * AMS / InputDispatcher / 驱动。本服务强制走：
 *   注册表校验 → 参数校验 → 策略引擎预检 →（按风险）用户确认
 *   → 确定性执行器 → 审计落链 → 结果回灌
 *
 * 状态机（8 态）：
 *   QUEUED → POLICY_CHECK → CONFIRMATION → EXECUTING → 终态
 *   终态：SUCCEEDED / FAILED / ABORTED(取消) / EXPIRED(确认超时)
 *
 * 回调：submitAction 成功后以 IAgentActionCallback.onStatusChanged 接收
 * 每次状态推进；终态回调后服务自动解绑（见 IAgentActionCallback）。
 *
 * 红线：
 * 1. 未知动作/能力/来源不合规 = 拒绝
 * 2. DENY/require_mitigation 一律失败-安全（不执行）
 * 3. 确认决策由用户（SystemUI/确认宿主）直回，不经大脑
 *
 * @hide
 */
interface IAgentActionService {
    // ==================== 提交结果码（submitAction 同步返回） ====================
    const int SUBMIT_ACCEPTED               = 0;  // 已受理，进入异步执行
    const int SUBMIT_UNKNOWN_ACTION         = 1;  // 动作不在注册表
    const int SUBMIT_POLICY_DENIED          = 2;  // 策略拒绝（无能力令牌/与意图不符/来源不合规）
    const int SUBMIT_ARGUMENT_INVALID       = 3;  // 参数 schema 校验失败
    const int SUBMIT_TAINT_DENIED           = 4;  // 参数来源含未授权 taint 数据（提示注入防线）
    const int SUBMIT_CONFIRMATION_REQUIRED  = 5;  // 高风险，已生成确认请求等待用户（状态=CONFIRMATION）

    // ==================== 动作状态（回调/查询共用） ====================
    const int STATE_QUEUED        = 1;   // 排队中
    const int STATE_POLICY_CHECK  = 2;   // 策略校验中
    const int STATE_CONFIRMATION  = 3;   // 等待用户确认（高风险）
    const int STATE_EXECUTING     = 4;
    const int STATE_SUCCEEDED     = 5;
    const int STATE_FAILED        = 6;   // 执行失败，原因见 result.error
    const int STATE_ABORTED       = 7;   // 用户/调用方取消
    const int STATE_EXPIRED       = 8;   // 确认超时自动放弃

    // ==================== 终态结果码（ActionResult.resultCode） ====================
    const int RESULT_OK                    = 0;  // SUCCEEDED
    const int RESULT_POLICY_DENIED         = 1;  // 策略拒绝
    const int RESULT_ARGUMENT_INVALID      = 2;  // 参数校验失败
    const int RESULT_TAINT_DENIED          = 3;  // taint 防线拒绝
    const int RESULT_CONFIRMATION_DENIED   = 4;  // 用户拒绝确认
    const int RESULT_EXECUTION_ERROR       = 5;  // 执行器执行失败
    const int RESULT_TIMEOUT               = 6;  // 执行超时（FAILED）/ 确认超时（EXPIRED）
    const int RESULT_CANCELLED             = 7;  // ABORTED（取消）
    const int RESULT_INTERNAL_ERROR        = 8;  // 中介内部错误
    const int RESULT_NOT_FOUND             = 9;  // 请求/动作不存在

    /**
     * 提交动作执行请求。成功后以 callback 接收状态流转。
     *
     * @param request 动作请求（actionName/arguments/taintedArgumentKeys/sourceIntentId/agentId/reason）
     * @param callback 状态回调（终态后服务自动解绑）
     * @param actionRequestId 输出请求标识符（单元素数组，取 actionRequestId[0]；
     *                        SUBMIT_ACCEPTED/CONFIRMATION_REQUIRED 时有效）
     * @return SUBMIT_*（同步闸门结果；异步结果经回调/状态查询）
     */
    int submitAction(in ActionRequest request, in IAgentActionCallback callback,
                     out String[] actionRequestId);

    /**
     * 取消仍处于 QUEUED / POLICY_CHECK / CONFIRMATION / EXECUTING 的动作
     *
     * @return RESULT_OK（已受理取消）/ RESULT_NOT_FOUND（未知请求）
     */
    int cancelAction(in String actionRequestId);

    /**
     * 查询动作当前状态与结果（含终态 result）
     *
     * @return 状态；请求不存在返回 null
     */
    ActionExecutionStatus getActionStatus(in String actionRequestId);

    /**
     * 取单个动作的注册信息（供规划/描述）
     *
     * @return 动作信息；未注册返回 null
     */
    ActionInfo describeAction(in String actionName);

    /**
     * 列出 Agent 可见动作（服务端按能力注册表过滤 enabled 项；粒度过滤由客户端令牌决定）
     *
     * @return 动作信息列表
     */
    ActionInfo[] listActions();
}
