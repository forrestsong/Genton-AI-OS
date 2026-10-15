// AI Native OS - Voice Message Bubble
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
 * 语音消息气泡：支持语音输入和语音回复（D2.1）
 * 
 * 显示内容：
 * - 语音图标（播放/暂停）
 * - 语音时长
 * - 转录文本（可选）
 * - 播放进度条
 * 
 * 设计原则：
 * - 用户语音：右侧蓝色气泡
 * - 助理语音：左侧灰色气泡
 * - 可播放/暂停
 * - 转录文本可折叠显示
 * 
 * @since A2 (D2.1)
 */
public class VoiceMessageBubble extends LinearLayout {
    private static final String TAG = "VoiceMessageBubble";
    
    public enum MessageType {
        USER,       // 用户语音
        ASSISTANT   // 助理语音
    }
    
    private ImageView mVoiceIcon;
    private TextView mDurationText;
    private TextView mTranscriptText;
    private LinearLayout mProgressBar;
    
    private MessageType mType;
    private String mAudioPath;
    private int mDurationMs;
    private String mTranscript;
    private boolean mIsPlaying = false;
    
    private VoicePlaybackController mPlaybackController;
    private OnVoiceActionListener mActionListener;
    
    public interface OnVoiceActionListener {
        void onPlayPause(String audioPath, boolean shouldPlay);
        void onSeek(String audioPath, int positionMs);
    }
    
    public VoiceMessageBubble(Context context) {
        super(context);
        init();
    }
    
    public VoiceMessageBubble(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        setOrientation(VERTICAL);
        setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        
        mPlaybackController = VoicePlaybackController.getInstance(getContext());
        
        // 语音容器
        LinearLayout voiceContainer = new LinearLayout(getContext());
        voiceContainer.setOrientation(HORIZONTAL);
        voiceContainer.setGravity(Gravity.CENTER_VERTICAL);
        voiceContainer.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        
        // 语音图标
        mVoiceIcon = new ImageView(getContext());
        mVoiceIcon.setImageResource(android.R.drawable.ic_media_play);
        mVoiceIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LayoutParams iconParams = new LayoutParams(dpToPx(32), dpToPx(32));
        iconParams.rightMargin = dpToPx(8);
        voiceContainer.addView(mVoiceIcon, iconParams);
        
        // 时长文本
        mDurationText = new TextView(getContext());
        mDurationText.setText("0:00");
        mDurationText.setTextSize(14);
        voiceContainer.addView(mDurationText, new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ));
        
        // 进度条占位
        mProgressBar = new LinearLayout(getContext());
        mProgressBar.setOrientation(HORIZONTAL);
        mProgressBar.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LayoutParams progressParams = new LayoutParams(
            0,
            dpToPx(4)
        );
        progressParams.weight = 1;
        progressParams.leftMargin = dpToPx(12);
        progressParams.gravity = Gravity.CENTER_VERTICAL;
        voiceContainer.addView(mProgressBar, progressParams);
        
        addView(voiceContainer, new LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ));
        
        // 转录文本（可折叠）
        mTranscriptText = new TextView(getContext());
        mTranscriptText.setTextSize(12);
        mTranscriptText.setVisibility(GONE);
        LayoutParams transcriptParams = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        );
        transcriptParams.topMargin = dpToPx(4);
        addView(mTranscriptText, transcriptParams);
        
        // 点击播放/暂停
        voiceContainer.setOnClickListener(v -> togglePlayPause());
        
        // 默认为助理语音
        setMessageType(MessageType.ASSISTANT);
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
                // 用户语音：蓝色，右对齐
                background.setColor(Color.parseColor("#2196F3"));
                mDurationText.setTextColor(Color.WHITE);
                mTranscriptText.setTextColor(Color.parseColor("#E3F2FD"));
                layoutParams.gravity = Gravity.END;
                break;
                
            case ASSISTANT:
                // 助理语音：灰色，左对齐
                background.setColor(Color.parseColor("#EEEEEE"));
                mDurationText.setTextColor(Color.parseColor("#212121"));
                mTranscriptText.setTextColor(Color.parseColor("#757575"));
                layoutParams.gravity = Gravity.START;
                break;
        }
        
        setBackground(background);
        setLayoutParams(layoutParams);
    }
    
    /**
     * 设置语音数据
     */
    public void setVoiceData(String audioPath, int durationMs, String transcript) {
        mAudioPath = audioPath;
        mDurationMs = durationMs;
        mTranscript = transcript;
        
        // 显示时长
        int seconds = durationMs / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        mDurationText.setText(String.format("%d:%02d", minutes, seconds));
        
        // 显示转录文本
        if (transcript != null && !transcript.isEmpty()) {
            mTranscriptText.setText("\"" + transcript + "\"");
            mTranscriptText.setVisibility(VISIBLE);
        } else {
            mTranscriptText.setVisibility(GONE);
        }
        
        // 设置播放回调监听器
        mPlaybackController.setOnPlaybackListener(new VoicePlaybackController.OnPlaybackListener() {
            @Override
            public void onPlaybackStart(String path) {
                if (path.equals(mAudioPath)) {
                    mIsPlaying = true;
                    mVoiceIcon.setImageResource(android.R.drawable.ic_media_pause);
                }
            }
            
            @Override
            public void onPlaybackPause(String path) {
                if (path.equals(mAudioPath)) {
                    mIsPlaying = false;
                    mVoiceIcon.setImageResource(android.R.drawable.ic_media_play);
                }
            }
            
            @Override
            public void onPlaybackProgress(String path, int currentMs, int durationMs) {
                if (path.equals(mAudioPath)) {
                    updateProgress(currentMs);
                }
            }
            
            @Override
            public void onPlaybackComplete(String path) {
                if (path.equals(mAudioPath)) {
                    mIsPlaying = false;
                    mVoiceIcon.setImageResource(android.R.drawable.ic_media_play);
                    updateProgress(0);
                }
            }
            
            @Override
            public void onPlaybackError(String path, String error) {
                if (path.equals(mAudioPath)) {
                    mIsPlaying = false;
                    mVoiceIcon.setImageResource(android.R.drawable.ic_media_play);
                }
            }
        });
    }
    
    /**
     * 切换播放/暂停（驱动 MediaPlayer）
     */
    private void togglePlayPause() {
        // 通过播放控制器实际播放/暂停（单实例互斥）
        mPlaybackController.playOrPause(mAudioPath);
        
        if (mActionListener != null) {
            mActionListener.onPlayPause(mAudioPath, mPlaybackController.isPlaying(mAudioPath));
        }
    }
    
    /**
     * 更新播放进度
     */
    public void updateProgress(int currentMs) {
        if (mDurationMs <= 0) return;
        
        float progress = (float) currentMs / mDurationMs;
        
        // 更新进度条背景（视觉填充效果）
        GradientDrawable progressDrawable = new GradientDrawable();
        progressDrawable.setColor(mType == MessageType.USER ? 
            Color.parseColor("#1976D2") : Color.parseColor("#9E9E9E"));
        progressDrawable.setCornerRadius(dpToPx(2));
        
        // 设置进度条宽度（通过 scaleX 实现填充效果）
        mProgressBar.setScaleX(progress);
        mProgressBar.setPivotX(0);  // 从左侧开始缩放
        mProgressBar.setBackground(progressDrawable);
    }
    
    /**
     * 设置播放状态
     */
    public void setPlaying(boolean playing) {
        mIsPlaying = playing;
        mVoiceIcon.setImageResource(playing ? 
            android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }
    
    /**
     * 设置动作监听器
     */
    public void setOnVoiceActionListener(OnVoiceActionListener listener) {
        mActionListener = listener;
    }
    
    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
