package com.android.server.ainative;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Slog;

import com.ainative.motion.GestureEvent;
import com.ainative.motion.IMotionGestureListener;
import com.ainative.motion.IMotionHubService;
import com.ainative.motion.MotionTierState;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MotionHub 姿态交互服务端
 * 
 * 核心职责：
 * 1. 三级档位管理（T1 亮屏前台/T2 深睡事件/T3 敏感全停）
 * 2. 传感器上下文唯一消费方（第三方不可读）
 * 3. 手势事件检测与广播（摇一摇就绪/翻面备忘/划动手势）
 * 4. 隐私保护（敏感场景强制门控）
 * 5. 功耗管理（三级档位功耗预算）
 * 
 * 三级档位架构：
 * - T1 亮屏前台：全传感器 400-1600Hz，全手势能力，功耗 ~15mW
 * - T2 深睡事件：Sensor Hub AON 岛，仅硬件事件，功耗 ~1mW（需硬件支持）
 * - T3 敏感全停：通话/录音/相机/支付时强制停止，功耗 0mW
 * 
 * 手势能力：
 * - 摇一摇就绪（GESTURE_SHAKE）：>1.5g, 500ms 窗口，P95 延迟 ≤150ms
 * - 翻面备忘（GESTURE_FLIP）：Z 轴翻转 >120°，P95 延迟 ≤200ms
 * - 划动手势（GESTURE_SWIPE）：6 方向，速度 >0.5m/s，P95 延迟 ≤250ms
 * 
 * 隐私保护：
 * - 敏感场景（通话/录音/相机/支付）强制 T3 档位
 * - 传感器数据仅内部使用，不外发原始数据
 * - T3 进入时立即清空传感器缓冲区
 * 
 * 功耗管理：
 * - T1: ≤15mW（高端 1600Hz），≤12mW（中端 400Hz）
 * - T2: ≤1mW（AON 岛），无 AON 岛平台禁用
 * - T3: 0mW（全部停止）
 * - 整机日均增量 <0.5%，待机增量 <0.2%（A5 门禁 KPI）
 * 
 * 对应任务：A12.1
 * 前置依赖：A6.1 ✅, A3.1 ✅, E1.1 ✅, A12.2b ✅
 * 草案：D9 §8.2/§8.5
 */
public class MotionHubService extends IMotionHubService.Stub {
    private static final String TAG = "MotionHubService";
    
    // 档位常量
    private static final int TIER_FOREGROUND = 1;        // T1: 亮屏前台
    private static final int TIER_DEEP_SLEEP = 2;        // T2: 深睡事件
    private static final int TIER_SENSITIVE_STOP = 3;    // T3: 敏感全停
    
    // 手势类型掩码
    public static final int GESTURE_SHAKE = 1 << 0;      // 摇一摇就绪
    public static final int GESTURE_FLIP = 1 << 1;       // 翻面备忘
    public static final int GESTURE_SWIPE = 1 << 2;      // 划动手势
    
    // 能力位掩码（与 A12.2b 对齐）
    public static final int CAP_TIER_FOREGROUND = 1 << 24;
    public static final int CAP_TIER_DEEP_SLEEP = 1 << 25;
    public static final int CAP_TIER_SENSITIVE_STOP = 1 << 26;
    public static final int CAP_GESTURE_SHAKE = 1 << 28;
    public static final int CAP_GESTURE_FLIP = 1 << 29;
    public static final int CAP_GESTURE_SWIPE = 1 << 30;
    public static final int CAP_SENSOR_HUB_AON = 1 << 0;  // 有 AON 岛支持
    
    // 敏感场景原因
    private static final String REASON_IN_CALL = "in_call";
    private static final String REASON_RECORDING = "recording";
    private static final String REASON_CAMERA = "camera_active";
    private static final String REASON_PAYMENT = "payment_in_progress";
    private static final String REASON_SCREENSHOT = "screenshotting";
    private static final String REASON_ACCESSIBILITY = "accessibility_active";
    private static final String REASON_DEVELOPER = "developer_mode";
    
    // 手势检测参数（T1 档位）
    private static final float SHAKE_THRESHOLD_G = 1.5f;          // 摇一摇阈值 1.5g
    private static final long SHAKE_WINDOW_MS = 500;              // 检测窗口 500ms
    private static final long SHAKE_COOLDOWN_MS = 2000;           // 冷却期 2s
    private static final float FLIP_ANGLE_THRESHOLD = 120.0f;     // 翻转角度阈值 120°
    private static final long FLIP_STABLE_DURATION_MS = 1000;     // 稳定持续时间 1s
    private static final float SWIPE_VELOCITY_THRESHOLD = 0.5f;   // 划动速度阈值 0.5m/s
    
    // 传感器采样率（根据平台能力动态调整）
    private static final int SENSOR_RATE_HIGH_END = 1600;         // 高端平台 1600Hz
    private static final int SENSOR_RATE_MID_END = 400;           // 中端平台 400Hz
    
    private final Context mContext;
    private final SensorManager mSensorManager;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    
    // 设备能力位（从系统属性读取）
    private final int mDeviceCapabilities;
    
    // 当前档位状态
    private int mCurrentTier = TIER_SENSITIVE_STOP;  // 默认停止，等待激活
    private String mCurrentSensitiveReason = null;
    
    // 手势开关状态（用户设置）
    private final Map<Integer, Boolean> mGestureEnabled = new ConcurrentHashMap<>();
    
    // 监听器管理
    private final RemoteCallbackList<IMotionGestureListener> mListeners = new RemoteCallbackList<>();
    private final Map<Long, ListenerRecord> mListenerRecords = new ConcurrentHashMap<>();
    private final AtomicLong mHandleIdGenerator = new AtomicLong(1);
    
    // 传感器监听器
    private final List<Sensor> mActiveSensors = new ArrayList<>();
    private final SensorEventListener mSensorListener;
    
    // 手势检测状态
    private long mLastShakeTime = 0;
    private long mLastFlipTime = 0;
    private float[] mLastGravity = new float[3];
    private boolean mIsFlipped = false;
    private long mFlipStartTime = 0;
    
    // 传感器数据缓冲区（用于手势识别）
    private final List<SensorDataPoint> mAccelBuffer = new ArrayList<>();
    private final List<SensorDataPoint> mGyroBuffer = new ArrayList<>();
    private static final int BUFFER_SIZE = 100;  // 最多保留 100 个采样点
    
    // 统计信息
    private final AtomicLong mTotalShakeCount = new AtomicLong(0);
    private final AtomicLong mTotalFlipCount = new AtomicLong(0);
    private final AtomicLong mTotalSwipeCount = new AtomicLong(0);
    private final AtomicLong mFalsePositiveCount = new AtomicLong(0);
    
    // 审计埋点（后续集成 AgentAuditService）
    private final AuditInstrumentation mAuditInstrumentation;
    
    /**
     * 监听器记录
     */
    private static class ListenerRecord {
        final IMotionGestureListener listener;
        final int gestureMask;
        final long handleId;
        final long registerTime;
        
        ListenerRecord(IMotionGestureListener listener, int gestureMask, long handleId) {
            this.listener = listener;
            this.gestureMask = gestureMask;
            this.handleId = handleId;
            this.registerTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 传感器数据点
     */
    private static class SensorDataPoint {
        final long timestamp;
        final float x, y, z;
        
        SensorDataPoint(long timestamp, float x, float y, float z) {
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
    
    public MotionHubService(Context context) {
        mContext = context;
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        
        // 读取设备能力位（从系统属性）
        mDeviceCapabilities = SystemProperties.getInt("ro.ainative.motion.capabilities", 0);
        
        // 初始化手势开关（从系统属性读取默认值）
        mGestureEnabled.put(GESTURE_SHAKE, SystemProperties.getBoolean(
            "ro.ainative.motion.gesture.shake", false));
        mGestureEnabled.put(GESTURE_FLIP, SystemProperties.getBoolean(
            "ro.ainative.motion.gesture.flip", false));
        mGestureEnabled.put(GESTURE_SWIPE, SystemProperties.getBoolean(
            "ro.ainative.motion.gesture.swipe", false));
        
        // 创建传感器监听器
        mSensorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                handleSensorEvent(event);
            }
            
            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // 暂不处理精度变化
            }
        };
        
        // 审计埋点
        mAuditInstrumentation = new AuditInstrumentation(context);
        
        Slog.i(TAG, "MotionHubService created, capabilities=0x" + 
            Integer.toHexString(mDeviceCapabilities));
    }
    
    /**
     * 启动服务
     */
    public void onStart() {
        mHandlerThread = new HandlerThread("MotionHubService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        
        // 检查档位支持情况
        logCapabilities();
        
        // 默认进入 T1 档位（如果支持且用户已授权）
        if (hasCapability(CAP_TIER_FOREGROUND) && isAnyGestureEnabled()) {
            transitionToTier(TIER_FOREGROUND, "service_started");
        }
        
        Slog.i(TAG, "MotionHubService started");
    }
    
    /**
     * 注册手势监听器
     */
    @Override
    public long registerGestureListener(IMotionGestureListener listener, int gestureMask) {
        if (listener == null) {
            Slog.w(TAG, "registerGestureListener: null listener");
            return -1;
        }
        
        long handleId = mHandleIdGenerator.getAndIncrement();
        ListenerRecord record = new ListenerRecord(listener, gestureMask, handleId);
        
        mListenerRecords.put(handleId, record);
        mListeners.register(listener);
        
        Slog.d(TAG, "Registered gesture listener: handleId=" + handleId + 
            ", gestureMask=0x" + Integer.toHexString(gestureMask));
        
        return handleId;
    }
    
    /**
     * 注销手势监听器
     */
    @Override
    public void unregisterGestureListener(long handleId) {
        ListenerRecord record = mListenerRecords.remove(handleId);
        if (record != null) {
            mListeners.unregister(record.listener);
            Slog.d(TAG, "Unregistered gesture listener: handleId=" + handleId);
        }
    }
    
    /**
     * 获取当前档位状态
     */
    @Override
    public MotionTierState getCurrentTierState() {
        MotionTierState state = new MotionTierState();
        state.currentTier = mCurrentTier;
        state.capabilities = mDeviceCapabilities;
        state.shakeEnabled = mGestureEnabled.getOrDefault(GESTURE_SHAKE, false);
        state.flipEnabled = mGestureEnabled.getOrDefault(GESTURE_FLIP, false);
        state.swipeEnabled = mGestureEnabled.getOrDefault(GESTURE_SWIPE, false);
        state.currentPowerMw = getCurrentPowerBudget();
        state.sensitiveReason = mCurrentSensitiveReason;
        return state;
    }
    
    /**
     * 设置手势开关
     */
    @Override
    public int setGestureEnabled(int gestureType, boolean enabled) {
        if (!isValidGestureType(gestureType)) {
            Slog.w(TAG, "setGestureEnabled: invalid gesture type " + gestureType);
            return -1;
        }
        
        // 检查设备是否支持该手势
        if (!isGestureSupported(gestureType)) {
            Slog.w(TAG, "setGestureEnabled: gesture " + gestureType + " not supported on this device");
            return -2;
        }
        
        mGestureEnabled.put(gestureType, enabled);
        
        // 如果所有手势都关闭，停止传感器
        if (!isAnyGestureEnabled()) {
            stopSensors();
        } else if (mCurrentTier == TIER_FOREGROUND) {
            // 重新配置传感器
            startSensors();
        }
        
        Slog.i(TAG, "setGestureEnabled: gesture=" + gestureType + ", enabled=" + enabled);
        return 0;
    }
    
    /**
     * 查询手势开关状态
     */
    @Override
    public boolean isGestureEnabled(int gestureType) {
        return mGestureEnabled.getOrDefault(gestureType, false);
    }
    
    /**
     * 强制进入敏感场景模式（T3 档位）
     */
    @Override
    public void enterSensitiveMode(String reason) {
        if (reason == null || reason.isEmpty()) {
            Slog.w(TAG, "enterSensitiveMode: null or empty reason");
            return;
        }
        
        Slog.i(TAG, "Entering sensitive mode: " + reason);
        mCurrentSensitiveReason = reason;
        transitionToTier(TIER_SENSITIVE_STOP, reason);
    }
    
    /**
     * 退出敏感场景模式
     */
    @Override
    public void exitSensitiveMode(String reason) {
        if (!reason.equals(mCurrentSensitiveReason)) {
            Slog.w(TAG, "exitSensitiveMode: reason mismatch, current=" + 
                mCurrentSensitiveReason + ", exit=" + reason);
            return;
        }
        
        Slog.i(TAG, "Exiting sensitive mode: " + reason);
        mCurrentSensitiveReason = null;
        
        // 恢复到 T1 档位（如果支持）
        if (hasCapability(CAP_TIER_FOREGROUND) && isAnyGestureEnabled()) {
            transitionToTier(TIER_FOREGROUND, "sensitive_mode_exit");
        }
    }
    
    /**
     * 查询设备能力位
     */
    @Override
    public int getDeviceCapabilities() {
        return mDeviceCapabilities;
    }
    
    /**
     * 获取手势统计信息
     */
    @Override
    public String getGestureStatistics() {
        try {
            JSONObject json = new JSONObject();
            json.put("currentTier", getTierName(mCurrentTier));
            json.put("currentPowerMw", getCurrentPowerBudget());
            json.put("totalShakeCount", mTotalShakeCount.get());
            json.put("totalFlipCount", mTotalFlipCount.get());
            json.put("totalSwipeCount", mTotalSwipeCount.get());
            json.put("falsePositiveCount", mFalsePositiveCount.get());
            json.put("shakeEnabled", mGestureEnabled.getOrDefault(GESTURE_SHAKE, false));
            json.put("flipEnabled", mGestureEnabled.getOrDefault(GESTURE_FLIP, false));
            json.put("swipeEnabled", mGestureEnabled.getOrDefault(GESTURE_SWIPE, false));
            json.put("activeSensorCount", mActiveSensors.size());
            json.put("listenerCount", mListenerRecords.size());
            return json.toString();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to generate statistics JSON", e);
            return "{}";
        }
    }
    
    // ===========================
    // 内部实现方法
    // ===========================
    
    /**
     * 档位切换
     */
    private void transitionToTier(int targetTier, String reason) {
        if (mCurrentTier == targetTier) {
            return;  // 已在目标档位
        }
        
        int oldTier = mCurrentTier;
        mCurrentTier = targetTier;
        
        // 根据档位配置传感器
        switch (targetTier) {
            case TIER_FOREGROUND:
                startSensors();
                break;
            case TIER_DEEP_SLEEP:
                // TODO: 配置 Sensor Hub AON 岛（需要 HAL 层支持）
                Slog.w(TAG, "T2 deep sleep tier not yet implemented");
                break;
            case TIER_SENSITIVE_STOP:
                stopSensors();
                clearSensorBuffers();
                break;
        }
        
        // 审计日志
        mAuditInstrumentation.recordTierTransition(oldTier, targetTier, reason);
        
        Slog.i(TAG, "Tier transition: " + getTierName(oldTier) + " -> " + 
            getTierName(targetTier) + ", reason=" + reason);
    }
    
    /**
     * 启动传感器
     */
    private void startSensors() {
        if (!hasCapability(CAP_TIER_FOREGROUND)) {
            Slog.w(TAG, "T1 foreground tier not supported on this device");
            return;
        }
        
        // 先停止现有传感器
        stopSensors();
        
        // 确定采样率（根据设备能力）
        int sampleRateHz = hasCapability(CAP_SENSOR_HUB_AON) ? 
            SENSOR_RATE_HIGH_END : SENSOR_RATE_MID_END;
        int sensorDelay = (sampleRateHz >= 1000) ? 
            SensorManager.SENSOR_DELAY_FASTEST : SensorManager.SENSOR_DELAY_GAME;
        
        // 加速度计（所有手势都需要）
        Sensor accel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            mSensorManager.registerListener(mSensorListener, accel, sensorDelay, mHandler);
            mActiveSensors.add(accel);
            Slog.d(TAG, "Registered accelerometer, rate=" + sampleRateHz + "Hz");
        }
        
        // 陀螺仪（划动手势需要）
        if (mGestureEnabled.getOrDefault(GESTURE_SWIPE, false) && 
            hasCapability(CAP_GESTURE_SWIPE)) {
            Sensor gyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyro != null) {
                mSensorManager.registerListener(mSensorListener, gyro, sensorDelay, mHandler);
                mActiveSensors.add(gyro);
                Slog.d(TAG, "Registered gyroscope");
            }
        }
        
        // 重力传感器（翻面手势需要）
        if (mGestureEnabled.getOrDefault(GESTURE_FLIP, false) && 
            hasCapability(CAP_GESTURE_FLIP)) {
            Sensor gravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (gravity != null) {
                mSensorManager.registerListener(mSensorListener, gravity, 
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
                mActiveSensors.add(gravity);
                Slog.d(TAG, "Registered gravity sensor");
            }
        }
    }
    
    /**
     * 停止传感器
     */
    private void stopSensors() {
        synchronized (mActiveSensors) {
            for (Sensor sensor : mActiveSensors) {
                mSensorManager.unregisterListener(mSensorListener, sensor);
            }
            mActiveSensors.clear();
        }
        Slog.d(TAG, "Stopped all sensors");
    }
    
    /**
     * 清空传感器缓冲区（隐私保护）
     */
    private void clearSensorBuffers() {
        synchronized (mAccelBuffer) {
            mAccelBuffer.clear();
        }
        synchronized (mGyroBuffer) {
            mGyroBuffer.clear();
        }
        Slog.d(TAG, "Cleared sensor buffers");
    }
    
    /**
     * 处理传感器事件
     */
    private void handleSensorEvent(SensorEvent event) {
        if (mCurrentTier != TIER_FOREGROUND) {
            return;  // 非前台档位不处理
        }
        
        long now = System.currentTimeMillis();
        
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                handleAccelerometerEvent(event, now);
                break;
            case Sensor.TYPE_GYROSCOPE:
                handleGyroscopeEvent(event, now);
                break;
            case Sensor.TYPE_GRAVITY:
                handleGravityEvent(event, now);
                break;
        }
    }
    
    /**
     * 处理加速度计事件（摇一摇检测）
     */
    private void handleAccelerometerEvent(SensorEvent event, long now) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        
        // 添加到缓冲区
        synchronized (mAccelBuffer) {
            mAccelBuffer.add(new SensorDataPoint(now, x, y, z));
            if (mAccelBuffer.size() > BUFFER_SIZE) {
                mAccelBuffer.remove(0);
            }
        }
        
        // 检测摇一摇手势
        if (mGestureEnabled.getOrDefault(GESTURE_SHAKE, false) && 
            hasCapability(CAP_GESTURE_SHAKE)) {
            detectShakeGesture(x, y, z, now);
        }
    }
    
    /**
     * 检测摇一摇手势
     */
    private void detectShakeGesture(float x, float y, float z, long now) {
        // 冷却期检查
        if (now - mLastShakeTime < SHAKE_COOLDOWN_MS) {
            return;
        }
        
        // 计算加速度幅值
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        float gForce = magnitude / SensorManager.GRAVITY_EARTH;
        
        // 阈值检测（>1.5g）
        if (gForce > SHAKE_THRESHOLD_G) {
            mLastShakeTime = now;
            mTotalShakeCount.incrementAndGet();
            
            // 触发手势事件
            GestureEvent event = new GestureEvent();
            event.gestureType = GESTURE_SHAKE;
            event.timestamp = now;
            event.confidence = Math.min(1.0f, gForce / (SHAKE_THRESHOLD_G * 2));
            event.metadata = "gForce=" + gForce;
            
            broadcastGestureEvent(event);
            
            Slog.d(TAG, "Shake gesture detected: gForce=" + gForce);
        }
    }
    
    /**
     * 处理陀螺仪事件（划动检测）
     */
    private void handleGyroscopeEvent(SensorEvent event, long now) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        
        // 添加到缓冲区
        synchronized (mGyroBuffer) {
            mGyroBuffer.add(new SensorDataPoint(now, x, y, z));
            if (mGyroBuffer.size() > BUFFER_SIZE) {
                mGyroBuffer.remove(0);
            }
        }
        
        // TODO: 实现划动手势检测（需要更复杂的轨迹分析）
    }
    
    /**
     * 处理重力传感器事件（翻面检测）
     */
    private void handleGravityEvent(SensorEvent event, long now) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        
        // 保存重力向量
        mLastGravity[0] = x;
        mLastGravity[1] = y;
        mLastGravity[2] = z;
        
        // 检测翻面手势
        if (mGestureEnabled.getOrDefault(GESTURE_FLIP, false) && 
            hasCapability(CAP_GESTURE_FLIP)) {
            detectFlipGesture(x, y, z, now);
        }
    }
    
    /**
     * 检测翻面手势
     */
    private void detectFlipGesture(float x, float y, float z, long now) {
        // 计算 Z 轴角度（相对于地面）
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        if (magnitude < 0.1f) {
            return;  // 避免除零
        }
        
        float zNormalized = z / magnitude;
        float angle = (float) Math.toDegrees(Math.acos(zNormalized));
        
        // 检测翻转状态（Z 轴翻转 >120°）
        boolean isFlippedNow = angle > FLIP_ANGLE_THRESHOLD;
        
        if (isFlippedNow && !mIsFlipped) {
            // 进入翻转状态
            mIsFlipped = true;
            mFlipStartTime = now;
        } else if (!isFlippedNow && mIsFlipped) {
            // 退出翻转状态
            long flipDuration = now - mFlipStartTime;
            if (flipDuration >= FLIP_STABLE_DURATION_MS) {
                // 稳定持续 >1s，触发手势
                mTotalFlipCount.incrementAndGet();
                mLastFlipTime = now;
                
                GestureEvent event = new GestureEvent();
                event.gestureType = GESTURE_FLIP;
                event.timestamp = now;
                event.confidence = 0.9f;
                event.metadata = "angle=" + angle + ", duration=" + flipDuration + "ms";
                
                broadcastGestureEvent(event);
                
                Slog.d(TAG, "Flip gesture detected: angle=" + angle + ", duration=" + flipDuration + "ms");
            }
            mIsFlipped = false;
        }
    }
    
    /**
     * 广播手势事件
     */
    private void broadcastGestureEvent(GestureEvent event) {
        int i = mListeners.beginBroadcast();
        try {
            while (i > 0) {
                i--;
                IMotionGestureListener listener = mListeners.getBroadcastItem(i);
                
                // 检查监听器的手势掩码
                ListenerRecord record = findListenerRecord(listener);
                if (record != null && (record.gestureMask & event.gestureType) != 0) {
                    try {
                        listener.onGestureDetected(event);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to deliver gesture event to listener", e);
                    }
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
    }
    
    /**
     * 查找监听器记录
     */
    private ListenerRecord findListenerRecord(IMotionGestureListener listener) {
        for (ListenerRecord record : mListenerRecords.values()) {
            if (record.listener.asBinder() == listener.asBinder()) {
                return record;
            }
        }
        return null;
    }
    
    /**
     * 检查是否有能力位
     */
    private boolean hasCapability(int cap) {
        return (mDeviceCapabilities & cap) != 0;
    }
    
    /**
     * 检查手势是否支持
     */
    private boolean isGestureSupported(int gestureType) {
        switch (gestureType) {
            case GESTURE_SHAKE:
                return hasCapability(CAP_GESTURE_SHAKE);
            case GESTURE_FLIP:
                return hasCapability(CAP_GESTURE_FLIP);
            case GESTURE_SWIPE:
                return hasCapability(CAP_GESTURE_SWIPE);
            default:
                return false;
        }
    }
    
    /**
     * 检查是否有任何手势启用
     */
    private boolean isAnyGestureEnabled() {
        for (Boolean enabled : mGestureEnabled.values()) {
            if (enabled) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查手势类型是否有效
     */
    private boolean isValidGestureType(int gestureType) {
        return gestureType == GESTURE_SHAKE || 
               gestureType == GESTURE_FLIP || 
               gestureType == GESTURE_SWIPE;
    }
    
    /**
     * 获取当前功耗预算（mW）
     */
    private float getCurrentPowerBudget() {
        switch (mCurrentTier) {
            case TIER_FOREGROUND:
                return hasCapability(CAP_SENSOR_HUB_AON) ? 15.0f : 12.0f;
            case TIER_DEEP_SLEEP:
                return 1.0f;
            case TIER_SENSITIVE_STOP:
                return 0.0f;
            default:
                return 0.0f;
        }
    }
    
    /**
     * 获取档位名称
     */
    private String getTierName(int tier) {
        switch (tier) {
            case TIER_FOREGROUND:
                return "T1_FOREGROUND";
            case TIER_DEEP_SLEEP:
                return "T2_DEEP_SLEEP";
            case TIER_SENSITIVE_STOP:
                return "T3_SENSITIVE_STOP";
            default:
                return "UNKNOWN";
        }
    }
    
    /**
     * 记录能力位信息
     */
    private void logCapabilities() {
        StringBuilder sb = new StringBuilder();
        sb.append("Device capabilities: 0x").append(Integer.toHexString(mDeviceCapabilities));
        sb.append("\n  T1 Foreground: ").append(hasCapability(CAP_TIER_FOREGROUND));
        sb.append("\n  T2 Deep Sleep: ").append(hasCapability(CAP_TIER_DEEP_SLEEP));
        sb.append("\n  T3 Sensitive Stop: ").append(hasCapability(CAP_TIER_SENSITIVE_STOP));
        sb.append("\n  Gesture Shake: ").append(hasCapability(CAP_GESTURE_SHAKE));
        sb.append("\n  Gesture Flip: ").append(hasCapability(CAP_GESTURE_FLIP));
        sb.append("\n  Gesture Swipe: ").append(hasCapability(CAP_GESTURE_SWIPE));
        sb.append("\n  Sensor Hub AON: ").append(hasCapability(CAP_SENSOR_HUB_AON));
        Slog.i(TAG, sb.toString());
    }
    
    /**
     * 审计埋点辅助类
     */
    private static class AuditInstrumentation {
        private final Context mContext;
        
        AuditInstrumentation(Context context) {
            mContext = context;
        }
        
        void recordTierTransition(int oldTier, int newTier, String reason) {
            // TODO: 集成 AgentAuditService
            Slog.i(TAG, "Audit: Tier transition " + oldTier + " -> " + newTier + ", reason=" + reason);
        }
    }
}
