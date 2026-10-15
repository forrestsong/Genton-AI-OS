// IMacroValidationService.aidl
package com.ainative.macro;

import com.ainative.macro.MacroTestCase;
import com.ainative.macro.MacroTestResult;
import com.ainative.macro.IMacroTestListener;

/**
 * 宏校验服务接口
 * 
 * 职责：
 * 1. 管理黄金样例（Golden Test Cases）
 * 2. 自动重放测试
 * 3. 失败检测与自动停用
 * 4. CI 集成支持
 * 
 * 对应任务：F1.2 宏资产CI校验流水线
 * 草案依据：D11 + F类功能扩展
 */
interface IMacroValidationService {
    const int STATUS_OK = 0;
    const int STATUS_DENIED = 1;
    const int STATUS_NOT_FOUND = 2;
    const int STATUS_VALIDATION_FAILED = 3;
    const int STATUS_INTERNAL_ERROR = -1;
    
    /**
     * 注册黄金样例
     * 
     * @param testCase 测试用例
     * @param testCaseId 输出参数，返回测试用例 ID（单元素数组，取 testCaseId[0]）
     * @return STATUS_OK 成功；其他错误码
     */
    int registerTestCase(in MacroTestCase testCase, out String[] testCaseId);
    
    /**
     * 获取测试用例
     * 
     * @param testCaseId 测试用例 ID
     * @return MacroTestCase，不存在返回 null
     */
    MacroTestCase getTestCase(in String testCaseId);
    
    /**
     * 查询测试用例（按宏ID）
     * 
     * @param macroId 宏 ID
     * @return MacroTestCase 数组
     */
    MacroTestCase[] queryTestCases(in String macroId);
    
    /**
     * 执行单个测试用例
     * 
     * @param testCaseId 测试用例 ID
     * @return MacroTestResult 测试结果
     */
    MacroTestResult runTestCase(in String testCaseId);
    
    /**
     * 批量测试宏的所有用例
     * 
     * @param macroId 宏 ID
     * @return MacroTestResult 数组
     */
    MacroTestResult[] runAllTestsForMacro(in String macroId);
    
    /**
     * 运行完整 CI 流水线
     * 
     * 对所有已注册的宏执行测试，失败率超过阈值自动停用
     * 
     * @return JSON 格式的报告
     */
    String runCiPipeline();
    
    /**
     * 删除测试用例
     * 
     * @param testCaseId 测试用例 ID
     * @return STATUS_OK 成功；其他错误码
     */
    int deleteTestCase(in String testCaseId);
    
    /**
     * 订阅测试事件
     * 
     * @param listener 监听器
     */
    void subscribeTestEvents(in IMacroTestListener listener);
    
    /**
     * 取消订阅
     * 
     * @param listener 监听器
     */
    void unsubscribeTestEvents(in IMacroTestListener listener);
    
    /**
     * 获取宏的测试统计
     * 
     * @param macroId 宏 ID
     * @return JSON 格式统计信息
     */
    String getTestStats(in String macroId);
    
    /**
     * 导出测试报告
     * 
     * @param format 格式：json / html / junit_xml
     * @return 报告内容
     */
    String exportTestReport(in String format);
}
