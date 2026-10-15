// Genton (AI Native OS) - 会话服务绑定宿主
//
// [P4] 说明：IAgentSessionService 的实现是 Binder Stub，不能直接作为
// android:name 的 Service 声明（Android 要求 Service 子类且具备无参构造）。
// 本类作为其绑定宿主：onBind 返回会话服务 binder。
package com.genton.brain.session

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

import com.genton.agent.IAgentSessionService
import com.genton.brain.BrainApplication

/** 会话服务绑定宿主（供 SystemUI 的 SessionServiceConnector 绑定） */
class AgentSessionBinderService : Service() {

    companion object {
        private const val TAG = "AgentSessionBinder"
    }

    private var mImpl: IAgentSessionService? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val app = BrainApplication.getInstance()
            mImpl = AgentSessionServiceImpl(this, app)
            Log.i(TAG, "session service impl created")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to create session service impl", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind: " + intent?.action)
        return mImpl?.asBinder()
    }
}
