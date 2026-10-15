// AI Native OS 三方沙箱联调样例 —— "便签小助手"（F5.2）
//
// 编译骨架：演示第三方 Agent 如何实现 ISandboxedAgent 并仅经网关调用系统能力。
// ⚠️ 占位标注：远端模型调用、动作参数、返回解析为占位，联调时按真实业务替换
//   （见本目录 README.md §3 联调核对清单）。
//
// 前提：宿主已按 B6.1 startSandbox 启动沙箱并注入 SandboxSessionContext；
//       网关对象由宿主在 SandboxSessionContext 中提供（com.genton.sandbox.GatewayService）。

package com.example.notepadmate

import com.genton.sandbox.* // ISandboxedAgent / SandboxSessionContext / SandboxUserTurn /
                              // IAgentSandboxHostEvents / ToolResultEnvelope

/**
 * 第三方 Agent 实现骨架。宿主（系统大脑特权 App）通过 isolatedProcess 绑定本服务，
 * 以 ISandboxedAgent 驱动会话；一切系统能力仅经 IAgentSandboxGateway 获取。
 */
class SandboxedAgentService : /* android.app.Service() , */ ISandboxedAgent {

    private var gateway: IAgentSandboxGateway? = null
    private var hostEvents: IAgentSandboxHostEvents? = null

    override fun start(context: SandboxSessionContext, events: IAgentSandboxHostEvents): Int {
        // context.gateway —— 宿主注入的沙箱网关（受限操作面唯一入口）
        gateway = context.gateway
        hostEvents = events
        events.onReady(/* handle */)
        return 0 // STATUS_OK
    }

    override fun onUserMessage(turn: SandboxUserTurn): Int {
        // 占位：把用户消息发给清单声明的远端模型端点（model.endpoints[0].domain）
        // val reply = callMyRemoteModel(turn.text)   // <-- 联调替换
        // 若模型决定需要读取当前页面：
        val uiJson = gateway?.read(
            IAgentSandboxGateway.OP_UI_READ_MASKED,
            GatewayReadRequest(maskedOnly = true)
        )
        // 占位：从 uiJson 提取用户想记录的便签内容
        // val note = extractNote(uiJson)            // <-- 联调替换
        // 写入自身记忆（own_only）：
        gateway?.write(
            IAgentSandboxGateway.OP_MEMORY_OWN,
            GatewayWriteRequest(key = "last_note", value = "<note>")
        )
        hostEvents?.onStateChanged(STATE_WORKING, "已记录便签")
        return 0
    }

    override fun onToolResult(toolCallId: String, result: ToolResultEnvelope): Int {
        // 动作执行结果回传（B6.2：ALLOW 后经此回传）
        hostEvents?.onStateChanged(STATE_IDLE, result.summaryMasked ?: "完成")
        return 0
    }

    override fun cancelCurrentTask(): Int = 0

    override fun stop(reason: Int): Int {
        // 用户撤销（reason=REVOKED）时：清理本地状态，不得重试已撤销操作
        gateway = null
        hostEvents = null
        return 0
    }

    // ---------- 占位辅助方法（联调替换） ----------
    private fun callMyRemoteModel(text: String): String = "<占位：远端模型回复>"
    private fun extractNote(uiJson: String?): String = "<占位：从掩码 UI 提取内容>"
}
