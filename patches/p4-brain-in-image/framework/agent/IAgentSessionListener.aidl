// IAgentSessionListener.aidl
// 会话监听器 - 由 SystemUI 实现，接收流式响应
package com.ainative.agent;

import com.ainative.agent.SessionHandle;
import com.ainative.agent.TurnDelta;
import com.ainative.agent.TurnResultSummary;
import com.ainative.agent.TaskCardData;
import com.ainative.agent.ActionCardData;

/**
 * Agent 会话监听器接口
 * 
 * 实现方：SystemUI
 * 调用方：Agent 大脑特权应用
 * 
 * 核心职责：
 * 1. 接收流式增量（thinking/text/tool_activity）
 * 2. 接收轮次完成通知
 * 3. 接收任务卡片变更（对齐 ACB 状态机）
 * 4. 接收动作卡片（进度/完成/撤销）
 * 5. 接收会话错误
 * 
 * 所有回调均为 oneway（异步），避免阻塞大脑推理
 */
interface IAgentSessionListener {
    // 会话状态
    const int SESSION_ACTIVE         = 0;  // 活跃中（推理/执行）
    const int SESSION_WAITING_CONFIRM = 1;  // 等待用户确认
    const int SESSION_IDLE           = 2;  // 空闲（等待下一轮输入）
    
    /**
     * 会话状态变更
     * 
     * @param handle 会话句柄
     * @param state 新状态（SESSION_ACTIVE/SESSION_WAITING_CONFIRM/SESSION_IDLE）
     */
    oneway void onSessionStateChanged(in SessionHandle handle, in int state);
    
    /**
     * 流式增量推送
     * 
     * @param handle 会话句柄
     * @param turnId 轮次 ID
     * @param delta 增量数据（thinking/text_stream/tool_activity）
     */
    oneway void onTurnDelta(in SessionHandle handle, 
                            in String turnId, 
                            in TurnDelta delta);
    
    /**
     * 轮次完成
     * 
     * @param handle 会话句柄
     * @param turnId 轮次 ID
     * @param result 轮次结果摘要（最终文本、动作卡片 ID 列表、审计引用）
     */
    oneway void onTurnCompleted(in SessionHandle handle, 
                                in String turnId, 
                                in TurnResultSummary result);
    
    /**
     * 任务卡片变更（增/更新/撤销）
     * 
     * @param handle 会话句柄
     * @param card 任务卡片数据（state 字段表达状态：对齐 ACB 状态机）
     */
    oneway void onTaskCardChanged(in SessionHandle handle, 
                                  in TaskCardData card);
    
    /**
     * 动作卡片推送
     * 
     * @param handle 会话句柄
     * @param card 动作卡片数据（type：progress/done/needs_attention/undo_available/form）
     */
    oneway void onActionCard(in SessionHandle handle, 
                             in ActionCardData card);
    
    /**
     * 会话错误
     * 
     * @param handle 会话句柄
     * @param code 错误码
     * @param message 错误消息
     */
    oneway void onSessionError(in SessionHandle handle, 
                               in int code, 
                               in String message);

    /**
     * 轮次开始（大脑受理用户输入后、规划前）
     */
    oneway void onTurnStarted(in SessionHandle handle, in String turnId);

    /**
     * 轮次取消（用户中断 / 会话关闭）
     */
    oneway void onTurnCancelled(in SessionHandle handle, in String turnId);

    /**
     * 动作卡片更新（ActionCardData 形态）
     */
    oneway void onActionCardChanged(in SessionHandle handle, in ActionCardData card);
}
