// MacroScript.aidl
package com.ainative.macro;

import com.ainative.macro.MacroStep;

/**
 * 宏脚本
 * 
 * MacroRecording 生成的可执行脚本，包含：
 * - 标准化的步骤序列
 * - 感知适配逻辑
 * - 回退策略
 * 
 * 对应任务：F1.1 脚本生成
 */
parcelable MacroScript {
    // 脚本 ID（由系统生成）
    String scriptId;
    
    // 关联的录制 ID
    String recordingId;
    
    // 脚本版本（SemVer）
    String version;
    
    // 脚本名称
    String name;
    
    // 脚本描述
    String description;
    
    // 目标应用包名
    String targetPackage;
    
    // 执行步骤（标准化后的）
    MacroStep[] steps;
    
    // 感知适配策略
    // 0 = none（纯坐标）
    // 1 = hybrid（感知 + 坐标降级）
    // 2 = perception_only（纯感知）
    int perceptionStrategy;
    
    // 重试策略
    int maxRetries = 3;
    int retryDelayMs = 1000;
    
    // 超时配置（毫秒）
    long timeoutMs = 30000;
    
    // 机型校准参数（可选）
    @nullable String calibrationParams;
    
    // 回退脚本 ID（可选）
    @nullable String fallbackScriptId;
    
    // 生成时间戳
    long generatedAt;
    
    // 扩展元数据
    @nullable String metadata;
}
