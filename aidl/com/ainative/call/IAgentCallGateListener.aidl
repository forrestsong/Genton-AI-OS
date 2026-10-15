// IAgentCallGateListener.aidl
package com.ainative.call;

import com.ainative.call.CallDescriptor;
import com.ainative.call.CallVerdict;
import com.ainative.call.CallSessionHandle;
import com.ainative.call.TranscriptDelta;
import com.ainative.call.CallSummary;

/**
 * CallGate 会话状态监听器（SystemUI / 大脑 实现）
 * 
 * 职责：
 * - 接收判定结果（供 SystemUI 显示接管卡/原生回退）
 * - 接收会话状态变更（bridging/greeting/transcribing/done/error）
 * - 接收转写增量（本地 ASR，脱敏后可显示）
 * - 接收摘要（本地生成，用户可见，不落长期记忆除非用户要求）
 * 
 * @see IAgentCallGateService
 */
interface IAgentCallGateListener {
    /**
     * 判定结果（供 SystemUI 显示 CallGate 接管卡/原生回退）
     * 
     * @param descriptor 来电描述符
     * @param verdict 判定结果（route/handleMode/ruleId）
     */
    oneway void onVerdict(in CallDescriptor descriptor, in CallVerdict verdict);

    /**
     * 会话状态变更
     * 
     * @param session 会话句柄
     * @param state 0=bridging, 1=greeting, 2=transcribing, 3=done, 4=error
     */
    oneway void onSessionState(in CallSessionHandle session, in int state);

    /**
     * 转写增量（本地 ASR，脱敏后可显示）
     * 
     * @param session 会话句柄
     * @param delta 转写增量（文本/说话人/通道）
     */
    oneway void onTranscriptDelta(in CallSessionHandle session, in TranscriptDelta delta);

    /**
     * 摘要就绪（本地生成，用户可见）
     * 
     * @param session 会话句柄
     * @param summary 通话摘要（文本/建议回拨/审计引用）
     */
    oneway void onSummaryReady(in CallSessionHandle session, in CallSummary summary);

    /**
     * 错误通知
     * 
     * @param session 会话句柄
     * @param code 错误码
     * @param message 错误信息
     */
    oneway void onError(in CallSessionHandle session, in int code, in String message);
}
