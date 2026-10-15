package com.android.systemui.genton.callgate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.genton.call.CallDescriptor
import com.genton.call.CallSessionHandle
import com.genton.call.CallSummary
import com.genton.call.CallVerdict
import com.genton.call.IAgentCallGateListener
import com.genton.call.TranscriptDelta
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CallGate UI 控制器
 * 
 * 职责：
 * - 实现 IAgentCallGateListener，接收 CallGate 服务回调
 * - 管理 UI 组件生命周期（接管卡/转写视图/摘要卡/详情页）
 * - 协调 UI 状态与会话状态同步
 * - 处理用户交互（中止接管/查看详情/回拨建议）
 * 
 * 生命周期：
 * onVerdict(route=AGENT_HANDLE) → 显示接管卡
 * → onSessionState(BRIDGING/GREETING/TRANSCRIBING) → 更新状态
 * → onTranscriptDelta() → 增量更新转写
 * → onSummaryReady() → 显示摘要卡，隐藏接管卡
 * 
 * @see IAgentCallGateListener
 * @see CallInterceptionCard
 * @see TranscriptView
 * @see CallSummaryCard
 */
@Singleton
class CallGateUiController @Inject constructor(
    private val context: Context
) : IAgentCallGateListener.Stub() {

    companion object {
        private const val TAG = "CallGateUiController"
        
        // 会话状态常量（与 IAgentCallGateListener 约定一致）
        const val STATE_BRIDGING = 0
        const val STATE_GREETING = 1
        const val STATE_TRANSCRIBING = 2
        const val STATE_DONE = 3
        const val STATE_ERROR = 4
        
        // CallVerdict.route 常量
        const val ROUTE_RING_NATIVE = 0
        const val ROUTE_AGENT_HANDLE = 1
        const val ROUTE_RECORD_ONLY = 2
        
        // CallVerdict.handleMode 常量
        const val MODE_L1_SILENT_AI = 1
        const val MODE_L2_AUDIO_DIALOG = 2
        const val MODE_L3_RECORD_ONLY = 3
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    
    // UI 组件引用
    private var interceptionCard: CallInterceptionCard? = null
    private var transcriptView: TranscriptView? = null
    private var summaryCard: CallSummaryCard? = null
    
    // 当前会话状态
    private var currentSession: CallSessionHandle? = null
    private var currentDescriptor: CallDescriptor? = null
    private var currentVerdict: CallVerdict? = null

    /**
     * 初始化控制器
     */
    fun initialize() {
        Log.d(TAG, "CallGateUiController initialized")
    }

    /**
     * 销毁控制器
     */
    fun destroy() {
        mainHandler.post {
            dismissAllUi()
        }
        currentSession = null
        currentDescriptor = null
        currentVerdict = null
        Log.d(TAG, "CallGateUiController destroyed")
    }

    // ==================== IAgentCallGateListener 实现 ====================

    /**
     * 判定结果回调
     * 
     * @param descriptor 来电描述符
     * @param verdict 判定结果
     */
    override fun onVerdict(descriptor: CallDescriptor, verdict: CallVerdict) {
        mainHandler.post {
            handleVerdict(descriptor, verdict)
        }
    }

    /**
     * 会话状态变更回调
     * 
     * @param session 会话句柄
     * @param state 会话状态（0=bridging, 1=greeting, 2=transcribing, 3=done, 4=error）
     */
    override fun onSessionState(session: CallSessionHandle, state: Int) {
        mainHandler.post {
            handleSessionState(session, state)
        }
    }

    /**
     * 转写增量回调
     * 
     * @param session 会话句柄
     * @param delta 转写增量
     */
    override fun onTranscriptDelta(session: CallSessionHandle, delta: TranscriptDelta) {
        mainHandler.post {
            handleTranscriptDelta(session, delta)
        }
    }

    /**
     * 摘要就绪回调
     * 
     * @param session 会话句柄
     * @param summary 通话摘要
     */
    override fun onSummaryReady(session: CallSessionHandle, summary: CallSummary) {
        mainHandler.post {
            handleSummaryReady(session, summary)
        }
    }

    /**
     * 错误回调
     * 
     * @param session 会话句柄
     * @param code 错误码
     * @param message 错误信息
     */
    override fun onError(session: CallSessionHandle, code: Int, message: String) {
        mainHandler.post {
            handleError(session, code, message)
        }
    }

    // ==================== 内部处理逻辑 ====================

    private fun handleVerdict(descriptor: CallDescriptor, verdict: CallVerdict) {
        currentDescriptor = descriptor
        currentVerdict = verdict
        
        Log.d(TAG, "onVerdict: route=${verdict.route}, handleMode=${verdict.handleMode}, " +
                "reason=${verdict.reason}, elapsedMs=${verdict.elapsedMs}")
        
        when (verdict.route) {
            ROUTE_AGENT_HANDLE -> {
                // AI 接管：显示接管卡
                showInterceptionCard(descriptor, verdict)
            }
            ROUTE_RING_NATIVE -> {
                // 原生响铃：无 UI
                Log.d(TAG, "Verdict: ring native, no UI needed")
            }
            ROUTE_RECORD_ONLY -> {
                // 仅记录：显示通知（静默）
                showRecordNotification(descriptor)
            }
            else -> {
                Log.w(TAG, "Unknown verdict route: ${verdict.route}")
            }
        }
    }

    private fun handleSessionState(session: CallSessionHandle, state: Int) {
        if (currentSession == null) {
            currentSession = session
        }
        
        Log.d(TAG, "onSessionState: sessionId=${session.sessionId}, state=$state")
        
        val stateText = when (state) {
            STATE_BRIDGING -> "正在连接..."
            STATE_GREETING -> "正在问候"
            STATE_TRANSCRIBING -> "正在听取"
            STATE_DONE -> "已结束"
            STATE_ERROR -> "出错"
            else -> "未知状态"
        }
        
        // 更新接管卡状态
        interceptionCard?.updateSessionState(stateText)
        
        // TRANSCRIBING 状态：显示转写视图
        if (state == STATE_TRANSCRIBING && transcriptView == null) {
            showTranscriptView(session)
        }
        
        // DONE 状态：隐藏接管卡（等待摘要）
        if (state == STATE_DONE) {
            // 不立即隐藏，等待 onSummaryReady 后统一处理
            Log.d(TAG, "Session done, waiting for summary...")
        }
        
        // ERROR 状态：隐藏所有 UI
        if (state == STATE_ERROR) {
            dismissAllUi()
        }
    }

    private fun handleTranscriptDelta(session: CallSessionHandle, delta: TranscriptDelta) {
        if (currentSession?.sessionId != session.sessionId) {
            Log.w(TAG, "Received transcript for unknown session: ${session.sessionId}")
            return
        }
        
        Log.d(TAG, "onTranscriptDelta: speaker=${delta.speaker}, isFinal=${delta.isFinal}, " +
                "text=${delta.text.take(20)}...")
        
        // 增量更新转写视图
        transcriptView?.appendDelta(delta)
    }

    private fun handleSummaryReady(session: CallSessionHandle, summary: CallSummary) {
        if (currentSession?.sessionId != session.sessionId) {
            Log.w(TAG, "Received summary for unknown session: ${session.sessionId}")
            return
        }
        
        Log.d(TAG, "onSummaryReady: durationSec=${summary.durationSec}, " +
                "wordCount=${summary.transcriptWordCount}, callback=${summary.callbackSuggested}")
        
        // 隐藏接管卡和转写视图
        dismissInterceptionCard()
        dismissTranscriptView()
        
        // 显示摘要卡
        showSummaryCard(session, summary)
    }

    private fun handleError(session: CallSessionHandle, code: Int, message: String) {
        Log.e(TAG, "onError: code=$code, message=$message")
        
        // 显示错误提示
        showErrorToast("通话接管出错: $message")
        
        // 清理 UI
        dismissAllUi()
    }

    // ==================== UI 显示/隐藏逻辑 ====================

    private fun showInterceptionCard(descriptor: CallDescriptor, verdict: CallVerdict) {
        if (interceptionCard != null) {
            Log.w(TAG, "Interception card already shown")
            return
        }
        
        interceptionCard = CallInterceptionCard(context)
        interceptionCard?.show(descriptor, verdict, object : CallInterceptionCard.Callback {
            override fun onAbortInterception() {
                handleAbortInterception()
            }
            
            override fun onViewDetails() {
                handleViewDetails()
            }
        })
        
        Log.d(TAG, "Interception card shown")
    }

    private fun dismissInterceptionCard() {
        interceptionCard?.dismiss()
        interceptionCard = null
        Log.d(TAG, "Interception card dismissed")
    }

    private fun showTranscriptView(session: CallSessionHandle) {
        if (transcriptView != null) {
            Log.w(TAG, "Transcript view already shown")
            return
        }
        
        transcriptView = TranscriptView(context)
        transcriptView?.show(session)
        
        Log.d(TAG, "Transcript view shown")
    }

    private fun dismissTranscriptView() {
        transcriptView?.dismiss()
        transcriptView = null
        Log.d(TAG, "Transcript view dismissed")
    }

    private fun showSummaryCard(session: CallSessionHandle, summary: CallSummary) {
        if (summaryCard != null) {
            Log.w(TAG, "Summary card already shown")
            return
        }
        
        summaryCard = CallSummaryCard(context)
        summaryCard?.show(session, summary, object : CallSummaryCard.Callback {
            override fun onCallback() {
                handleCallbackSuggestion(summary)
            }
            
            override fun onViewDetails() {
                handleViewDetailsWithSummary(session, summary)
            }
            
            override fun onDismiss() {
                dismissSummaryCard()
            }
        })
        
        Log.d(TAG, "Summary card shown")
    }

    private fun dismissSummaryCard() {
        summaryCard?.dismiss()
        summaryCard = null
        Log.d(TAG, "Summary card dismissed")
    }

    private fun showRecordNotification(descriptor: CallDescriptor) {
        // TODO: 显示静默通知（仅记录模式）
        Log.d(TAG, "Record-only notification: ${maskPhoneNumber(descriptor.numberE164)}")
    }

    private fun showErrorToast(message: String) {
        // TODO: 显示错误 Toast
        Log.e(TAG, "Error toast: $message")
    }

    private fun dismissAllUi() {
        dismissInterceptionCard()
        dismissTranscriptView()
        dismissSummaryCard()
        Log.d(TAG, "All UI dismissed")
    }

    // ==================== 用户交互处理 ====================

    /**
     * 处理用户中止接管
     */
    private fun handleAbortInterception() {
        Log.d(TAG, "User aborted interception")
        
        val session = currentSession
        if (session != null) {
            // TODO: 调用 CallGate 服务的 endHandleSession()
            // ServiceManager.getService("agent_call_gate").endHandleSession(session)
        }
        
        dismissAllUi()
    }

    /**
     * 处理查看详情（接管中）
     */
    private fun handleViewDetails() {
        Log.d(TAG, "User requested details (during interception)")
        
        // TODO: 跳转到审计时间线（实时视图）
        // Intent intent = new Intent("com.genton.action.VIEW_CALL_SESSION");
        // intent.putExtra("sessionId", currentSession.sessionId);
        // context.startActivity(intent);
    }

    /**
     * 处理查看详情（摘要后）
     */
    private fun handleViewDetailsWithSummary(session: CallSessionHandle, summary: CallSummary) {
        Log.d(TAG, "User requested details (with summary): auditRef=${summary.auditRef}")
        
        // TODO: 跳转到审计时间线（完整视图）
        // Intent intent = new Intent("com.genton.action.VIEW_AUDIT_REF");
        // intent.putExtra("auditRef", summary.auditRef);
        // context.startActivity(intent);
    }

    /**
     * 处理回拨建议
     */
    private fun handleCallbackSuggestion(summary: CallSummary) {
        Log.d(TAG, "User selected callback: ${summary.callbackNumberMasked}")
        
        // TODO: 发起回拨（需要用户二次确认）
        // Intent intent = new Intent(Intent.ACTION_DIAL);
        // intent.setData(Uri.parse("tel:" + summary.callbackNumberMasked));
        // context.startActivity(intent);
    }

    // ==================== 工具方法 ====================

    /**
     * 号码脱敏显示
     */
    private fun maskPhoneNumber(numberE164: String): String {
        if (numberE164.length < 8) {
            return numberE164
        }
        val prefix = numberE164.substring(0, numberE164.length - 8)
        val middle = "****"
        val suffix = numberE164.substring(numberE164.length - 4)
        return "$prefix$middle$suffix"
    }
}
