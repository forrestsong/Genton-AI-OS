// IAgentSessionService.aidl
// 会话服务接口 - 由 Agent 大脑特权 App 实现，SystemUI 通过 BIND_AGENT_UI 权限绑定
package com.genton.agent;

import com.genton.agent.SessionOpenRequest;
import com.genton.agent.SessionHandle;
import com.genton.agent.UserTurn;
import com.genton.agent.IAgentSessionListener;

/**
 * Agent 会话服务接口
 * 
 * 实现方：Agent 大脑特权应用（平台签名）
 * 调用方：SystemUI（平台签名 + BIND_AGENT_UI 权限）
 * 
 * 核心职责：
 * 1. 会话生命周期管理（打开/结束）
 * 2. 用户轮次提交与流式响应
 * 3. 任务/动作卡片推送
 * 4. 跨模式会话恢复（checkpoint 关联）
 * 
 * 安全约束：
 * - 仅 SystemUI 可绑定（signature|privileged + SE 策略）
 * - 会话内容不广播给第三方
 * - 用户输入必须经大脑处理，不得"抄近路"
 */
interface IAgentSessionService {
    // 状态码
    const int STATUS_OK                = 0;
    const int STATUS_DENIED            = 1;   // 签名/白名单校验失败
    const int STATUS_SESSION_NOT_FOUND = 2;
    const int STATUS_BUSY              = 3;   // 已有轮次推理中
    const int STATUS_INTERNAL_ERROR    = -1;
    
    /**
     * 打开（或复用）一个会话
     * 
     * @param request 会话打开请求（包含 sessionId、agentId、entryMode、resumePackId）
     * @param listener SystemUI 实现的流式回调监听器
     * @param handle 输出参数，返回会话句柄
     * @return STATUS_OK 成功；STATUS_DENIED 权限拒绝；STATUS_INTERNAL_ERROR 内部错误
     */
    int openSession(in SessionOpenRequest request, 
                    in IAgentSessionListener listener,
                    out SessionHandle handle);
    
    /**
     * 提交用户轮次
     * 
     * @param handle 会话句柄
     * @param turn 用户轮次数据（文本/语音/附件/表单提交）
     * @param turnId 输出参数，返回轮次 ID（单元素数组，取 turnId[0]；用于取消/追踪）
     * @return STATUS_OK 成功；STATUS_BUSY 上一轮未完成；STATUS_SESSION_NOT_FOUND 会话不存在
     */
    int submitUserTurn(in SessionHandle handle, 
                       in UserTurn turn, 
                       out String[] turnId);
    
    /**
     * 取消当前轮次
     * 
     * @param handle 会话句柄
     * @param turnId 要取消的轮次 ID
     * @return STATUS_OK 成功；STATUS_SESSION_NOT_FOUND 会话不存在
     */
    int cancelTurn(in SessionHandle handle, in String turnId);
    
    /**
     * 结束会话
     * 
     * @param handle 会话句柄
     * @param reason 结束原因（0=user_switch 切换用户；1=app_bg 应用后台；2=agent_done Agent 完成）
     * @return STATUS_OK 成功；STATUS_SESSION_NOT_FOUND 会话不存在
     */
    int endSession(in SessionHandle handle, in int reason);
}
