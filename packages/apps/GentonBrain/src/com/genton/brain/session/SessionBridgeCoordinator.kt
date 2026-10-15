// SessionBridgeCoordinator.kt
// B3.1/A11.1 会话桥协调器：协调大脑与 SystemUI 的会话交互
package com.genton.brain.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.genton.agent.IAgentSessionListener
import com.genton.agent.IAgentSessionService
import com.genton.ui.IAgentAssistantUi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 会话桥协调器
 * 
 * 核心职责：
 * 1. 管理大脑侧会话服务绑定（提供给 SystemUI）
 * 2. 管理 SystemUI 侧 UI 服务反向注册
 * 3. 双向通信桥接
 * 
 * 架构设计：
 * - SystemUI 通过 BIND_AGENT_UI 权限绑定大脑的会话服务
 * - 大脑通过反向注册接口获取 SystemUI 的 UI 服务
 * - 对齐 A11.1/D12 会话桥 AIDL 落码
 * 
 * 权限要求：
 * - SystemUI 需要 BIND_AGENT_UI 权限
 * - 大脑服务声明 android:permission="com.genton.permission.BIND_AGENT_UI"
 */
class SessionBridgeCoordinator(
    private val context: Context,
    private val sessionService: AgentSessionService
) {
    
    companion object {
        private const val TAG = "SessionBridgeCoordinator"
        
        // SystemUI 包名和服务
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val SYSTEMUI_UI_SERVICE = "com.android.systemui.genton.session.SessionUiService"
        
        // 绑定超时（5 秒）
        private const val BIND_TIMEOUT_MS = 5000L
    }
    
    // SystemUI UI 服务（反向注册）
    @Volatile
    private var assistantUi: IAgentAssistantUi? = null
    
    // 服务连接状态
    private var serviceConnection: ServiceConnection? = null
    
    /**
     * 获取会话服务（提供给 SystemUI 绑定）
     * 
     * @return IAgentSessionService Binder
     */
    fun getSessionServiceBinder(): IBinder {
        return sessionService.asBinder()
    }
    
    /**
     * SystemUI 反向注册 UI 服务
     * 
     * @param uiService SystemUI 的 UI 服务
     */
    fun registerAssistantUi(uiService: IAgentAssistantUi) {
        assistantUi = uiService
        Log.i(TAG, "SystemUI assistant UI registered")
    }
    
    /**
     * SystemUI 取消注册 UI 服务
     */
    fun unregisterAssistantUi() {
        assistantUi = null
        Log.i(TAG, "SystemUI assistant UI unregistered")
    }
    
    /**
     * 获取 SystemUI UI 服务
     * 
     * @return IAgentAssistantUi，未注册返回 null
     */
    fun getAssistantUi(): IAgentAssistantUi? {
        return assistantUi
    }
    
    /**
     * 主动绑定 SystemUI UI 服务（备用方案）
     * 
     * 注意：通常由 SystemUI 主动绑定大脑服务并反向注册，
     * 这个方法用于大脑主动发起绑定的场景（如系统启动时预绑定）
     * 
     * @return 是否成功绑定
     */
    fun bindSystemUiService(): Boolean {
        if (assistantUi != null) {
            Log.d(TAG, "SystemUI service already bound")
            return true
        }
        
        val bindLatch = CountDownLatch(1)
        var bindSuccess = false
        
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val uiService = IAgentAssistantUi.Stub.asInterface(service)
                assistantUi = uiService
                bindSuccess = true
                bindLatch.countDown()
                Log.i(TAG, "SystemUI service bound successfully")
            }
            
            override fun onServiceDisconnected(name: ComponentName) {
                assistantUi = null
                Log.w(TAG, "SystemUI service disconnected")
            }
        }
        
        try {
            val intent = Intent().apply {
                component = ComponentName(SYSTEMUI_PACKAGE, SYSTEMUI_UI_SERVICE)
            }
            
            val bound = context.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            
            if (!bound) {
                Log.e(TAG, "Failed to bind SystemUI service")
                return false
            }
            
            serviceConnection = connection
            
            // 等待绑定完成
            val success = bindLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            
            if (!success) {
                Log.e(TAG, "SystemUI service binding timeout")
                unbindSystemUiService()
                return false
            }
            
            return bindSuccess
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind SystemUI service", e)
            return false
        }
    }
    
    /**
     * 解绑 SystemUI UI 服务
     */
    fun unbindSystemUiService() {
        serviceConnection?.let { connection ->
            try {
                context.unbindService(connection)
                Log.i(TAG, "SystemUI service unbound")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unbind SystemUI service", e)
            }
        }
        serviceConnection = null
        assistantUi = null
    }
    
    /**
     * 检查 SystemUI UI 服务是否可用
     * 
     * @return 是否可用
     */
    fun isAssistantUiAvailable(): Boolean {
        val ui = assistantUi
        return ui != null && try {
            ui.asBinder().isBinderAlive
        } catch (e: Exception) {
            false
        }
    }
}
