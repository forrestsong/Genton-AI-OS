// IMacroRecordListener.aidl
package com.ainative.macro;

import com.ainative.macro.MacroStep;

/**
 * 宏录制事件监听器
 */
oneway interface IMacroRecordListener {
    /**
     * 录制开始
     * 
     * @param sessionId 会话 ID
     */
    void onRecordStarted(in String sessionId);
    
    /**
     * 录制步骤
     * 
     * @param sessionId 会话 ID
     * @param step 录制的步骤
     */
    void onStepRecorded(in String sessionId, in MacroStep step);
    
    /**
     * 录制停止
     * 
     * @param sessionId 会话 ID
     * @param totalSteps 总步骤数
     */
    void onRecordStopped(in String sessionId, int totalSteps);
    
    /**
     * 宏生成完成
     * 
     * @param sessionId 会话 ID
     * @param macroId 生成的宏 ID
     */
    void onMacroGenerated(in String sessionId, in String macroId);
}
