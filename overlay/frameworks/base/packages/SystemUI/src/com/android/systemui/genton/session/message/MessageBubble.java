// AI Native OS - Message Bubble
package com.android.systemui.genton.session.message;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 消息气泡：会话流UI的基础组件
 * 
 * 支持：
 * - 用户消息（右侧，蓝色）
 * - 助理消息（左侧，灰色）
 * - 系统消息（居中，浅灰色）
 */
public class MessageBubble extends LinearLayout {
    private static final String TAG = "MessageBubble";
    
    // 消息类型
    public enum MessageType {
        USER,       // 用户消息
        ASSISTANT,  // 助理消息
        SYSTEM      // 系统消息
    }
    
    private TextView mTextView;
    private MessageType mType;
    private OnBubbleLongClickListener mLongClickListener;
    
    public interface OnBubbleLongClickListener {
        void onBubbleLongClick(MessageBubble bubble, String text);
    }
    
    public MessageBubble(Context context) {
        super(context);
        init();
    }
    
    public MessageBubble(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        setOrientation(VERTICAL);
        setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        
        // 创建文本视图
        mTextView = new TextView(getContext());
        mTextView.setTextSize(16);
        mTextView.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        
        // 长按弹出操作菜单
        mTextView.setOnLongClickListener(v -> {
            if (mLongClickListener != null) {
                mLongClickListener.onBubbleLongClick(this, getText());
                return true;
            }
            return false;
        });
        
        addView(mTextView, new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ));
        
        // 默认为助理消息
        setMessageType(MessageType.ASSISTANT);
    }
    
    /**
     * 设置长按监听器
     */
    public void setOnBubbleLongClickListener(OnBubbleLongClickListener listener) {
        mLongClickListener = listener;
    }
    
    /**
     * 设置消息类型
     */
    public void setMessageType(MessageType type) {
        mType = type;
        
        // 创建圆角背景
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dpToPx(12));
        
        LayoutParams layoutParams = (LayoutParams) getLayoutParams();
        if (layoutParams == null) {
            layoutParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            );
        }
        
        switch (type) {
            case USER:
                // 用户消息：蓝色，右对齐
                background.setColor(Color.parseColor("#2196F3"));
                mTextView.setTextColor(Color.WHITE);
                layoutParams.gravity = Gravity.END;
                break;
                
            case ASSISTANT:
                // 助理消息：灰色，左对齐
                background.setColor(Color.parseColor("#EEEEEE"));
                mTextView.setTextColor(Color.parseColor("#212121"));
                layoutParams.gravity = Gravity.START;
                break;
                
            case SYSTEM:
                // 系统消息：浅灰色，居中
                background.setColor(Color.parseColor("#F5F5F5"));
                mTextView.setTextColor(Color.parseColor("#757575"));
                layoutParams.gravity = Gravity.CENTER_HORIZONTAL;
                mTextView.setTextSize(14);
                break;
        }
        
        mTextView.setBackground(background);
        setLayoutParams(layoutParams);
    }
    
    /**
     * 设置消息文本
     */
    public void setText(String text) {
        mTextView.setText(text);
    }
    
    /**
     * 追加文本（流式输出）
     */
    public void appendText(String text) {
        mTextView.append(text);
    }
    
    /**
     * 获取消息文本
     */
    public String getText() {
        return mTextView.getText().toString();
    }
    
    /**
     * 获取消息类型
     */
    public MessageType getMessageType() {
        return mType;
    }
    
    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
