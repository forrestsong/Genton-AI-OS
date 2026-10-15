// IMacroTestListener.aidl
package com.ainative.macro;

import com.ainative.macro.MacroTestResult;

/**
 * 宏测试事件监听器
 */
oneway interface IMacroTestListener {
    /**
     * 测试开始
     * 
     * @param testCaseId 测试用例 ID
     * @param macroId 宏 ID
     */
    void onTestStarted(in String testCaseId, in String macroId);
    
    /**
     * 测试完成
     * 
     * @param result 测试结果
     */
    void onTestCompleted(in MacroTestResult result);
    
    /**
     * 批量测试完成
     * 
     * @param macroId 宏 ID
     * @param totalTests 总测试数
     * @param passedTests 通过数
     * @param failedTests 失败数
     */
    void onBatchTestCompleted(in String macroId, int totalTests, int passedTests, int failedTests);
    
    /**
     * 宏自动停用
     * 
     * @param macroId 宏 ID
     * @param reason 停用原因
     */
    void onMacroAutoDisabled(in String macroId, in String reason);
}
