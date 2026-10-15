#!/usr/bin/env python3
import os

BASE = '/root/aosp/frameworks/base/core/java/com/genton'

def write(path, content):
    with open(path, 'w') as f:
        f.write(content)
    print(f"WROTE {path.split('genton/')[1]}")

# 1. MacroStep.aidl - rewrite to match AgentMacroRecorderService usage
write(os.path.join(BASE, 'macro', 'MacroStep.aidl'), '''// MacroStep.aidl
package com.genton.macro;

/**
 * 宏步骤
 *
 * 表示宏录制中的单个交互步骤
 * 字段对齐 services/core/.../AgentMacroRecorderService.java 实现
 */
parcelable MacroStep {
    // 步骤序号（在录制内唯一，从 0 递增）
    int stepIndex;

    // 步骤类型（tap/swipe/input_text/wait/assert_element/screenshot）
    String stepType;

    // 步骤参数（JSON 字符串）
    @nullable String parameters;

    // 相对上一步骤的延迟（毫秒）
    long delayMs;

    // 步骤描述（便于调试）
    @nullable String description;

    // UI 快照引用（可选）
    @nullable String uiSnapshotRef;

    // 时间戳（相对录制开始时间，毫秒）
    long timestamp;
}
''')

# 2. MacroScript.aidl - add macroId field
write(os.path.join(BASE, 'macro', 'MacroScript.aidl'), '''// MacroScript.aidl
package com.genton.macro;

import com.genton.macro.MacroStep;

/**
 * 宏脚本
 *
 * MacroRecording 生成的可执行脚本
 * 字段对齐 services/core/.../AgentMacroValidationService.java 实现
 */
parcelable MacroScript {
    // 脚本 ID（宏 ID，供测试/CI 引用）
    String macroId;

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
''')

# 3. IAgentFrameListener.aidl - add onFrameCaptured/onFrameDropped
write(os.path.join(BASE, 'perception', 'IAgentFrameListener.aidl'), '''package com.genton.perception;

import com.genton.perception.ScreenFrame;

interface IAgentFrameListener {
    void onFrameCaptured(in ScreenFrame frame);

    void onFrameDropped(in long droppedCount);
}
''')

# 4. IMacroValidationService.aidl - add STATUS_* constants
write(os.path.join(BASE, 'macro', 'IMacroValidationService.aidl'), '''package com.genton.macro;

import com.genton.macro.MacroTestCase;
import com.genton.macro.MacroTestResult;
import com.genton.macro.IMacroTestListener;

interface IMacroValidationService {
    const int STATUS_OK              = 0;
    const int STATUS_NOT_FOUND       = 2;
    const int STATUS_INTERNAL_ERROR  = -1;

    int registerTestCase(in MacroTestCase testCase, out String[] testCaseId);

    MacroTestCase getTestCase(in String testCaseId);

    MacroTestCase[] queryTestCases(in String macroId);

    MacroTestResult runTestCase(in String testCaseId);

    MacroTestResult[] runAllTestsForMacro(in String macroId);

    String runCiPipeline();

    int deleteTestCase(in String testCaseId);

    void subscribeTestEvents(in IMacroTestListener listener);

    void unsubscribeTestEvents(in IMacroTestListener listener);

    String getTestStats(in String macroId);

    String exportTestReport(in String format);
}
''')

print("DONE")
