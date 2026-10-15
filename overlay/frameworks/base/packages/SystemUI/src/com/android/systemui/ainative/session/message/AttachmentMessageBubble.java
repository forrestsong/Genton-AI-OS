// AI Native OS - Attachment Message Bubble
package com.android.systemui.ainative.session.message;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 附件消息气泡：支持文件、图片、链接等附件类型（D2.1）
 * 
 * 显示内容：
 * - 附件图标（文件类型图标）
 * - 附件名称
 * - 文件大小
 * - 点击预览/下载
 * 
 * 设计原则：
 * - 用户附件：右侧蓝色气泡
 * - 助理附件：左侧灰色气泡
 * - 可点击预览（图片/视频）
 * - 可点击下载（文件）
 * 
 * @since A2 (D2.1)
 */
public class AttachmentMessageBubble extends LinearLayout {
    private static final String TAG = "AttachmentMessageBubble";
    
    public enum MessageType {
        USER,       // 用户附件
        ASSISTANT   // 助理附件
    }
    
    public enum AttachmentType {
        IMAGE,      // 图片
        VIDEO,      // 视频
        AUDIO,      // 音频
        DOCUMENT,   // 文档
        LINK,       // 链接
        OTHER       // 其他
    }
    
    private ImageView mTypeIcon;
    private TextView mNameText;
    private TextView mSizeText;
    private ImageView mThumbnailView;
    
    private MessageType mMessageType;
    private AttachmentType mAttachmentType;
    private String mFilePath;
    private String mFileName;
    private long mFileSize;
    
    private AttachmentPreviewHelper mPreviewHelper;
    private OnAttachmentActionListener mActionListener;
    
    public interface OnAttachmentActionListener {
        void onAttachmentClick(String filePath, AttachmentType type);
        void onAttachmentLongClick(String filePath);
    }
    
    public AttachmentMessageBubble(Context context) {
        super(context);
        init();
    }
    
    public AttachmentMessageBubble(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        setOrientation(VERTICAL);
        setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        
        mPreviewHelper = new AttachmentPreviewHelper(getContext());
        
        // 附件容器
        LinearLayout attachmentContainer = new LinearLayout(getContext());
        attachmentContainer.setOrientation(HORIZONTAL);
        attachmentContainer.setGravity(Gravity.CENTER_VERTICAL);
        attachmentContainer.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        
        // 类型图标
        mTypeIcon = new ImageView(getContext());
        mTypeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LayoutParams iconParams = new LayoutParams(dpToPx(40), dpToPx(40));
        iconParams.rightMargin = dpToPx(12);
        attachmentContainer.addView(mTypeIcon, iconParams);
        
        // 文本容器
        LinearLayout textContainer = new LinearLayout(getContext());
        textContainer.setOrientation(VERTICAL);
        
        // 文件名
        mNameText = new TextView(getContext());
        mNameText.setTextSize(14);
        mNameText.setMaxLines(2);
        textContainer.addView(mNameText, new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ));
        
        // 文件大小
        mSizeText = new TextView(getContext());
        mSizeText.setTextSize(12);
        LayoutParams sizeParams = new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        );
        sizeParams.topMargin = dpToPx(4);
        textContainer.addView(mSizeText, sizeParams);
        
        attachmentContainer.addView(textContainer, new LayoutParams(
            0,
            LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        
        addView(attachmentContainer, new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ));
        
        // 缩略图（仅图片/视频显示）
        mThumbnailView = new ImageView(getContext());
        mThumbnailView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mThumbnailView.setVisibility(GONE);
        LayoutParams thumbnailParams = new LayoutParams(
            dpToPx(160),
            dpToPx(120)
        );
        thumbnailParams.topMargin = dpToPx(8);
        addView(mThumbnailView, thumbnailParams);
        
        // 点击事件（预览/打开附件）
        attachmentContainer.setOnClickListener(v -> {
            // 通过预览助手实际打开附件
            mPreviewHelper.preview(mFilePath, mAttachmentType);
            if (mActionListener != null) {
                mActionListener.onAttachmentClick(mFilePath, mAttachmentType);
            }
        });
        
        attachmentContainer.setOnLongClickListener(v -> {
            if (mActionListener != null) {
                mActionListener.onAttachmentLongClick(mFilePath);
            }
            return true;
        });
        
        // 默认为助理附件
        setMessageType(MessageType.ASSISTANT);
    }
    
    /**
     * 设置消息类型
     */
    public void setMessageType(MessageType type) {
        mMessageType = type;
        
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
                // 用户附件：蓝色，右对齐
                background.setColor(Color.parseColor("#2196F3"));
                mNameText.setTextColor(Color.WHITE);
                mSizeText.setTextColor(Color.parseColor("#E3F2FD"));
                layoutParams.gravity = Gravity.END;
                break;
                
            case ASSISTANT:
                // 助理附件：灰色，左对齐
                background.setColor(Color.parseColor("#EEEEEE"));
                mNameText.setTextColor(Color.parseColor("#212121"));
                mSizeText.setTextColor(Color.parseColor("#757575"));
                layoutParams.gravity = Gravity.START;
                break;
        }
        
        setBackground(background);
        setLayoutParams(layoutParams);
    }
    
    /**
     * 设置附件数据
     */
    public void setAttachmentData(String filePath, String fileName, long fileSize, AttachmentType type) {
        mFilePath = filePath;
        mFileName = fileName;
        mFileSize = fileSize;
        mAttachmentType = type;
        
        // 设置文件名
        mNameText.setText(fileName);
        
        // 设置文件大小
        mSizeText.setText(formatFileSize(fileSize));
        
        // 设置类型图标
        setTypeIcon(type);
        
        // 显示缩略图（仅图片/视频）
        if (type == AttachmentType.IMAGE || type == AttachmentType.VIDEO) {
            mThumbnailView.setVisibility(VISIBLE);
            // 优先从 LRU 缓存读取缩略图，命中则直接复用（避免重复解码）
            android.graphics.Bitmap cached = ImageLruCache.getInstance().get(filePath);
            if (cached != null) {
                mThumbnailView.setImageBitmap(cached);
            }
            // 未命中时由外部通过 setThumbnail 异步加载并写入缓存
        } else {
            mThumbnailView.setVisibility(GONE);
        }
    }
    
    /**
     * 设置类型图标
     */
    private void setTypeIcon(AttachmentType type) {
        switch (type) {
            case IMAGE:
                mTypeIcon.setImageResource(android.R.drawable.ic_menu_gallery);
                break;
            case VIDEO:
                mTypeIcon.setImageResource(android.R.drawable.ic_media_play);
                break;
            case AUDIO:
                mTypeIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
                break;
            case DOCUMENT:
                mTypeIcon.setImageResource(android.R.drawable.ic_menu_agenda);
                break;
            case LINK:
                mTypeIcon.setImageResource(android.R.drawable.ic_menu_share);
                break;
            case OTHER:
                mTypeIcon.setImageResource(android.R.drawable.ic_menu_info_details);
                break;
        }
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
    
    /**
     * 设置缩略图（Bitmap），同时写入 LRU 缓存
     */
    public void setThumbnail(android.graphics.Bitmap bitmap) {
        if (bitmap != null) {
            mThumbnailView.setImageBitmap(bitmap);
            mThumbnailView.setVisibility(VISIBLE);
            // 写入 LRU 缓存供后续复用
            if (mFilePath != null) {
                ImageLruCache.getInstance().put(mFilePath, bitmap);
            }
        }
    }
    
    /**
     * 设置动作监听器
     */
    public void setOnAttachmentActionListener(OnAttachmentActionListener listener) {
        mActionListener = listener;
    }
    
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
