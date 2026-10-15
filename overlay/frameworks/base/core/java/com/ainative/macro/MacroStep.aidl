// MacroStep.aidl
package com.ainative.macro;

/**
 * 宏步骤
 * 
 * 表示宏录制中的单个交互步骤
 */
parcelable MacroStep {
    // 步骤 ID（在录制内唯一）
    int stepId;
    
    // 步骤类型
    // 0 = tap（点击）
    // 1 = swipe（滑动）
    // 2 = input_text（输入文本）
    // 3 = wait（等待）
    // 4 = assert_element（断言元素存在）
    // 5 = screenshot（截图检查点）
    int type;
    
    // 时间戳（相对录制开始时间，毫秒）
    long timestamp;
    
    // 坐标信息（type=0,1 时使用）
    int x;
    int y;
    
    // 滑动目标坐标（type=1 时使用）
    int targetX;
    int targetY;
    
    // 滑动持续时间（type=1 时使用，毫秒）
    int durationMs;
    
    // 输入文本内容（type=2 时使用）
    @nullable String inputText;
    
    // 等待时间（type=3 时使用，毫秒）
    int waitMs;
    
    // 感知元素描述（type=4 时使用）
    @nullable String elementDescription;
    
    // 感知置信度阈值（type=4 时使用，0.0-1.0）
    float confidenceThreshold;
    
    // 截图路径（type=5 时使用）
    @nullable String screenshotPath;
    
    // 步骤描述（便于调试）
    @nullable String description;
    
    // 扩展数据（JSON 格式）
    @nullable String extras;
}
