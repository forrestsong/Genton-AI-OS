package com.ainative.motion;

import com.ainative.motion.GestureEvent;
import com.ainative.motion.MotionTierState;
import com.ainative.motion.IMotionGestureListener;

/**
 * IMotionHubService - 姿态交互服务接口
 * 
 * AI Native OS 姿态手势门控服务
 * 职责：
 * 1. 三级档位管理（T1 亮屏前台/T2 深睡事件/T3 敏感全停）
 * 2. 传感器上下文唯一消费方（第三方不可读）
 * 3. 手势事件检测与广播（摇一摇就绪/翻面备忘/划动手势）
 * 4. 隐私保护（敏感场景强制门控）
 * 5. 功耗管理（三级档位功耗预算）
 * 
 * 对应任务：A12.1
 * 前置依赖：A6.1 (事件总线), A3.1 (动作注册表), E1.1 (安全基线), A12.2b (档位方案)
 * 草案：D9 §8.2/§8.5（用户体验保障系统草案 S7）
 * 
 * 权限要求：
 * - CONTROL_MOTION_GESTURES (signature|privileged) - 控制手势开关
 * - READ_MOTION_GESTURE_STATE (signature|privileged) - 读取手势状态
 */
interface IMotionHubService {
    /**
     * 注册手势监听器
     * @param listener 手势事件回调
     * @param gestureMask 关注的手势掩码（GESTURE_SHAKE | GESTURE_FLIP | GESTURE_SWIPE）
     * @return handleId 监听句柄，用于注销
     */
    long registerGestureListener(in IMotionGestureListener listener, int gestureMask);
    
    /**
     * 注销手势监听器
     * @param handleId registerGestureListener 返回的句柄
     */
    void unregisterGestureListener(long handleId);
    
    /**
     * 获取当前档位状态
     * @return MotionTierState 档位状态（当前档位/功耗/手势能力）
     */
    MotionTierState getCurrentTierState();
    
    /**
     * 设置手势开关（用户设置）
     * @param gestureType 手势类型（GESTURE_SHAKE/GESTURE_FLIP/GESTURE_SWIPE）
     * @param enabled 是否启用
     * @return 0 成功，负数错误码
     */
    int setGestureEnabled(int gestureType, boolean enabled);
    
    /**
     * 查询手势开关状态
     * @param gestureType 手势类型
     * @return true 已启用，false 已禁用
     */
    boolean isGestureEnabled(int gestureType);
    
    /**
     * 强制进入敏感场景模式（T3 档位）
     * 系统内部调用，外部不可访问
     * @param reason 敏感场景原因（REASON_IN_CALL/REASON_RECORDING/REASON_CAMERA等）
     */
    void enterSensitiveMode(String reason);
    
    /**
     * 退出敏感场景模式
     * @param reason 敏感场景原因
     */
    void exitSensitiveMode(String reason);
    
    /**
     * 查询设备能力位
     * @return 32 位能力掩码（CAP_TIER_* | CAP_GESTURE_* | CAP_SENSOR_HUB_AON）
     */
    int getDeviceCapabilities();
    
    /**
     * 获取手势统计信息（调试用）
     * @return JSON 字符串（总触发次数/误触次数/当前功耗等）
     */
    String getGestureStatistics();
}
