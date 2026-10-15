// AI Native OS - Session View
package com.android.systemui.ainative.session;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.ainative.session.message.MessageBubble;

import com.ainative.agent.TaskCardData;
import com.ainative.agent.ActionCardData;

/**
 * 会话视图：Assistant Surface 的主界面（D2.1完整实现）
 * 
 * 包含：
 * - 会话流 RecyclerView（虚拟化长会话：消息气泡 + 语音/附件 + 任务卡 + 动作卡）
 * - 输入框和发送按钮
 * - 思考指示器
 * - 状态栏
 * 
 * D2.1 虚拟化：使用 RecyclerView + ConversationRecyclerAdapter（DiffUtil 增量刷新、
 * ViewHolder 复用），替换旧的 ListView + BaseAdapter 以支持长会话性能。
 */
public class SessionView extends FrameLayout {
    private static final String TAG = "SessionView";
    
    private LinearLayout mContentLayout;
    private RecyclerView mConversationList;
    private LinearLayoutManager mLayoutManager;
    private ConversationRecyclerAdapter mAdapter;
    
    private LinearLayout mInputLayout;
    private EditText mInputField;
    private Button mSendButton;
    
    private LinearLayout mThinkingIndicator;
    private ProgressBar mThinkingProgress;
    private TextView mThinkingText;
    
    private TextView mStatusText;
    
    private boolean mIsInitialized = false;
    private boolean mIsThinking = false;
    
    private OnMessageSendListener mMessageSendListener;
    
    public interface OnMessageSendListener {
        void onMessageSend(String text);
    }
    
    public SessionView(Context context) {
        super(context);
        init();
    }
    
    public SessionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        setBackgroundColor(Color.parseColor("#F5F5F5"));
    }
    
    /**
     * 初始化视图
     */
    public void initialize() {
        if (mIsInitialized) {
            return;
        }
        
        // 主布局
        mContentLayout = new LinearLayout(getContext());
        mContentLayout.setOrientation(LinearLayout.VERTICAL);
        addView(mContentLayout, new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ));
        
        // 状态栏
        mStatusText = new TextView(getContext());
        mStatusText.setText("AI Assistant");
        mStatusText.setTextSize(16);
        mStatusText.setTextColor(Color.WHITE);
        mStatusText.setBackgroundColor(Color.parseColor("#2196F3"));
        mStatusText.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        mStatusText.setGravity(Gravity.CENTER);
        mContentLayout.addView(mStatusText, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        
        // 会话列表（RecyclerView 虚拟化）
        mConversationList = new RecyclerView(getContext());
        mLayoutManager = new LinearLayoutManager(getContext());
        mLayoutManager.setStackFromEnd(true);  // 从底部堆叠，新消息在下方
        mConversationList.setLayoutManager(mLayoutManager);
        mAdapter = new ConversationRecyclerAdapter(getContext());
        mAdapter.setHasStableIds(true);
        mConversationList.setAdapter(mAdapter);
        
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        );
        listParams.weight = 1;
        mContentLayout.addView(mConversationList, listParams);
        
        // 思考指示器
        mThinkingIndicator = new LinearLayout(getContext());
        mThinkingIndicator.setOrientation(LinearLayout.HORIZONTAL);
        mThinkingIndicator.setGravity(Gravity.CENTER);
        mThinkingIndicator.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        mThinkingIndicator.setBackgroundColor(Color.parseColor("#E3F2FD"));
        mThinkingIndicator.setVisibility(GONE);
        
        mThinkingProgress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleSmall);
        mThinkingIndicator.addView(mThinkingProgress);
        
        mThinkingText = new TextView(getContext());
        mThinkingText.setText("正在思考...");
        mThinkingText.setTextSize(14);
        mThinkingText.setTextColor(Color.parseColor("#1976D2"));
        LinearLayout.LayoutParams thinkingTextParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        thinkingTextParams.leftMargin = dpToPx(8);
        mThinkingIndicator.addView(mThinkingText, thinkingTextParams);
        
        mContentLayout.addView(mThinkingIndicator, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        
        // 输入布局
        mInputLayout = new LinearLayout(getContext());
        mInputLayout.setOrientation(LinearLayout.HORIZONTAL);
        mInputLayout.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        mInputLayout.setBackgroundColor(Color.WHITE);
        
        // 输入框
        mInputField = new EditText(getContext());
        mInputField.setHint("输入消息...");
        mInputField.setTextSize(16);
        mInputField.setBackgroundColor(Color.parseColor("#F5F5F5"));
        mInputField.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        mInputField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mInputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.weight = 1;
        inputParams.rightMargin = dpToPx(8);
        mInputLayout.addView(mInputField, inputParams);
        
        // 发送按钮
        mSendButton = new Button(getContext());
        mSendButton.setText("发送");
        mSendButton.setTextSize(14);
        mSendButton.setBackgroundColor(Color.parseColor("#2196F3"));
        mSendButton.setTextColor(Color.WHITE);
        mSendButton.setOnClickListener(v -> sendMessage());
        mInputLayout.addView(mSendButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        
        mContentLayout.addView(mInputLayout, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        
        // 添加欢迎消息
        mAdapter.addTextMessage(MessageBubble.MessageType.SYSTEM, "欢迎使用AI Native OS助理");
        
        mIsInitialized = true;
        Log.i(TAG, "Session view initialized with RecyclerView virtualization (D2.1)");
    }
    
    /**
     * 发送消息
     */
    private void sendMessage() {
        String text = mInputField.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        
        // 添加用户消息到会话流
        mAdapter.addTextMessage(MessageBubble.MessageType.USER, text);
        scrollToBottom();
        
        // 清空输入框
        mInputField.setText("");
        
        // 显示思考指示器
        showThinking(true);
        
        // 回调
        if (mMessageSendListener != null) {
            mMessageSendListener.onMessageSend(text);
        }
        
        Log.d(TAG, "Message sent: " + text);
    }
    
    /**
     * 添加助理消息
     */
    public void addAssistantMessage(String text) {
        mAdapter.addTextMessage(MessageBubble.MessageType.ASSISTANT, text);
        scrollToBottom();
    }
    
    /**
     * 追加文本到最后一条助理消息（流式输出）
     */
    public void appendToAssistantMessage(String text) {
        mAdapter.appendToLastAssistantMessage(text);
        scrollToBottom();
    }
    
    /**
     * 添加系统消息
     */
    public void addSystemMessage(String text) {
        mAdapter.addTextMessage(MessageBubble.MessageType.SYSTEM, text);
        scrollToBottom();
    }
    
    /**
     * 更新任务卡片
     */
    public void updateTaskCard(TaskCardData cardData) {
        mAdapter.updateTaskCard(cardData);
        scrollToBottom();
    }
    
    /**
     * 更新动作卡片
     */
    public void updateActionCard(ActionCardData cardData) {
        mAdapter.updateActionCard(cardData);
        scrollToBottom();
    }
    
    /**
     * 显示/隐藏思考指示器
     */
    public void showThinking(boolean show) {
        mIsThinking = show;
        mThinkingIndicator.setVisibility(show ? VISIBLE : GONE);
    }
    
    /**
     * 设置思考提示文本
     */
    public void setThinkingText(String text) {
        mThinkingText.setText(text);
    }
    
    /**
     * 更新状态文本
     */
    public void setStatus(String status) {
        mStatusText.setText(status);
    }
    
    /**
     * 滚动到底部
     */
    private void scrollToBottom() {
        mConversationList.post(() -> {
            if (mAdapter.getItemCount() > 0) {
                mConversationList.smoothScrollToPosition(mAdapter.getItemCount() - 1);
            }
        });
    }
    
    /**
     * 设置消息发送监听器
     */
    public void setOnMessageSendListener(OnMessageSendListener listener) {
        mMessageSendListener = listener;
    }
    
    /**
     * 清空会话
     */
    public void clearConversation() {
        mAdapter.clear();
        mAdapter.addTextMessage(MessageBubble.MessageType.SYSTEM, "会话已清空");
    }
    
    /**
     * 恢复（前台）
     */
    public void onResume() {
        Log.d(TAG, "Session view resumed");
    }
    
    /**
     * 暂停（后台）
     */
    public void onPause() {
        Log.d(TAG, "Session view paused");
    }
    
    /**
     * 销毁
     */
    public void destroy() {
        removeAllViews();
        mIsInitialized = false;
        Log.i(TAG, "Session view destroyed");
    }
    
    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
