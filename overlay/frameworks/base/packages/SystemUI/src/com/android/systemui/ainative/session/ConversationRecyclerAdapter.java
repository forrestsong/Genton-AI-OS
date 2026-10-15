// AI Native OS - Conversation RecyclerView Adapter (D2.1 虚拟化长会话)
package com.android.systemui.ainative.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.android.systemui.ainative.session.message.MessageBubble;
import com.android.systemui.ainative.session.message.VoiceMessageBubble;
import com.android.systemui.ainative.session.message.AttachmentMessageBubble;
import com.android.systemui.ainative.session.message.MessageContextMenu;
import com.android.systemui.ainative.session.card.TaskCardView;
import com.android.systemui.ainative.session.card.ActionCardView;

import com.ainative.agent.TaskCardData;
import com.ainative.agent.ActionCardData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 会话流适配器（RecyclerView + DiffUtil）：虚拟化长会话（D2.1）
 * 
 * 相比 ListView + BaseAdapter 的优势：
 * - View 复用（ViewHolder 模式），滚动性能高
 * - DiffUtil 增量更新，仅刷新变化项（避免 notifyDataSetChanged 全量刷新）
 * - 支持长会话虚拟化（仅渲染可见项）
 * 
 * 支持项目类型（对齐 D2.1）：
 * - 文字消息气泡（用户/助理/系统）
 * - 语音消息气泡
 * - 附件消息气泡
 * - 任务卡片
 * - 动作卡片（type 0-4）
 * 
 * @since A2 (D2.1)
 */
public class ConversationRecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String TAG = "ConversationRecyclerAdapter";
    
    // 视图类型
    public static final int VIEW_TYPE_TEXT_MESSAGE = 0;
    public static final int VIEW_TYPE_VOICE_MESSAGE = 1;
    public static final int VIEW_TYPE_ATTACHMENT_MESSAGE = 2;
    public static final int VIEW_TYPE_TASK_CARD = 3;
    public static final int VIEW_TYPE_ACTION_CARD = 4;
    
    private final Context mContext;
    private final List<ConversationItem> mItems;
    
    // 异步 Diff 计算（后台线程）+ 主线程刷新分发
    private final Executor mDiffExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    
    private MessageContextMenu mContextMenu;
    private ActionCardView.OnActionListener mActionCardListener;
    private OnMessageActionListener mMessageActionListener;
    
    public interface OnMessageActionListener {
        void onMessageDeleted(String messageId);
        void onMessageQuoted(String messageId, String text);
    }
    
    public ConversationRecyclerAdapter(Context context) {
        mContext = context;
        mItems = new ArrayList<>();
        mContextMenu = new MessageContextMenu(context);
        
        // 设置上下文菜单监听器
        mContextMenu.setOnMenuActionListener(new MessageContextMenu.OnMenuActionListener() {
            @Override
            public void onCopy(String messageId, String text) {
                Log.d(TAG, "Message copied: " + messageId);
            }
            
            @Override
            public void onDelete(String messageId) {
                removeMessageById(messageId);
                if (mMessageActionListener != null) {
                    mMessageActionListener.onMessageDeleted(messageId);
                }
            }
            
            @Override
            public void onQuote(String messageId, String text) {
                if (mMessageActionListener != null) {
                    mMessageActionListener.onMessageQuoted(messageId, text);
                }
            }
        });
    }
    
    // ==================== 数据操作（DiffUtil 驱动） ====================
    
    /**
     * 添加文字消息
     */
    public void addTextMessage(MessageBubble.MessageType type, String text) {
        ConversationItem item = new ConversationItem();
        item.viewType = VIEW_TYPE_TEXT_MESSAGE;
        item.stableId = generateId();
        item.messageId = "msg_" + item.stableId;
        item.messageType = type;
        item.text = text;
        appendItem(item);
    }
    
    /**
     * 添加语音消息
     */
    public void addVoiceMessage(VoiceMessageBubble.MessageType type, String audioPath, 
                                 int durationMs, String transcript) {
        ConversationItem item = new ConversationItem();
        item.viewType = VIEW_TYPE_VOICE_MESSAGE;
        item.stableId = generateId();
        item.voiceType = type;
        item.audioPath = audioPath;
        item.durationMs = durationMs;
        item.transcript = transcript;
        appendItem(item);
    }
    
    /**
     * 添加附件消息
     */
    public void addAttachmentMessage(AttachmentMessageBubble.MessageType type, String filePath,
                                      String fileName, long fileSize, 
                                      AttachmentMessageBubble.AttachmentType attachmentType) {
        ConversationItem item = new ConversationItem();
        item.viewType = VIEW_TYPE_ATTACHMENT_MESSAGE;
        item.stableId = generateId();
        item.attachmentMessageType = type;
        item.filePath = filePath;
        item.fileName = fileName;
        item.fileSize = fileSize;
        item.attachmentType = attachmentType;
        appendItem(item);
    }
    
    /**
     * 追加文本到最后一条助理消息（流式输出）
     */
    public void appendToLastAssistantMessage(String text) {
        for (int i = mItems.size() - 1; i >= 0; i--) {
            ConversationItem item = mItems.get(i);
            if (item.viewType == VIEW_TYPE_TEXT_MESSAGE &&
                item.messageType == MessageBubble.MessageType.ASSISTANT) {
                item.text += text;
                notifyItemChanged(i);
                return;
            }
        }
        // 没有找到助理消息，创建新的
        addTextMessage(MessageBubble.MessageType.ASSISTANT, text);
    }
    
    /**
     * 添加或更新任务卡片
     */
    public void updateTaskCard(TaskCardData cardData) {
        for (int i = 0; i < mItems.size(); i++) {
            ConversationItem item = mItems.get(i);
            if (item.viewType == VIEW_TYPE_TASK_CARD &&
                cardData.taskId.equals(item.taskCardData.taskId)) {
                item.taskCardData = cardData;
                notifyItemChanged(i);
                return;
            }
        }
        // 不存在，添加新卡片
        ConversationItem item = new ConversationItem();
        item.viewType = VIEW_TYPE_TASK_CARD;
        item.stableId = generateId();
        item.taskCardData = cardData;
        appendItem(item);
    }
    
    /**
     * 添加或更新动作卡片（同 cardId 推送即状态更新）
     */
    public void updateActionCard(ActionCardData cardData) {
        for (int i = 0; i < mItems.size(); i++) {
            ConversationItem item = mItems.get(i);
            if (item.viewType == VIEW_TYPE_ACTION_CARD &&
                cardData.cardId.equals(item.actionCardData.cardId)) {
                item.actionCardData = cardData;
                notifyItemChanged(i);
                return;
            }
        }
        // 不存在，添加新卡片
        ConversationItem item = new ConversationItem();
        item.viewType = VIEW_TYPE_ACTION_CARD;
        item.stableId = generateId();
        item.actionCardData = cardData;
        appendItem(item);
    }
    
    /**
     * 批量替换会话（DiffUtil 差异计算在后台线程执行，主线程增量刷新）
     * 
     * 相比同步 DiffUtil.calculateDiff，长会话（1000+ 条）差异计算不阻塞主线程，
     * 避免大列表刷新时的 UI 卡顿。
     */
    public void submitList(List<ConversationItem> newItems) {
        final List<ConversationItem> oldItems = new ArrayList<>(mItems);
        final List<ConversationItem> finalNewItems = new ArrayList<>(newItems);
        
        mDiffExecutor.execute(() -> {
            final DiffUtil.DiffResult result = DiffUtil.calculateDiff(
                new ConversationDiffCallback(oldItems, finalNewItems));
            // 差异计算完成后回到主线程分发刷新
            mMainHandler.post(() -> {
                mItems.clear();
                mItems.addAll(finalNewItems);
                result.dispatchUpdatesTo(this);
            });
        });
    }
    
    /**
     * 清空会话
     */
    public void clear() {
        int size = mItems.size();
        mItems.clear();
        notifyItemRangeRemoved(0, size);
    }
    
    /**
     * 追加单项并通知插入
     */
    private void appendItem(ConversationItem item) {
        mItems.add(item);
        notifyItemInserted(mItems.size() - 1);
    }
    
    /**
     * 按消息 ID 删除消息项
     */
    public void removeMessageById(String messageId) {
        if (messageId == null) return;
        for (int i = 0; i < mItems.size(); i++) {
            if (messageId.equals(mItems.get(i).messageId)) {
                mItems.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }
    
    private long generateId() {
        return System.nanoTime();
    }
    
    // ==================== RecyclerView.Adapter 实现 ====================
    
    @Override
    public int getItemViewType(int position) {
        return mItems.get(position).viewType;
    }
    
    @Override
    public long getItemId(int position) {
        return mItems.get(position).stableId;
    }
    
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        switch (viewType) {
            case VIEW_TYPE_TEXT_MESSAGE:
                return new TextMessageHolder(new MessageBubble(mContext));
            case VIEW_TYPE_VOICE_MESSAGE:
                return new VoiceMessageHolder(new VoiceMessageBubble(mContext));
            case VIEW_TYPE_ATTACHMENT_MESSAGE:
                return new AttachmentMessageHolder(new AttachmentMessageBubble(mContext));
            case VIEW_TYPE_TASK_CARD:
                return new TaskCardHolder(new TaskCardView(mContext));
            case VIEW_TYPE_ACTION_CARD:
                ActionCardView cardView = new ActionCardView(mContext);
                if (mActionCardListener != null) {
                    cardView.setOnActionListener(mActionCardListener);
                }
                return new ActionCardHolder(cardView);
            default:
                throw new IllegalArgumentException("Unknown view type: " + viewType);
        }
    }
    
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ConversationItem item = mItems.get(position);
        
        switch (item.viewType) {
            case VIEW_TYPE_TEXT_MESSAGE: {
                MessageBubble bubble = ((TextMessageHolder) holder).bubble;
                bubble.setMessageType(item.messageType);
                bubble.setText(item.text);
                // 长按弹出操作菜单（复制/删除/引用）
                final String messageId = item.messageId;
                bubble.setOnBubbleLongClickListener((view, text) ->
                    mContextMenu.show(view, messageId, text));
                break;
            }
            case VIEW_TYPE_VOICE_MESSAGE: {
                VoiceMessageBubble bubble = ((VoiceMessageHolder) holder).bubble;
                bubble.setMessageType(item.voiceType);
                bubble.setVoiceData(item.audioPath, item.durationMs, item.transcript);
                break;
            }
            case VIEW_TYPE_ATTACHMENT_MESSAGE: {
                AttachmentMessageBubble bubble = ((AttachmentMessageHolder) holder).bubble;
                bubble.setMessageType(item.attachmentMessageType);
                bubble.setAttachmentData(item.filePath, item.fileName, item.fileSize, item.attachmentType);
                break;
            }
            case VIEW_TYPE_TASK_CARD:
                ((TaskCardHolder) holder).cardView.bind(item.taskCardData);
                break;
            case VIEW_TYPE_ACTION_CARD:
                ((ActionCardHolder) holder).cardView.bind(item.actionCardData);
                break;
        }
    }
    
    @Override
    public int getItemCount() {
        return mItems.size();
    }
    
    /**
     * 设置动作卡片监听器
     */
    public void setActionCardListener(ActionCardView.OnActionListener listener) {
        mActionCardListener = listener;
    }
    
    /**
     * 设置消息操作监听器
     */
    public void setMessageActionListener(OnMessageActionListener listener) {
        mMessageActionListener = listener;
    }
    
    // ==================== ViewHolder ====================
    
    static class TextMessageHolder extends RecyclerView.ViewHolder {
        final MessageBubble bubble;
        TextMessageHolder(MessageBubble view) {
            super(view);
            bubble = view;
        }
    }
    
    static class VoiceMessageHolder extends RecyclerView.ViewHolder {
        final VoiceMessageBubble bubble;
        VoiceMessageHolder(VoiceMessageBubble view) {
            super(view);
            bubble = view;
        }
    }
    
    static class AttachmentMessageHolder extends RecyclerView.ViewHolder {
        final AttachmentMessageBubble bubble;
        AttachmentMessageHolder(AttachmentMessageBubble view) {
            super(view);
            bubble = view;
        }
    }
    
    static class TaskCardHolder extends RecyclerView.ViewHolder {
        final TaskCardView cardView;
        TaskCardHolder(TaskCardView view) {
            super(view);
            cardView = view;
        }
    }
    
    static class ActionCardHolder extends RecyclerView.ViewHolder {
        final ActionCardView cardView;
        ActionCardHolder(ActionCardView view) {
            super(view);
            cardView = view;
        }
    }
    
    // ==================== 会话项数据类 ====================
    
    /**
     * 会话项数据类
     */
    public static class ConversationItem {
        int viewType;
        long stableId;
        String messageId;  // 消息唯一 ID（用于删除/引用）
        
        // 文字消息
        MessageBubble.MessageType messageType;
        String text;
        
        // 语音消息
        VoiceMessageBubble.MessageType voiceType;
        String audioPath;
        int durationMs;
        String transcript;
        
        // 附件消息
        AttachmentMessageBubble.MessageType attachmentMessageType;
        String filePath;
        String fileName;
        long fileSize;
        AttachmentMessageBubble.AttachmentType attachmentType;
        
        // 任务卡
        TaskCardData taskCardData;
        
        // 动作卡
        ActionCardData actionCardData;
    }
    
    // ==================== DiffUtil Callback ====================
    
    /**
     * DiffUtil 差异计算回调：基于 stableId 判断同一项，基于内容判断是否需刷新
     */
    static class ConversationDiffCallback extends DiffUtil.Callback {
        private final List<ConversationItem> oldList;
        private final List<ConversationItem> newList;
        
        ConversationDiffCallback(List<ConversationItem> oldList, List<ConversationItem> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }
        
        @Override
        public int getOldListSize() {
            return oldList.size();
        }
        
        @Override
        public int getNewListSize() {
            return newList.size();
        }
        
        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).stableId == newList.get(newItemPosition).stableId;
        }
        
        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            ConversationItem oldItem = oldList.get(oldItemPosition);
            ConversationItem newItem = newList.get(newItemPosition);
            
            if (oldItem.viewType != newItem.viewType) {
                return false;
            }
            
            switch (oldItem.viewType) {
                case VIEW_TYPE_TEXT_MESSAGE:
                    return oldItem.messageType == newItem.messageType &&
                           equalsStr(oldItem.text, newItem.text);
                case VIEW_TYPE_VOICE_MESSAGE:
                    return oldItem.voiceType == newItem.voiceType &&
                           equalsStr(oldItem.audioPath, newItem.audioPath) &&
                           oldItem.durationMs == newItem.durationMs;
                case VIEW_TYPE_ATTACHMENT_MESSAGE:
                    return equalsStr(oldItem.filePath, newItem.filePath) &&
                           oldItem.fileSize == newItem.fileSize;
                case VIEW_TYPE_TASK_CARD:
                    return oldItem.taskCardData.state == newItem.taskCardData.state &&
                           oldItem.taskCardData.progress == newItem.taskCardData.progress;
                case VIEW_TYPE_ACTION_CARD:
                    return oldItem.actionCardData.type == newItem.actionCardData.type &&
                           equalsStr(oldItem.actionCardData.summaryMasked, 
                                     newItem.actionCardData.summaryMasked);
                default:
                    return false;
            }
        }
        
        private boolean equalsStr(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }
}
