// AI Native OS - Session View Controller
package com.android.systemui.genton.session;

import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Toast;

import com.genton.agent.SessionHandle;
import com.genton.agent.TurnDelta;
import com.genton.agent.TurnResultSummary;
import com.genton.agent.TaskCardData;
import com.genton.agent.ActionCardData;

import com.android.systemui.genton.background.BackgroundAssistantManager;

/**
 * 会话视图控制器：管理 Assistant Surface 的会话界面（D2.2完整实现）
 * 
 * 职责：
 * - 会话UI生命周期管理
 * - 与AgentSessionService通信（通过SessionServiceConnector）
 * - 会话状态同步（推送优先）
 * - 流式增量处理
 */
public class SessionViewController {

    /** D1.3 后台化：普通模式下助理以常驻胶囊形式最小化存在（P2） */
    private com.android.systemui.genton.background.BackgroundAssistantManager mBackgroundManager;


    private static final String TAG = "SessionViewController";
    
    private static final String DEFAULT_AGENT_ID = "default_assistant";
    
    private final Context mContext;
    private final ViewGroup mContainer;
    
    private SessionView mSessionView;
    private SessionServiceConnector mServiceConnector;
    
    private boolean mIsActive = false;
    private boolean mIsSessionOpened = false;
    
    public SessionViewController(Context context, ViewGroup container) {
        mContext = context;
        mContainer = container;
    }
    
    /**
     * 初始化会话视图
     */
    public void initialize() {
        // 创建会话视图
        mSessionView = new SessionView(mContext);
        mContainer.removeAllViews();
        mContainer.addView(mSessionView, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        
        mSessionView.initialize();
        
        // 设置消息发送监听器
        mSessionView.setOnMessageSendListener(text -> {
            onUserMessageSent(text);
        });
        
        // 创建服务连接器
        mServiceConnector = new SessionServiceConnector(mContext);
        mServiceConnector.setEventListener(mSessionEventListener);
        
        Log.i(TAG, "Session view initialized with service connector");
    }
    
    /**
     * 用户消息发送
     */
    private void onUserMessageSent(String text) {
        if (!mServiceConnector.isBound()) {
            Log.w(TAG, "Service not bound, connecting...");
            mSessionView.addSystemMessage("正在连接服务...");
            mServiceConnector.bind();
            return;
        }
        
        if (!mIsSessionOpened) {
            Log.w(TAG, "Session not opened, opening...");
            mServiceConnector.openSession(DEFAULT_AGENT_ID);
            return;
        }
        
        // 提交用户轮次
        mServiceConnector.submitUserTurn(text);
    }
    
    /**
     * 会话事件监听器
     */
    private final SessionServiceConnector.SessionEventListener mSessionEventListener = 
        new SessionServiceConnector.SessionEventListener() {
        
        @Override
        public void onServiceConnected() {
            Log.i(TAG, "Service connected");
            mSessionView.setStatus("已连接");
            mSessionView.addSystemMessage("服务已连接");
            
            // 自动打开会话
            mServiceConnector.openSession(DEFAULT_AGENT_ID);
        }
        
        @Override
        public void onServiceDisconnected() {
            Log.w(TAG, "Service disconnected");
            mSessionView.setStatus("连接断开");
            mSessionView.addSystemMessage("服务连接断开");
            mIsSessionOpened = false;
        }
        
        @Override
        public void onSessionOpened(SessionHandle handle) {
            Log.i(TAG, "Session opened: " + handle.sessionId);
            mIsSessionOpened = true;
            mSessionView.setStatus("会话已就绪");
        }
        
        @Override
        public void onSessionStateChanged(int state) {
            // 会话状态：0=ACTIVE, 1=WAITING_CONFIRM, 2=IDLE
            String stateText;
            switch (state) {
                case 0: // ACTIVE
                    stateText = "处理中";
                    mSessionView.showThinking(true);
                    break;
                case 1: // WAITING_CONFIRM
                    stateText = "等待确认";
                    mSessionView.showThinking(false);
                    break;
                case 2: // IDLE
                    stateText = "空闲";
                    mSessionView.showThinking(false);
                    break;
                default:
                    stateText = "未知状态";
            }
            mSessionView.setStatus(stateText);
            Log.d(TAG, "Session state changed: " + state + " (" + stateText + ")");
        }
        
        @Override
        public void onTurnDelta(String turnId, TurnDelta delta) {
            // 流式增量处理
            switch (delta.type) {
                case 0: // thinking
                    mSessionView.setThinkingText("正在思考...");
                    mSessionView.showThinking(true);
                    break;
                    
                case 1: // text_stream
                    mSessionView.showThinking(false);
                    mSessionView.appendToAssistantMessage(delta.text);
                    break;
                    
                case 2: // tool_activity
                    String activityText = getToolActivityText(delta.toolKind);
                    mSessionView.setThinkingText(activityText);
                    mSessionView.showThinking(true);
                    break;
            }
        }
        
        @Override
        public void onTurnCompleted(String turnId, TurnResultSummary result) {
            Log.i(TAG, "Turn completed: " + turnId);
            mSessionView.showThinking(false);
            mSessionView.setStatus("空闲");
        }
        
        @Override
        public void onTaskCardChanged(TaskCardData card) {
            mSessionView.updateTaskCard(card);
        }
        
        @Override
        public void onActionCard(ActionCardData card) {
            mSessionView.updateActionCard(card);
        }
        
        @Override
        public void onSessionError(int code, String message) {
            Log.e(TAG, "Session error: " + code + " - " + message);
            mSessionView.showThinking(false);
            mSessionView.addSystemMessage("错误: " + message);
            Toast.makeText(mContext, "会话错误: " + message, Toast.LENGTH_SHORT).show();
        }
    };
    
    /**
     * 获取工具活动文本
     */
    private String getToolActivityText(int toolKind) {
        switch (toolKind) {
            case 0: return "正在打开应用...";
            case 1: return "正在发送消息...";
            case 2: return "正在修改设置...";
            case 3: return "正在操作文件...";
            default: return "正在执行操作...";
        }
    }
    
    /**
     * 成为活跃状态
     */
    public void onBecomeActive() {
        mIsActive = true;
        
        if (mSessionView != null) {
            mSessionView.onResume();
        }
        
        // 绑定服务
        if (mServiceConnector != null && !mServiceConnector.isBound()) {
            mServiceConnector.bind();
        }
        
        Log.d(TAG, "Session view became active");
    }
    
    /**
     * 成为非活跃状态
     */
    public void onBecomeInactive() {
        mIsActive = false;
        
        if (mSessionView != null) {
            mSessionView.onPause();
        }
        
        // 保持服务连接（不解绑），以便后台接收推送
        
        Log.d(TAG, "Session view became inactive");
    }
    
    /**
     * 是否活跃
     */
    public boolean isActive() {
        return mIsActive;
    }
    
    /**
     * 获取会话视图
     */
    public SessionView getSessionView() {
        return mSessionView;
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        // 解绑服务
        if (mServiceConnector != null) {
            mServiceConnector.unbind();
            mServiceConnector = null;
        }
        
        // D1.3：销毁后台管理器
        if (mBackgroundManager != null) {
            mBackgroundManager.destroy();
            mBackgroundManager = null;
        }
        
        if (mSessionView != null) {
            mSessionView.destroy();
            mSessionView = null;
        }
        
        mContainer.removeAllViews();
        mIsSessionOpened = false;
        
        Log.i(TAG, "Session view destroyed");
    }
}
