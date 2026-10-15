package com.android.server.ainative.executors;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Slog;

import com.ainative.action.ActionRequest;

import org.json.JSONObject;

import java.util.List;

/**
 * 媒体执行器
 * 
 * 核心职责：
 * 1. 播放/暂停/停止
 * 2. 上一曲/下一曲
 * 3. 快进/快退
 * 4. 音量调节
 * 5. 查询当前播放状态
 * 
 * 安全约束：
 * - 仅控制活跃媒体会话
 * - 不可控制系统音频（通话/闹钟）
 * - 音量调节有上下限保护
 * 
 * 性能要求：
 * - 控制延迟 P50 < 20ms
 * - 状态查询 P50 < 10ms
 * 
 * @see MediaSessionManager
 * @see MediaController
 */
public class MediaExecutor implements ActionExecutor {
    private static final String TAG = "MediaExecutor";
    
    private final Context mContext;
    private final MediaSessionManager mMediaSessionManager;
    private final AudioManager mAudioManager;
    
    public MediaExecutor(Context context) {
        mContext = context;
        mMediaSessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        
        Slog.i(TAG, "MediaExecutor initialized");
    }
    
    @Override
    public String getExecutorType() {
        return "media";
    }
    
    /**
     * 执行媒体操作
     * 
     * 支持动作：
     * - media.play：播放
     * - media.pause：暂停
     * - media.stop：停止
     * - media.next：下一曲
     * - media.previous：上一曲
     * - media.seek：跳转
     * - media.volume：音量调节
     * - media.get_status：查询状态
     */
    @Override
    public ExecutionResult execute(ActionRequest request, ExecutionContext context) {
        long startNanos = System.nanoTime();
        
        try {
            JSONObject args = new JSONObject(request.argumentsJson);
            String actionId = request.actionId;
            
            ExecutionResult result;
            
            switch (actionId) {
                case "media.play":
                    result = executePlay(args);
                    break;
                case "media.pause":
                    result = executePause(args);
                    break;
                case "media.stop":
                    result = executeStop(args);
                    break;
                case "media.next":
                    result = executeNext(args);
                    break;
                case "media.previous":
                    result = executePrevious(args);
                    break;
                case "media.seek":
                    result = executeSeek(args);
                    break;
                case "media.volume":
                    result = executeVolume(args);
                    break;
                case "media.get_status":
                    result = executeGetStatus(args);
                    break;
                default:
                    return ExecutionResult.failure("Unsupported action: " + actionId);
            }
            
            if (result.success) {
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                JSONObject resultJson = new JSONObject(result.resultJson);
                resultJson.put("elapsed_us", elapsedMicros);
                result.resultJson = resultJson.toString();
            }
            
            return result;
            
        } catch (Exception e) {
            Slog.e(TAG, "Media execution failed", e);
            return ExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }
    
    /**
     * 播放
     */
    private ExecutionResult executePlay(JSONObject args) throws Exception {
        MediaController controller = getActiveMediaController();
        if (controller == null) {
            return ExecutionResult.failure("No active media session");
        }
        
        controller.getTransportControls().play();
        
        JSONObject result = new JSONObject();
        result.put("action", "play");
        result.put("package", controller.getPackageName());
        
        Slog.i(TAG, "Media play: package=" + controller.getPackageName());
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 暂停
     */
    private ExecutionResult executePause(JSONObject args) throws Exception {
        MediaController controller = getActiveMediaController();
        if (controller == null) {
            return ExecutionResult.failure("No active media session");
        }
        
        controller.getTransportControls().pause();
        
        JSONObject result = new JSONObject();
        result.put("action", "pause");
        result.put("package", controller.getPackageName());
        
        Slog.i(TAG, "Media pause: package=" + controller.getPackageName());
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 停止
     */
    private ExecutionResult executeStop(JSONObject args) throws Exception {
        MediaController controller = getActiveMediaController();
        if (controller == null) {
            return ExecutionResult.failure("No active media session");
        }
        
        controller.getTransportControls().stop();
        
        JSONObject result = new JSONObject();
        result.put("action", "stop");
        result.put("package", controller.getPackageName());
        
        Slog.i(TAG, "Media stop: package=" + controller.getPackageName());
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 下一曲
     */
    private ExecutionResult executeNext(JSONObject args) throws Exception {
        MediaController controller = getActiveMediaController();
        if (controller == null) {
            return ExecutionResult.failure("No active media session");
        }
        
        controller.getTransportControls().skipToNext();
        
        JSONObject result = new JSONObject();
        result.put("action", "next");
        result.put("package", controller.getPackageName());
        
        Slog.i(TAG, "Media next: package=" + controller.getPackageName());
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 上一曲
     */
    private ExecutionResult executePrevious(JSONObject args) throws Exception {
        MediaController controller = getActiveMediaController();
        if (controller == null) {
            return ExecutionResult.failure("No active media session");
        }
        
        controller.getTransportControls().skipToPrevious();
        
        JSONObject result = new JSONObject();
        result.put("action", "previous");
        result.put("package", controller.getPackageName());
        
        Slog.i(TAG, "Media previous: package=" + controller.getPackageName());
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 跳转到指定位置
     * 
     * 参数：
     * {
     *   "position": 30000  // 毫秒
     * }
     */
    private ExecutionResult executeSeek(JSONObject args) throws Exception {
        if (!args.has("position")) {
            return ExecutionResult.failure("Missing parameter: position");
        }
        
        MediaController controller = getActiveMediaController();
        if (controller == null) {
            return ExecutionResult.failure("No active media session");
        }
        
        long position = args.getLong("position");
        
        // 边界检查
        PlaybackState state = controller.getPlaybackState();
        if (state != null) {
            long duration = state.getPosition();
            if (position < 0) {
                position = 0;
            }
        }
        
        controller.getTransportControls().seekTo(position);
        
        JSONObject result = new JSONObject();
        result.put("action", "seek");
        result.put("position", position);
        result.put("package", controller.getPackageName());
        
        Slog.i(TAG, "Media seek: position=" + position);
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 音量调节
     * 
     * 参数：
     * {
     *   "level": 10,  // 绝对值（0-max）
     *   // 或
     *   "delta": 1    // 增量
     * }
     */
    private ExecutionResult executeVolume(JSONObject args) throws Exception {
        int streamType = AudioManager.STREAM_MUSIC;
        int maxVolume = mAudioManager.getStreamMaxVolume(streamType);
        int currentVolume = mAudioManager.getStreamVolume(streamType);
        int newVolume;
        
        if (args.has("level")) {
            newVolume = args.getInt("level");
        } else if (args.has("delta")) {
            int delta = args.getInt("delta");
            newVolume = currentVolume + delta;
        } else {
            return ExecutionResult.failure("Missing parameter: level or delta");
        }
        
        // 边界检查
        if (newVolume < 0) {
            newVolume = 0;
        } else if (newVolume > maxVolume) {
            newVolume = maxVolume;
        }
        
        mAudioManager.setStreamVolume(streamType, newVolume, 0);
        
        JSONObject result = new JSONObject();
        result.put("previous_volume", currentVolume);
        result.put("new_volume", newVolume);
        result.put("max_volume", maxVolume);
        
        Slog.i(TAG, String.format("Media volume: %d→%d", currentVolume, newVolume));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 查询当前播放状态
     */
    private ExecutionResult executeGetStatus(JSONObject args) throws Exception {
        MediaController controller = getActiveMediaController();
        if (controller == null) {
            JSONObject result = new JSONObject();
            result.put("active", false);
            return ExecutionResult.success(result.toString());
        }
        
        PlaybackState state = controller.getPlaybackState();
        MediaMetadata metadata = controller.getMetadata();
        
        JSONObject result = new JSONObject();
        result.put("active", true);
        result.put("package", controller.getPackageName());
        
        // 播放状态
        if (state != null) {
            result.put("state", getStateString(state.getState()));
            result.put("position", state.getPosition());
            result.put("playback_speed", state.getPlaybackSpeed());
        }
        
        // 媒体信息
        if (metadata != null) {
            result.put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            result.put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
            result.put("album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            result.put("duration", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        }
        
        // 音量
        int volume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        result.put("volume", volume);
        result.put("max_volume", maxVolume);
        
        Slog.i(TAG, "Media status queried: package=" + controller.getPackageName());
        return ExecutionResult.success(result.toString());
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 获取活跃的媒体控制器
     */
    private MediaController getActiveMediaController() {
        List<MediaController> controllers = mMediaSessionManager.getActiveSessions(null);
        
        if (controllers == null || controllers.isEmpty()) {
            return null;
        }
        
        // 返回第一个活跃会话
        return controllers.get(0);
    }
    
    /**
     * 播放状态转字符串
     */
    private String getStateString(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                return "playing";
            case PlaybackState.STATE_PAUSED:
                return "paused";
            case PlaybackState.STATE_STOPPED:
                return "stopped";
            case PlaybackState.STATE_BUFFERING:
                return "buffering";
            case PlaybackState.STATE_ERROR:
                return "error";
            default:
                return "unknown";
        }
    }
    
    @Override
    public boolean supportsAction(String actionId) {
        return actionId.startsWith("media.");
    }
}
