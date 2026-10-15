// TurnDelta.aidl
// 流式增量数据
package com.genton.agent;

/**
 * 轮次流式增量
 * 
 * 大脑推理过程中的流式输出（thinking/text/tool_activity）
 */
parcelable TurnDelta {
    /**
     * 增量类型
     * 
     * 0 = thinking（思考中，显示加载指示器）
     * 1 = text_stream（文本流式输出）
     * 2 = tool_activity（工具调用活动，如"正在打开应用"）
     */
    int type;
    
    /**
     * 文本内容（type=1 时为流式增量文本）
     */
    String text;
    
    /**
     * 工具类别（type=2 时使用）
     * 
     * 示例值：
     * 0 = 打开应用
     * 1 = 发送消息
     * 2 = 修改设置
     * 3 = 文件操作
     * 
     * 脱敏后的动作类别（不含具体参数）
     */
    int toolKind;
}
