// IMacroRecordingCallback.aidl
package com.ainative.macro;

import com.ainative.macro.MacroExecutionResult;

/**
 * 宏录制/执行回调接口
 */
interface IMacroRecordingCallback {
    /**
     * 执行进度更新
     * 
     * @param executionId 执行 ID
     * @param currentStep 当前步骤
     * @param totalSteps 总步骤数
     */
    void onProgress(in String executionId, int currentStep, int totalSteps);
    
    /**
     * 步骤执行完成
     * 
     * @param executionId 执行 ID
     * @param stepId 步骤 ID
     * @param success 是否成功
     */
    void onStepCompleted(in String executionId, int stepId, boolean success);
    
    /**
     * 执行完成
     * 
     * @param result 执行结果
     */
    void onCompleted(in MacroExecutionResult result);
    
    /**
     * 执行失败
     * 
     * @param executionId 执行 ID
     * @param errorCode 错误码
     * @param errorMessage 错误消息
     */
    void onFailed(in String executionId, int errorCode, in String errorMessage);
}
