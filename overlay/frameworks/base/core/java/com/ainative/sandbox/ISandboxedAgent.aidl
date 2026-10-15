// ISandboxedAgent.aidl
// 第三方 Agent 实现接口 - 第三方开发者实现，宿主调用
package com.ainative.sandbox;

import com.ainative.sandbox.SandboxSessionContext;
import com.ainative.sandbox.SandboxUserTurn;
import com.ainative.sandbox.ToolResultEnvelope;
import com.ainative.sandbox.IAgentSandboxHostEvents;

/**
 * 第三方 Agent 实现接口
 * 
 * 实现方：第三方开发者 App（独立签名，isolatedProcess）
 * 调用方：沙箱宿主（系统大脑特权 App）
 * 
 * 生命周期：
 * 1. start：初始化，注册事件回调
 * 2. onUserMessage：接收用户轮次（可异步多轮）
 * 3. onToolResult：接收工具调用结果
 * 4. cancelCurrentTask：取消当前任务
 * 5. stop：清理资源
 * 
 * 约束：
 * - 运行在 isolatedProcess，独立 UID
 * - 仅能通过 IAgentSandboxGateway 访问系统能力
 * - 自带模型只能用清单声明的远端 API
 * - 记忆范围限定为 own_only
 * - 网络仅限清单声明的域名白名单
 */
interface ISandboxedAgent {
    /**
     * 启动 Agent
     * 
     * @param context 会话上下文（sessionId、用户授权能力列表）
     * @param events 宿主事件回调接口（onReady/onStateChanged/onToolRequest/onError）
     * @return STATUS_OK 成功；STATUS_INTERNAL_ERROR 初始化失败
     */
    int start(in SandboxSessionContext context, in IAgentSandboxHostEvents events);
    
    /**
     * 处理用户消息
     * 
     * 第三方 Agent 处理用户输入，可能触发多次工具调用（通过 events.onToolRequest）
     * 
     * @param turn 用户轮次（turnId、消息内容、附件引用）
     * @return STATUS_OK 成功；STATUS_BUSY 正在处理其他任务
     */
    int onUserMessage(in SandboxUserTurn turn);
    
    /**
     * 接收工具调用结果
     * 
     * 宿主执行工具调用后，将结果回传给第三方 Agent
     * 
     * @param toolCallId 工具调用 ID（对应 onToolRequest 的 callId）
     * @param result 工具执行结果（成功/失败、返回值、错误信息）
     * @return STATUS_OK 成功
     */
    int onToolResult(in String toolCallId, in ToolResultEnvelope result);
    
    /**
     * 取消当前任务
     * 
     * 用户/宿主请求取消正在执行的任务
     * 
     * @return STATUS_OK 成功
     */
    int cancelCurrentTask();
    
    /**
     * 停止 Agent
     * 
     * @param reason 停止原因（0=正常，1=用户撤销，2=资源回收，3=崩溃）
     * @return STATUS_OK 成功
     */
    int stop(int reason);
}
