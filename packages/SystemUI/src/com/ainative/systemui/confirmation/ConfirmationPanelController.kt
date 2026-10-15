package com.ainative.systemui.confirmation

import android.content.Context
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.ainative.policy.IPolicyEngine
import com.ainative.ui.ConfirmationUiRequest
import com.ainative.ui.IAgentConfirmationHost
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * 确认面板控制器
 * 
 * 任务：D3.1 确认面板
 * 
 * 核心职责：
 * 1. 实现 IAgentConfirmationHost 接口（由 A11.2 服务端调用）
 * 2. 管理确认队列（exclusive 模式串行展示）
 * 3. 用户决策回灌到 PolicyEngine
 * 4. FLAG_SECURE 窗口管理
 * 
 * 安全约束：
 * - 用户决策直接调用 PolicyEngine.reportUserDecision()（不经大脑）
 * - FLAG_SECURE 强制启用（防截屏/录屏）
 * - 确认 ID 防重放检测
 * - 超时自动拒绝（Fail-secure）
 * 
 * 依赖：
 * - A11.2 确认宿主服务端（回灌接口定义）
 * - A7.1 策略引擎服务端（reportUserDecision）
 * - E1.1 安全基线规范（FLAG_SECURE 边界）
 */
class ConfirmationPanelController(
    private val context: Context,
    private val windowManager: WindowManager
) : ConfirmationPanelView.ConfirmationCallback {
    
    private val panelView: ConfirmationPanelView = ConfirmationPanelView(context)
    private val confirmQueue = LinkedBlockingQueue<ConfirmationUiRequest>()
    private val processedConfirmIds = ConcurrentHashMap.newKeySet<String>()
    
    @Volatile
    private var isShowing = false
    
    @Volatile
    private var policyEngine: IPolicyEngine? = null
    
    companion object {
        private const val TAG = "ConfirmationPanelCtrl"
        
        // 返回码（对齐 IAgentConfirmationHost）
        const val RESULT_OK = 0
        const val RESULT_INVALID = 1
        const val RESULT_DUPLICATE = 2
        const val RESULT_DENIED = 3
        
        // 用户决策（对齐 PolicyEngine）
        const val DECISION_APPROVE = 0
        const val DECISION_REJECT = 1
        const val DECISION_NEVER = 2
        const val DECISION_TIMEOUT = 3
    }
    
    init {
        panelView.visibility = android.view.View.GONE
    }
    
    /**
     * 设置策略引擎引用（用于回灌决策）
     */
    fun setPolicyEngine(engine: IPolicyEngine?) {
        this.policyEngine = engine
        Log.i(TAG, "PolicyEngine set: ${engine != null}")
    }
    
    /**
     * 获取 Binder 接口实现
     */
    fun getConfirmationHost(): IAgentConfirmationHost.Stub {
        return object : IAgentConfirmationHost.Stub() {
            override fun showConfirmation(request: ConfirmationUiRequest?): Int {
                if (request == null) {
                    Log.w(TAG, "showConfirmation: null request")
                    return RESULT_INVALID
                }
                
                // 验证 confirmId
                if (request.confirmId.isNullOrEmpty()) {
                    Log.w(TAG, "showConfirmation: empty confirmId")
                    return RESULT_INVALID
                }
                
                // 防重放检测
                if (processedConfirmIds.contains(request.confirmId)) {
                    Log.w(TAG, "showConfirmation: duplicate confirmId ${request.confirmId}")
                    return RESULT_DUPLICATE
                }
                
                // 标记为已处理（即使后续失败也不允许重试）
                processedConfirmIds.add(request.confirmId)
                
                // 入队
                try {
                    confirmQueue.put(request)
                    Log.i(TAG, "Confirmation queued: ${request.confirmId}")
                    
                    // 如果当前无确认展示，立即处理
                    if (!isShowing) {
                        processNextConfirmation()
                    }
                    
                    return RESULT_OK
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Failed to queue confirmation", e)
                    return RESULT_INVALID
                }
            }
            
            override fun dismissConfirmation(confirmId: String?, reason: Int) {
                if (confirmId == null) return
                
                val currentConfirmId = panelView.getCurrentConfirmId()
                if (currentConfirmId == confirmId) {
                    Log.i(TAG, "Dismissing confirmation: $confirmId, reason: $reason")
                    hidePanel()
                    processNextConfirmation()
                }
            }
        }
    }
    
    /**
     * 处理队列中的下一个确认请求
     */
    private fun processNextConfirmation() {
        if (isShowing) {
            // 当前有确认在展示，等待其完成
            return
        }
        
        val request = confirmQueue.poll() ?: return
        
        // 在主线程显示面板
        android.os.Handler(context.mainLooper).post {
            showPanel(request)
        }
    }
    
    /**
     * 显示确认面板
     */
    private fun showPanel(request: ConfirmationUiRequest) {
        if (isShowing) {
            Log.w(TAG, "Panel already showing, skipping")
            return
        }
        
        try {
            // 创建 FLAG_SECURE 窗口参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_SECURE, // 防截屏/录屏
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                title = "AI Native Confirmation"
            }
            
            // 显示面板
            panelView.showRequest(request, this)
            windowManager.addView(panelView, params)
            isShowing = true
            
            Log.i(TAG, "Confirmation panel shown for: ${request.confirmId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show confirmation panel", e)
            isShowing = false
            processNextConfirmation()
        }
    }
    
    /**
     * 隐藏确认面板
     */
    private fun hidePanel() {
        if (!isShowing) return
        
        try {
            android.os.Handler(context.mainLooper).post {
                panelView.hide()
                windowManager.removeView(panelView)
                isShowing = false
                
                Log.i(TAG, "Confirmation panel hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide confirmation panel", e)
            isShowing = false
        }
    }
    
    // ConfirmationCallback 实现
    
    override fun onApprove(confirmId: String) {
        Log.i(TAG, "User approved: $confirmId")
        reportDecision(confirmId, DECISION_APPROVE)
        hidePanel()
        processNextConfirmation()
    }
    
    override fun onReject(confirmId: String) {
        Log.i(TAG, "User rejected: $confirmId")
        reportDecision(confirmId, DECISION_REJECT)
        hidePanel()
        processNextConfirmation()
    }
    
    override fun onNever(confirmId: String) {
        Log.i(TAG, "User chose never: $confirmId")
        reportDecision(confirmId, DECISION_NEVER)
        hidePanel()
        processNextConfirmation()
    }
    
    override fun onTimeout(confirmId: String) {
        Log.i(TAG, "Confirmation timeout: $confirmId")
        reportDecision(confirmId, DECISION_TIMEOUT)
        hidePanel()
        processNextConfirmation()
    }
    
    /**
     * 回灌用户决策到策略引擎
     */
    private fun reportDecision(confirmId: String, decision: Int) {
        val engine = policyEngine
        if (engine == null) {
            Log.e(TAG, "PolicyEngine not available, cannot report decision")
            return
        }
        
        try {
            // TODO: 调用 PolicyEngine.reportUserDecision(confirmId, decision)
            // 当前占位实现
            Log.i(TAG, "Reporting decision to PolicyEngine: confirmId=$confirmId, decision=$decision")
            
            // 实际实现示例：
            // engine.reportUserDecision(confirmId, decision)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to report decision to PolicyEngine", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun destroy() {
        hidePanel()
        confirmQueue.clear()
        processedConfirmIds.clear()
        policyEngine = null
    }
}
