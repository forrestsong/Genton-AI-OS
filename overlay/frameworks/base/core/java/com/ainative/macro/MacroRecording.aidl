// MacroRecording.aidl
package com.ainative.macro;

import com.ainative.macro.MacroStep;

/**
 * 宏录制数据
 * 
 * 表示一次完整的宏录制会话，包含：
 * - 用户交互序列（点击、滑动、输入等）
 * - 屏幕截图和感知结果
 * - 时序信息
 * 
 * 对应任务：F1.1 宏录制工具链
 */
parcelable MacroRecording {
    // 录制 ID（唯一标识）
    String recordingId;
    
    // 宏名称
    String name;
    
    // 宏描述（用于资产注册）
    String description;
    
    // 录制开始时间戳
    long startTime;
    
    // 录制结束时间戳
    long endTime;
    
    // 录制的步骤序列
    MacroStep[] steps;
    
    // 设备信息（用于机型校准）
    String deviceModel;
    String deviceManufacturer;
    int screenWidth;
    int screenHeight;
    float screenDensity;
    int sdkVersion;
    
    // 目标应用包名
    String targetPackage;
    
    // 录制状态：recording / completed / failed
    String state;
    
    // 感知结果（可选，用于智能重放）
    @nullable String perceptionData;
    
    // 扩展元数据
    @nullable String metadata;
}
