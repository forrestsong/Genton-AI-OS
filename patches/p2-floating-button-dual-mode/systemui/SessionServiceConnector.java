// AI Native OS - Session Service Connector
package com.android.systemui.ainative.session;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.ainative.agent.IAgentSessionService;
import com.ainative.agent.IAgentSessionListener;
import com.ainative.agent.IAgentRuntimeService;
import com.ainative.agent.AgentHandle;
import com.ainative.agent.AgentRuntimeStatus;
import com.ainative.agent.SessionOpenRequest;
import com.ainative.agent.SessionHandle;
import com.ainative.agent.UserTurn;
import com.ainative.agent.TurnDelta;
import com.ainative.agent.TurnResultSummary;
import com.ainative.agent.TaskCardData;
import com.ainative.agent.ActionCardData;

/**
 * 会话服务连接器（D2.2 完整实现）：连接AgentSessionService
 * 
 * 职责：
 * - 绑定AgentSessionService
 * - 实现IAgentSessionListener接收推送
 * - 会话状态以推送为准
 * - 推送为准 + 兜底ACB getAgentStatus（D2.2 核心）
 */
public class SessionServiceConnector {
    private static final String TAG = "SessionServiceConnector";
    
    // Agent大脑服务包名（需要与实际包名一致）
    private static final String AGENT_BRAIN_PACKAGE = "com.ainative.brain";
    private static final String AGENT_SESSION_SERVICE = "com.ainative.brain.AgentSessionService";
    
    // D2.2：推送超时阈值（推送未到达时触发 ACB 兜底）
    private static final long PUSH_TIMEOUT_MS = 5000; // 5秒无推送则兜底轮询
    private static final long FALLBACK_POLL_INTERVAL_MS = 2000; // 兜底轮询间隔 2秒
    
    private final Context mContext;
    private final Handler mHandler;
    
    private IAgentSessionService mSessionService;
    private IAgentRuntimeService mRuntimeService;
    private SessionHandle mSessionHandle;
    private boolean mIsBound = false;
    private boolean mIsRuntimeBound = false;
    
    private SessionEventListener mEventListener;
    
    // D2.2：推送健康检测
    private long mLastPushTimestamp = 0;
    private boolean mIsFallbackMode = false;
    private Runnable mFallbackPollRunnable;
    
    public interface SessionEventListener {
        void onSessionOpened(SessionHandle handle);
        void onSessionStateChanged(int state);
        void onTurnDelta(String turnId, TurnDelta delta);
        void onTurnCompleted(String turnId, TurnResultSummary result);
        void onTaskCardChanged(TaskCardData card);
        void onActionCard(ActionCardData card);
        void onSessionError(int code, String message);
        void onServiceConnected();
        void onServiceDisconnected();
    }
    
    public SessionServiceConnector(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        
        // D2.2：初始化兜底轮询任务
        mFallbackPollRunnable = new Runnable() {
            @Override
            public void run() {
                pollAgentStatus();
                if (mIsFallbackMode) {
                    mHandler.postDelayed(this, FALLBACK_POLL_INTERVAL_MS);
                }
            }
        };
    }
    
    /**
     * 绑定服务
     */
    public void bind() {
        if (mIsBound) {
            Log.w(TAG, "Already bound");
            return;
        }
        
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(AGENT_BRAIN_PACKAGE, AGENT_SESSION_SERVICE));
        
        boolean success = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        
        if (success) {
            Log.i(TAG, "Binding to AgentSessionService...");
        } else {
            Log.e(TAG, "Failed to bind to AgentSessionService");
            // 通知失败
            if (mEventListener != null) {
                mEventListener.onSessionError(-1, "无法连接到Agent大脑服务");
            }
        }
        
        // D2.2：同时绑定 ACB Runtime Service（兜底用）
        bindRuntimeService();
    }
    
    /**
     * 解绑服务
     */
    public void unbind() {
        if (!mIsBound) {
            return;
        }
        
        // 结束会话
        if (mSessionHandle != null && mSessionService != null) {
            try {
                mSessionService.endSession(mSessionHandle, 1); // reason=1: app_bg
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to end session", e);
            }
        }
        
        mContext.unbindService(mServiceConnection);
        mIsBound = false;
        mSessionService = null;
        mSessionHandle = null;
        
        // D2.2：解绑 Runtime Service + 停止兜底轮询
        unbindRuntimeService();
        stopFallbackPolling();
        
        Log.i(TAG, "Service unbound");
    }
    
    /**
     * 打开会话
     */
    public void openSession(String agentId) {
        if (mSessionService == null) {
            Log.e(TAG, "Service not bound");
            return;
        }
        
        try {
            SessionOpenRequest request = new SessionOpenRequest();
            request.sessionId = "session_" + System.currentTimeMillis();
            request.agentId = agentId;
            request.entryMode = 0; // 0=new, 1=resume
            request.resumePackId = null;
            
            SessionHandle handle = new SessionHandle();
            
            int status = mSessionService.openSession(request, mSessionListener, handle);
            
            if (status == IAgentSessionService.STATUS_OK) {
                mSessionHandle = handle;
                Log.i(TAG, "Session opened: " + handle.sessionId);
                
                // D2.2：开启推送健康监测
                mLastPushTimestamp = System.currentTimeMillis();
                startPushHealthMonitoring();
                
                if (mEventListener != null) {
                    mEventListener.onSessionOpened(handle);
                }
            } else {
                Log.e(TAG, "Failed to open session: status=" + status);
                if (mEventListener != null) {
                    mEventListener.onSessionError(status, "打开会话失败");
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to open session", e);
            if (mEventListener != null) {
                mEventListener.onSessionError(-1, "打开会话异常: " + e.getMessage());
            }
        }
    }
    
    /**
     * 提交用户轮次
     */
    public void submitUserTurn(String text) {
        if (mSessionService == null || mSessionHandle == null) {
            Log.e(TAG, "Session not opened");
            return;
        }
        
        try {
            UserTurn turn = new UserTurn();
            // [P2 修复] UserTurn 实际字段名（见 com/ainative/agent/UserTurn.aidl）
            turn.kind = 0; // 0=text, 1=voice, 2=attachment
            turn.content = text;
            turn.createdAtRealtimeNanos = System.nanoTime();
            
            String[] turnId = new String[1];
            int status = mSessionService.submitUserTurn(mSessionHandle, turn, turnId);
            
            if (status == IAgentSessionService.STATUS_OK) {
                Log.i(TAG, "User turn submitted: " + turnId[0]);
            } else {
                Log.e(TAG, "Failed to submit turn: status=" + status);
                if (mEventListener != null) {
                    mEventListener.onSessionError(status, "提交轮次失败");
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to submit turn", e);
            if (mEventListener != null) {
                mEventListener.onSessionError(-1, "提交轮次异常: " + e.getMessage());
            }
        }
    }
    
    /**
     * 取消当前轮次
     */
    public void cancelTurn(String turnId) {
        if (mSessionService == null || mSessionHandle == null) {
            Log.e(TAG, "Session not opened");
            return;
        }
        
        try {
            int status = mSessionService.cancelTurn(mSessionHandle, turnId);
            Log.i(TAG, "Turn cancelled: " + turnId + ", status=" + status);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to cancel turn", e);
        }
    }
    
    /**
     * 设置事件监听器
     */
    public void setEventListener(SessionEventListener listener) {
        mEventListener = listener;
    }
    
    /**
     * 是否已绑定服务
     */
    public boolean isBound() {
        return mIsBound;
    }
    
    /**
     * 服务连接
     */
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mSessionService = IAgentSessionService.Stub.asInterface(service);
            mIsBound = true;
            
            Log.i(TAG, "Service connected: " + name);
            
            if (mEventListener != null) {
                mEventListener.onServiceConnected();
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mSessionService = null;
            mIsBound = false;
            
            Log.w(TAG, "Service disconnected: " + name);
            
            if (mEventListener != null) {
                mEventListener.onServiceDisconnected();
            }
        }
    };
    
    /**
     * 会话监听器实现
     */
    private final IAgentSessionListener.Stub mSessionListener = new IAgentSessionListener.Stub() {
        @Override
        public void onSessionStateChanged(SessionHandle handle, int state) {
            mHandler.post(() -> {
                // D2.2：更新推送时间戳，退出兜底模式
                updatePushTimestamp();
                
                Log.d(TAG, "Session state changed: " + state);
                if (mEventListener != null) {
                    mEventListener.onSessionStateChanged(state);
                }
            });
        }
        
        @Override
        public void onTurnDelta(SessionHandle handle, String turnId, TurnDelta delta) {
            mHandler.post(() -> {
                // D2.2：更新推送时间戳，退出兜底模式
                updatePushTimestamp();
                
                Log.d(TAG, "Turn delta: type=" + delta.type);
                if (mEventListener != null) {
                    mEventListener.onTurnDelta(turnId, delta);
                }
            });
        }
        
        @Override
        public void onTurnCompleted(SessionHandle handle, String turnId, TurnResultSummary result) {
            mHandler.post(() -> {
                // D2.2：更新推送时间戳，退出兜底模式
                updatePushTimestamp();
                
                Log.i(TAG, "Turn completed: " + turnId);
                if (mEventListener != null) {
                    mEventListener.onTurnCompleted(turnId, result);
                }
            });
        }
        
        @Override
        public void onTaskCardChanged(SessionHandle handle, TaskCardData card) {
            mHandler.post(() -> {
                // D2.2：更新推送时间戳
                updatePushTimestamp();
                
                Log.d(TAG, "Task card changed: " + card.taskId);
                if (mEventListener != null) {
                    mEventListener.onTaskCardChanged(card);
                }
            });
        }
        
        @Override
        public void onActionCard(SessionHandle handle, ActionCardData card) {
            mHandler.post(() -> {
                // D2.2：更新推送时间戳
                updatePushTimestamp();
                
                Log.d(TAG, "Action card: " + card.cardId + ", type=" + card.type);
                if (mEventListener != null) {
                    mEventListener.onActionCard(card);
                }
            });
        }
        
        @Override
        public void onSessionError(SessionHandle handle, int code, String message) {
            mHandler.post(() -> {
                // D2.2：错误也算推送活跃
                updatePushTimestamp();
                
                Log.e(TAG, "Session error: code=" + code + ", message=" + message);
                if (mEventListener != null) {
                    mEventListener.onSessionError(code, message);
                }
            });
        }
    };
    
    // ==================== D2.2 核心：推送为准 + ACB 兜底 ====================
    
    /**
     * 绑定 ACB Runtime Service（兜底用）
     */
    private void bindRuntimeService() {
        if (mIsRuntimeBound) {
            return;
        }
        
        try {
            // Runtime Service 由系统服务提供，通过 ServiceManager 获取
            IBinder binder = android.os.ServiceManager.getService("ainative_agent_runtime");
            if (binder != null) {
                mRuntimeService = IAgentRuntimeService.Stub.asInterface(binder);
                mIsRuntimeBound = true;
                Log.i(TAG, "Runtime Service bound (fallback ready)");
            } else {
                Log.w(TAG, "Runtime Service not available (fallback disabled)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind Runtime Service", e);
        }
    }
    
    /**
     * 解绑 Runtime Service
     */
    private void unbindRuntimeService() {
        if (mIsRuntimeBound) {
            mRuntimeService = null;
            mIsRuntimeBound = false;
            Log.i(TAG, "Runtime Service unbound");
        }
    }
    
    /**
     * 更新推送时间戳（收到推送时调用）
     */
    private void updatePushTimestamp() {
        mLastPushTimestamp = System.currentTimeMillis();
        
        // 如果当前在兜底模式，退出兜底模式
        if (mIsFallbackMode) {
            Log.i(TAG, "Push recovered, exiting fallback mode");
            mIsFallbackMode = false;
            stopFallbackPolling();
        }
    }
    
    /**
     * 开启推送健康监测
     */
    private void startPushHealthMonitoring() {
        // 延迟检测（首次 PUSH_TIMEOUT_MS 后检查）
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkPushHealthAndFallback();
                if (mSessionHandle != null) {
                    mHandler.postDelayed(this, PUSH_TIMEOUT_MS);
                }
            }
        }, PUSH_TIMEOUT_MS);
        
        Log.d(TAG, "Push health monitoring started");
    }
    
    /**
     * 检查推送健康状态，必要时启动兜底轮询
     */
    private void checkPushHealthAndFallback() {
        if (mSessionHandle == null) {
            return;
        }
        
        long now = System.currentTimeMillis();
        long elapsed = now - mLastPushTimestamp;
        
        if (elapsed > PUSH_TIMEOUT_MS && !mIsFallbackMode) {
            Log.w(TAG, "Push timeout detected (" + elapsed + "ms), entering fallback mode");
            mIsFallbackMode = true;
            startFallbackPolling();
        }
    }
    
    /**
     * 启动兜底轮询（通过 ACB getAgentStatus）
     */
    private void startFallbackPolling() {
        if (!mIsRuntimeBound || mRuntimeService == null) {
            Log.w(TAG, "Cannot start fallback polling: Runtime Service not available");
            return;
        }
        
        Log.i(TAG, "Starting fallback polling via ACB getAgentStatus");
        mHandler.post(mFallbackPollRunnable);
    }
    
    /**
     * 停止兜底轮询
     */
    private void stopFallbackPolling() {
        mHandler.removeCallbacks(mFallbackPollRunnable);
        Log.d(TAG, "Fallback polling stopped");
    }
    
    /**
     * 兜底轮询任务实现：通过 ACB getAgentStatus 查询状态
     */
    private void pollAgentStatus() {
        if (!mIsRuntimeBound || mRuntimeService == null || mSessionHandle == null) {
            return;
        }
        
        try {
            // AIDL 契约：getAgentStatus(in AgentHandle handle)
            AgentHandle agentHandle = new AgentHandle();
            agentHandle.agentId = mSessionHandle.ownerAgentId;
            
            AgentRuntimeStatus status = mRuntimeService.getAgentStatus(agentHandle);
            
            if (status != null) {
                Log.d(TAG, "Fallback: ACB status=" + status.state + ", detail=" + status.detail);
                
                // 将 ACB 状态映射到会话状态
                int sessionState = mapAcbStateToSessionState(status.state);
                
                if (mEventListener != null) {
                    mEventListener.onSessionStateChanged(sessionState);
                }
            } else {
                Log.w(TAG, "Fallback: ACB status is null (agent not found)");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Fallback: Failed to get ACB status", e);
        }
    }
    
    /**
     * 映射 ACB 状态到会话状态
     * 
     * ACB 状态：
     * 0=SPAWNING, 1=PLANNING, 2=ACTING, 3=WAITING, 4=SUSPENDED, 5=TERMINATED
     * 
     * 会话状态：
     * 0=ACTIVE, 1=WAITING_CONFIRM, 2=IDLE
     */
    private int mapAcbStateToSessionState(int acbState) {
        switch (acbState) {
            case 0: // SPAWNING
            case 1: // PLANNING
            case 2: // ACTING
                return 0; // ACTIVE
            case 3: // WAITING
                return 1; // WAITING_CONFIRM
            case 4: // SUSPENDED
            case 5: // TERMINATED
            default:
                return 2; // IDLE
        }
    }
}
