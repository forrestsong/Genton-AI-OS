package com.android.server.genton;

import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.genton.macro.MacroTestCase;
import com.genton.macro.MacroTestResult;
import com.genton.macro.MacroScript;
import com.genton.macro.IMacroValidationService;
import com.genton.macro.IMacroTestListener;
import com.genton.assets.AssetExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 宏校验服务实现
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
public class AgentMacroValidationService extends IMacroValidationService.Stub {
    private static final String TAG = "MacroValidation";
    
    private final Context mContext;
    private final Object mLock = new Object();
    
    // 测试用例存储（testCaseId -> MacroTestCase）
    private final Map<String, MacroTestCase> mTestCases = new ConcurrentHashMap<>();
    
    // 测试结果缓存（testCaseId -> MacroTestResult）
    private final Map<String, MacroTestResult> mTestResults = new ConcurrentHashMap<>();
    
    // 测试统计（macroId -> TestStats）
    private final Map<String, TestStats> mTestStats = new ConcurrentHashMap<>();
    
    // 监听器
    private final RemoteCallbackList<IMacroTestListener> mListeners = new RemoteCallbackList<>();
    
    // 依赖服务
    private final AgentMacroRecorderService mRecorderService;
    private final AgentAssetLibraryService mAssetLibrary;
    
    // 自动停用配置
    private static final int AUTO_DISABLE_FAILURE_THRESHOLD = 3;  // 连续3次失败
    private static final double AUTO_DISABLE_FAILURE_RATE = 0.7;   // 失败率 >= 70%
    private static final int AUTO_DISABLE_MIN_TESTS = 5;           // 最少5次测试
    
    public AgentMacroValidationService(Context context,
                                       AgentMacroRecorderService recorderService,
                                       AgentAssetLibraryService assetLibrary) {
        mContext = context;
        mRecorderService = recorderService;
        mAssetLibrary = assetLibrary;
    }
    
    @Override
    public int registerTestCase(MacroTestCase testCase, String[] testCaseId) {
        enforcePermission();
        
        try {
            String tcId = generateTestCaseId();
            testCase.testCaseId = tcId;
            testCase.createdAt = System.currentTimeMillis();
            testCase.lastRunAt = 0;
            testCase.lastRunResult = null;
            
            // 验证关联的宏是否存在
            MacroScript macro = mRecorderService.getMacro(testCase.macroId);
            if (macro == null) {
                Log.w(TAG, "Macro not found: " + testCase.macroId);
                return STATUS_NOT_FOUND;
            }
            
            mTestCases.put(tcId, testCase);
            
            // 初始化统计
            TestStats stats = mTestStats.computeIfAbsent(testCase.macroId, k -> new TestStats());
            stats.totalTestCases.incrementAndGet();
            
            testCaseId[0] = tcId;
            Log.i(TAG, "Test case registered: " + tcId + " for macro " + testCase.macroId);
            return STATUS_OK;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to register test case", e);
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    @Override
    public MacroTestCase getTestCase(String testCaseId) {
        enforcePermission();
        return mTestCases.get(testCaseId);
    }
    
    @Override
    public MacroTestCase[] queryTestCases(String macroId) {
        enforcePermission();
        
        List<MacroTestCase> results = new ArrayList<>();
        
        for (MacroTestCase testCase : mTestCases.values()) {
            if (macroId == null || macroId.equals(testCase.macroId)) {
                results.add(testCase);
            }
        }
        
        return results.toArray(new MacroTestCase[0]);
    }
    
    @Override
    public MacroTestResult runTestCase(String testCaseId) {
        enforcePermission();
        
        MacroTestCase testCase = mTestCases.get(testCaseId);
        if (testCase == null) {
            MacroTestResult errorResult = new MacroTestResult();
            errorResult.testCaseId = testCaseId;
            errorResult.result = "error";
            errorResult.failureReason = "Test case not found";
            return errorResult;
        }
        
        // 通知监听器测试开始
        notifyTestStarted(testCaseId, testCase.macroId);
        
        MacroTestResult result = executeTestCase(testCase);
        
        // 更新测试用例状态
        synchronized (mLock) {
            testCase.lastRunAt = result.testedAt;
            testCase.lastRunResult = result.result;
        }
        
        // 缓存结果
        mTestResults.put(testCaseId, result);
        
        // 更新统计
        updateTestStats(testCase.macroId, result);
        
        // 通知监听器测试完成
        notifyTestCompleted(result);
        
        // 检查是否需要自动停用
        checkAutoDisable(testCase.macroId);
        
        Log.i(TAG, "Test completed: " + testCaseId + " result=" + result.result);
        return result;
    }
    
    @Override
    public MacroTestResult[] runAllTestsForMacro(String macroId) {
        enforcePermission();
        
        List<MacroTestResult> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        
        // 查询该宏的所有测试用例
        MacroTestCase[] testCases = queryTestCases(macroId);
        
        Log.i(TAG, "Running all tests for macro: " + macroId + " (" + testCases.length + " tests)");
        
        for (MacroTestCase testCase : testCases) {
            MacroTestResult result = runTestCase(testCase.testCaseId);
            results.add(result);
            
            if ("pass".equals(result.result)) {
                passed++;
            } else if ("fail".equals(result.result)) {
                failed++;
            }
        }
        
        // 通知批量测试完成
        notifyBatchTestCompleted(macroId, testCases.length, passed, failed);
        
        return results.toArray(new MacroTestResult[0]);
    }
    
    @Override
    public String runCiPipeline() {
        enforcePermission();
        
        long startTime = System.currentTimeMillis();
        
        try {
            JSONObject report = new JSONObject();
            report.put("startTime", startTime);
            report.put("pipelineVersion", "1.0.0");
            
            JSONArray macroResults = new JSONArray();
            
            // 获取所有宏
            MacroScript[] allMacros = mRecorderService.queryMacros(null);
            
            int totalMacros = 0;
            int testedMacros = 0;
            int passedMacros = 0;
            int failedMacros = 0;
            int disabledMacros = 0;
            
            for (MacroScript macro : allMacros) {
                totalMacros++;
                
                // 查询该宏的测试用例
                MacroTestCase[] testCases = queryTestCases(macro.macroId);
                
                if (testCases.length == 0) {
                    // 没有测试用例，跳过
                    continue;
                }
                
                testedMacros++;
                
                // 运行所有测试
                MacroTestResult[] results = runAllTestsForMacro(macro.macroId);
                
                // 统计结果
                int passed = 0;
                int failed = 0;
                
                for (MacroTestResult result : results) {
                    if ("pass".equals(result.result)) {
                        passed++;
                    } else if ("fail".equals(result.result)) {
                        failed++;
                    }
                }
                
                boolean macroPass = (failed == 0 && passed > 0);
                
                if (macroPass) {
                    passedMacros++;
                } else {
                    failedMacros++;
                }
                
                // 检查是否需要自动停用
                if (shouldAutoDisableMacro(macro.macroId)) {
                    disableMacroAsset(macro.macroId, "CI pipeline failure");
                    disabledMacros++;
                }
                
                // 添加到报告
                JSONObject macroResult = new JSONObject();
                macroResult.put("macroId", macro.macroId);
                macroResult.put("macroName", macro.name);
                macroResult.put("totalTests", results.length);
                macroResult.put("passed", passed);
                macroResult.put("failed", failed);
                macroResult.put("status", macroPass ? "pass" : "fail");
                
                macroResults.put(macroResult);
            }
            
            report.put("macros", macroResults);
            report.put("summary", new JSONObject()
                .put("totalMacros", totalMacros)
                .put("testedMacros", testedMacros)
                .put("passedMacros", passedMacros)
                .put("failedMacros", failedMacros)
                .put("disabledMacros", disabledMacros)
            );
            
            long endTime = System.currentTimeMillis();
            report.put("endTime", endTime);
            report.put("durationMs", endTime - startTime);
            
            Log.i(TAG, "CI Pipeline completed: " + testedMacros + " macros tested, " +
                  passedMacros + " passed, " + failedMacros + " failed, " +
                  disabledMacros + " disabled");
            
            return report.toString(2);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to run CI pipeline", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    @Override
    public int deleteTestCase(String testCaseId) {
        enforcePermission();
        
        MacroTestCase testCase = mTestCases.remove(testCaseId);
        if (testCase != null) {
            mTestResults.remove(testCaseId);
            Log.i(TAG, "Test case deleted: " + testCaseId);
            return STATUS_OK;
        }
        
        return STATUS_NOT_FOUND;
    }
    
    @Override
    public void subscribeTestEvents(IMacroTestListener listener) {
        enforcePermission();
        mListeners.register(listener);
    }
    
    @Override
    public void unsubscribeTestEvents(IMacroTestListener listener) {
        enforcePermission();
        mListeners.unregister(listener);
    }
    
    @Override
    public String getTestStats(String macroId) {
        enforcePermission();
        
        TestStats stats = mTestStats.get(macroId);
        if (stats == null) {
            return null;
        }
        
        try {
            JSONObject json = new JSONObject();
            json.put("totalTestCases", stats.totalTestCases.get());
            json.put("totalRuns", stats.totalRuns.get());
            json.put("passedRuns", stats.passedRuns.get());
            json.put("failedRuns", stats.failedRuns.get());
            json.put("consecutiveFailures", stats.consecutiveFailures.get());
            json.put("failureRate", stats.getFailureRate());
            json.put("lastRunAt", stats.lastRunAt);
            
            return json.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to serialize test stats", e);
            return null;
        }
    }
    
    @Override
    public String exportTestReport(String format) {
        enforcePermission();
        
        try {
            if ("json".equals(format)) {
                return exportJsonReport();
            } else if ("html".equals(format)) {
                return exportHtmlReport();
            } else if ("junit_xml".equals(format)) {
                return exportJunitXmlReport();
            } else {
                return "{\"error\":\"Unsupported format: " + format + "\"}";
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to export report", e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // ================ 私有辅助方法 ================
    
    private void enforcePermission() {
        mContext.enforceCallingOrSelfPermission(
            android.Manifest.permission.MANAGE_AI_NATIVE_AGENTS,
            "AgentMacroValidationService");
    }
    
    private String generateTestCaseId() {
        return "tc_" + UUID.randomUUID().toString();
    }
    
    private MacroTestResult executeTestCase(MacroTestCase testCase) {
        MacroTestResult result = new MacroTestResult();
        result.testCaseId = testCase.testCaseId;
        result.macroId = testCase.macroId;
        result.testedAt = System.currentTimeMillis();
        
        long startTime = System.currentTimeMillis();
        
        try {
            // TODO: 设置初始 UI 状态（如果提供）
            if (testCase.initialState != null) {
                // 恢复初始状态（通过截图或 UI 树对比）
            }
            
            // 执行宏重放
            int replayResult = mRecorderService.replayMacro(testCase.macroId, false);
            
            long endTime = System.currentTimeMillis();
            result.executionTimeMs = endTime - startTime;
            
            // 检查超时
            if (result.executionTimeMs > testCase.timeoutMs) {
                result.result = "timeout";
                result.failureReason = "Execution timeout (" + result.executionTimeMs + "ms > " + testCase.timeoutMs + "ms)";
                return result;
            }
            
            // 检查重放是否成功
            if (replayResult != 0) {
                result.result = "fail";
                result.failureReason = "Macro replay failed with code " + replayResult;
                return result;
            }
            
            // TODO: 验证预期结果
            if (testCase.expectedResult != null) {
                boolean resultMatch = verifyExpectedResult(testCase);
                if (!resultMatch) {
                    result.result = "fail";
                    result.failureReason = "Expected result mismatch";
                    result.expectedValue = testCase.expectedResult;
                    // result.actualValue = getActualResult();
                    return result;
                }
            }
            
            // TODO: 验证预期 UI 状态
            if (testCase.expectedUiState != null) {
                boolean uiMatch = verifyExpectedUiState(testCase);
                if (!uiMatch) {
                    result.result = "fail";
                    result.failureReason = "Expected UI state mismatch";
                    return result;
                }
            }
            
            // 测试通过
            result.result = "pass";
            
        } catch (Exception e) {
            result.result = "error";
            result.failureReason = "Exception: " + e.getMessage();
            result.logOutput = Log.getStackTraceString(e);
        }
        
        return result;
    }
    
    private boolean verifyExpectedResult(MacroTestCase testCase) {
        // TODO: 实现结果验证逻辑
        // 1. 解析 expectedResult JSON
        // 2. 获取实际执行结果
        // 3. 对比是否匹配
        return true;  // 临时返回 true
    }
    
    private boolean verifyExpectedUiState(MacroTestCase testCase) {
        // TODO: 实现 UI 状态验证
        // 1. 获取当前 UI 树快照
        // 2. 与 expectedUiState 对比
        // 3. 允许一定容差（如坐标偏移）
        return true;  // 临时返回 true
    }
    
    private void updateTestStats(String macroId, MacroTestResult result) {
        TestStats stats = mTestStats.computeIfAbsent(macroId, k -> new TestStats());
        
        stats.totalRuns.incrementAndGet();
        stats.lastRunAt = result.testedAt;
        
        if ("pass".equals(result.result)) {
            stats.passedRuns.incrementAndGet();
            stats.consecutiveFailures.set(0);
        } else if ("fail".equals(result.result)) {
            stats.failedRuns.incrementAndGet();
            stats.consecutiveFailures.incrementAndGet();
        }
    }
    
    private void checkAutoDisable(String macroId) {
        if (shouldAutoDisableMacro(macroId)) {
            String reason = "Auto-disabled due to high test failure rate";
            disableMacroAsset(macroId, reason);
            notifyMacroAutoDisabled(macroId, reason);
        }
    }
    
    private boolean shouldAutoDisableMacro(String macroId) {
        TestStats stats = mTestStats.get(macroId);
        if (stats == null) {
            return false;
        }
        
        int totalRuns = stats.totalRuns.get();
        int consecutiveFailures = stats.consecutiveFailures.get();
        
        // 条件1：连续失败次数超过阈值
        if (consecutiveFailures >= AUTO_DISABLE_FAILURE_THRESHOLD) {
            Log.w(TAG, "Macro " + macroId + " has " + consecutiveFailures + " consecutive failures");
            return true;
        }
        
        // 条件2：样本数足够 + 失败率超过阈值
        if (totalRuns >= AUTO_DISABLE_MIN_TESTS) {
            double failureRate = stats.getFailureRate();
            if (failureRate >= AUTO_DISABLE_FAILURE_RATE) {
                Log.w(TAG, "Macro " + macroId + " has high failure rate: " + failureRate);
                return true;
            }
        }
        
        return false;
    }
    
    private void disableMacroAsset(String macroId, String reason) {
        try {
            // 回灌到 AssetLibrary 停用
            // 假设宏资产 ID 与 macroId 对应
            int result = mAssetLibrary.disableAsset(macroId, 1);  // reason=1 (auto_failure)
            
            if (result == 0) {
                Log.i(TAG, "Macro asset disabled: " + macroId + " reason=" + reason);
            } else {
                Log.w(TAG, "Failed to disable macro asset: " + macroId + " code=" + result);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable macro asset", e);
        }
    }
    
    // 报告导出方法
    
    private String exportJsonReport() throws Exception {
        JSONObject report = new JSONObject();
        report.put("generatedAt", System.currentTimeMillis());
        report.put("format", "json");
        
        JSONArray testCases = new JSONArray();
        
        for (MacroTestCase testCase : mTestCases.values()) {
            JSONObject tc = new JSONObject();
            tc.put("testCaseId", testCase.testCaseId);
            tc.put("macroId", testCase.macroId);
            tc.put("name", testCase.name);
            tc.put("testType", testCase.testType);
            tc.put("lastRunResult", testCase.lastRunResult);
            tc.put("lastRunAt", testCase.lastRunAt);
            
            testCases.put(tc);
        }
        
        report.put("testCases", testCases);
        
        return report.toString(2);
    }
    
    private String exportHtmlReport() throws Exception {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><title>Macro Test Report</title>");
        html.append("<style>body{font-family:Arial;} table{border-collapse:collapse;width:100%;} ");
        html.append("th,td{border:1px solid #ddd;padding:8px;text-align:left;} ");
        html.append("th{background-color:#4CAF50;color:white;} .pass{color:green;} .fail{color:red;}</style>");
        html.append("</head><body>");
        html.append("<h1>Macro Test Report</h1>");
        html.append("<table><tr><th>Test Case</th><th>Macro ID</th><th>Type</th><th>Result</th><th>Last Run</th></tr>");
        
        for (MacroTestCase testCase : mTestCases.values()) {
            html.append("<tr>");
            html.append("<td>").append(testCase.name).append("</td>");
            html.append("<td>").append(testCase.macroId).append("</td>");
            html.append("<td>").append(testCase.testType).append("</td>");
            
            String resultClass = "pass".equals(testCase.lastRunResult) ? "pass" : "fail";
            html.append("<td class='").append(resultClass).append("'>").append(testCase.lastRunResult).append("</td>");
            html.append("<td>").append(testCase.lastRunAt).append("</td>");
            html.append("</tr>");
        }
        
        html.append("</table></body></html>");
        
        return html.toString();
    }
    
    private String exportJunitXmlReport() throws Exception {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuites>\n");
        
        // 按 macroId 分组
        Map<String, List<MacroTestCase>> grouped = new ConcurrentHashMap<>();
        for (MacroTestCase tc : mTestCases.values()) {
            grouped.computeIfAbsent(tc.macroId, k -> new ArrayList<>()).add(tc);
        }
        
        for (Map.Entry<String, List<MacroTestCase>> entry : grouped.entrySet()) {
            String macroId = entry.getKey();
            List<MacroTestCase> tests = entry.getValue();
            
            int passed = 0;
            int failed = 0;
            
            for (MacroTestCase tc : tests) {
                if ("pass".equals(tc.lastRunResult)) passed++;
                else if ("fail".equals(tc.lastRunResult)) failed++;
            }
            
            xml.append("  <testsuite name=\"").append(macroId).append("\" tests=\"").append(tests.size())
               .append("\" failures=\"").append(failed).append("\">\n");
            
            for (MacroTestCase tc : tests) {
                xml.append("    <testcase name=\"").append(tc.name).append("\" classname=\"").append(macroId).append("\">\n");
                
                if ("fail".equals(tc.lastRunResult)) {
                    xml.append("      <failure message=\"Test failed\"></failure>\n");
                }
                
                xml.append("    </testcase>\n");
            }
            
            xml.append("  </testsuite>\n");
        }
        
        xml.append("</testsuites>");
        
        return xml.toString();
    }
    
    // 通知方法
    
    private void notifyTestStarted(String testCaseId, String macroId) {
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onTestStarted(testCaseId, macroId);
            } catch (RemoteException e) {
                // Listener died
            }
        }
        mListeners.finishBroadcast();
    }
    
    private void notifyTestCompleted(MacroTestResult result) {
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onTestCompleted(result);
            } catch (RemoteException e) {
                // Listener died
            }
        }
        mListeners.finishBroadcast();
    }
    
    private void notifyBatchTestCompleted(String macroId, int total, int passed, int failed) {
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onBatchTestCompleted(macroId, total, passed, failed);
            } catch (RemoteException e) {
                // Listener died
            }
        }
        mListeners.finishBroadcast();
    }
    
    private void notifyMacroAutoDisabled(String macroId, String reason) {
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onMacroAutoDisabled(macroId, reason);
            } catch (RemoteException e) {
                // Listener died
            }
        }
        mListeners.finishBroadcast();
    }
    
    // ================ 内部类 ================
    
    /**
     * 测试统计信息
     */
    private static class TestStats {
        final AtomicInteger totalTestCases = new AtomicInteger(0);
        final AtomicInteger totalRuns = new AtomicInteger(0);
        final AtomicInteger passedRuns = new AtomicInteger(0);
        final AtomicInteger failedRuns = new AtomicInteger(0);
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        long lastRunAt = 0;
        
        double getFailureRate() {
            int total = totalRuns.get();
            if (total == 0) return 0.0;
            return (double) failedRuns.get() / total;
        }
    }
}
