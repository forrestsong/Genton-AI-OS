// AI Native OS - Voice Playback Controller
package com.android.systemui.ainative.session.message;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

/**
 * 语音播放控制器：管理语音消息的播放/暂停/进度（D2.1 增强）
 * 
 * 功能：
 * - MediaPlayer 集成，支持播放/暂停/跳转
 * - 单实例互斥：同一时间仅一个语音播放
 * - 进度回调：定时推送播放进度（200ms 间隔）
 * - 播放完成自动切换状态
 * 
 * 设计原则：
 * - 单例模式：全局共享一个 MediaPlayer 实例
 * - 状态管理：确保播放器状态正确切换（IDLE/PREPARED/PLAYING/PAUSED）
 * - 内存安全：及时释放资源
 * 
 * @since A2 (D2.1 增强)
 */
public class VoicePlaybackController {
    private static final String TAG = "VoicePlaybackController";
    
    private static VoicePlaybackController sInstance;
    
    private final Context mContext;
    private MediaPlayer mMediaPlayer;
    private Handler mProgressHandler;
    
    private String mCurrentAudioPath;
    private OnPlaybackListener mListener;
    private boolean mIsPlaying = false;
    
    /**
     * 播放回调接口
     */
    public interface OnPlaybackListener {
        void onPlaybackStart(String audioPath);
        void onPlaybackPause(String audioPath);
        void onPlaybackProgress(String audioPath, int currentMs, int durationMs);
        void onPlaybackComplete(String audioPath);
        void onPlaybackError(String audioPath, String error);
    }
    
    private VoicePlaybackController(Context context) {
        mContext = context.getApplicationContext();
        mProgressHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized VoicePlaybackController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new VoicePlaybackController(context);
        }
        return sInstance;
    }
    
    /**
     * 播放或暂停语音
     */
    public void playOrPause(String audioPath) {
        if (audioPath == null || audioPath.isEmpty()) {
            Log.w(TAG, "Audio path is null or empty");
            return;
        }
        
        // 如果当前正在播放同一音频，则暂停
        if (mIsPlaying && audioPath.equals(mCurrentAudioPath)) {
            pause();
            return;
        }
        
        // 如果当前正在播放其他音频，先停止
        if (mIsPlaying && !audioPath.equals(mCurrentAudioPath)) {
            stop();
        }
        
        // 开始播放
        play(audioPath);
    }
    
    /**
     * 播放语音
     */
    private void play(String audioPath) {
        try {
            // 释放旧的播放器
            releaseMediaPlayer();
            
            // 创建新的播放器
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDataSource(audioPath);
            
            mMediaPlayer.setOnPreparedListener(mp -> {
                mCurrentAudioPath = audioPath;
                mIsPlaying = true;
                mp.start();
                startProgressTracking();
                
                if (mListener != null) {
                    mListener.onPlaybackStart(audioPath);
                }
                
                Log.d(TAG, "Playback started: " + audioPath);
            });
            
            mMediaPlayer.setOnCompletionListener(mp -> {
                mIsPlaying = false;
                stopProgressTracking();
                
                if (mListener != null) {
                    mListener.onPlaybackComplete(mCurrentAudioPath);
                }
                
                Log.d(TAG, "Playback completed: " + mCurrentAudioPath);
                mCurrentAudioPath = null;
            });
            
            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                String error = "MediaPlayer error: what=" + what + ", extra=" + extra;
                Log.e(TAG, error);
                
                if (mListener != null) {
                    mListener.onPlaybackError(audioPath, error);
                }
                
                mIsPlaying = false;
                stopProgressTracking();
                return true;
            });
            
            mMediaPlayer.prepareAsync();
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to play audio: " + audioPath, e);
            if (mListener != null) {
                mListener.onPlaybackError(audioPath, e.getMessage());
            }
        }
    }
    
    /**
     * 暂停播放
     */
    private void pause() {
        if (mMediaPlayer != null && mIsPlaying) {
            mMediaPlayer.pause();
            mIsPlaying = false;
            stopProgressTracking();
            
            if (mListener != null) {
                mListener.onPlaybackPause(mCurrentAudioPath);
            }
            
            Log.d(TAG, "Playback paused: " + mCurrentAudioPath);
        }
    }
    
    /**
     * 恢复播放（从暂停状态）
     */
    public void resume(String audioPath) {
        if (mMediaPlayer != null && audioPath.equals(mCurrentAudioPath) && !mIsPlaying) {
            mMediaPlayer.start();
            mIsPlaying = true;
            startProgressTracking();
            
            if (mListener != null) {
                mListener.onPlaybackStart(audioPath);
            }
            
            Log.d(TAG, "Playback resumed: " + audioPath);
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        if (mMediaPlayer != null) {
            mIsPlaying = false;
            stopProgressTracking();
            releaseMediaPlayer();
            mCurrentAudioPath = null;
            Log.d(TAG, "Playback stopped");
        }
    }
    
    /**
     * 跳转到指定位置
     */
    public void seekTo(String audioPath, int positionMs) {
        if (mMediaPlayer != null && audioPath.equals(mCurrentAudioPath)) {
            mMediaPlayer.seekTo(positionMs);
            Log.d(TAG, "Seek to: " + positionMs + "ms");
        }
    }
    
    /**
     * 获取当前播放位置
     */
    public int getCurrentPosition(String audioPath) {
        if (mMediaPlayer != null && audioPath.equals(mCurrentAudioPath)) {
            try {
                return mMediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Failed to get current position", e);
            }
        }
        return 0;
    }
    
    /**
     * 获取音频总时长
     */
    public int getDuration(String audioPath) {
        if (mMediaPlayer != null && audioPath.equals(mCurrentAudioPath)) {
            try {
                return mMediaPlayer.getDuration();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Failed to get duration", e);
            }
        }
        return 0;
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying(String audioPath) {
        return mIsPlaying && audioPath.equals(mCurrentAudioPath);
    }
    
    /**
     * 设置播放回调监听器
     */
    public void setOnPlaybackListener(OnPlaybackListener listener) {
        mListener = listener;
    }
    
    /**
     * 开始进度跟踪
     */
    private void startProgressTracking() {
        stopProgressTracking();
        mProgressHandler.post(mProgressRunnable);
    }
    
    /**
     * 停止进度跟踪
     */
    private void stopProgressTracking() {
        mProgressHandler.removeCallbacks(mProgressRunnable);
    }
    
    /**
     * 进度跟踪 Runnable（200ms 间隔）
     */
    private final Runnable mProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mMediaPlayer != null && mIsPlaying) {
                try {
                    int currentMs = mMediaPlayer.getCurrentPosition();
                    int durationMs = mMediaPlayer.getDuration();
                    
                    if (mListener != null) {
                        mListener.onPlaybackProgress(mCurrentAudioPath, currentMs, durationMs);
                    }
                    
                    mProgressHandler.postDelayed(this, 200);
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Progress tracking error", e);
                }
            }
        }
    };
    
    /**
     * 释放 MediaPlayer 资源
     */
    private void releaseMediaPlayer() {
        if (mMediaPlayer != null) {
            try {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                }
                mMediaPlayer.reset();
                mMediaPlayer.release();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Error releasing MediaPlayer", e);
            }
            mMediaPlayer = null;
        }
    }
    
    /**
     * 销毁控制器（释放所有资源）
     */
    public void destroy() {
        stop();
        releaseMediaPlayer();
        mProgressHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "VoicePlaybackController destroyed");
    }
}
