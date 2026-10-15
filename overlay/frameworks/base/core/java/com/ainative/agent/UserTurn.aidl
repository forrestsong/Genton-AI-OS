// UserTurn.aidl
// 用户轮次数据
package com.ainative.agent;

import com.ainative.agent.FormSubmitPayload;

/**
 * 用户轮次
 * 
 * 用户输入的多模态数据（文本/语音/附件/表单提交）
 */
parcelable UserTurn {
    /**
     * 轮次 ID（由 UI 生成，防重复提交）
     * 
     * 格式：UUID v4
     */
    String turnId;
    
    /**
     * 输入类型
     * 
     * 0 = text（文本输入）
     * 1 = voice（语音输入，可先本地转写）
     * 2 = attachment（附件/截图）
     * 3 = command（点按建议胶囊）
     * 4 = form_submit（L0.5 表单提交）
     */
    int kind;
    
    /**
     * 文本/指令内容
     * 
     * kind=0/1: 用户输入的文本
     * kind=3: 建议胶囊的命令 ID
     * kind=4: 空（实际数据在 formSubmit）
     */
    String content;
    
    /**
     * 附件引用（文件/截图，走既有能力授权）
     * 
     * 格式：content:// URI 或文件路径
     */
    String[] attachmentRefs;
    
    /**
     * 表单提交负载（kind=4 时携带）
     */
    FormSubmitPayload formSubmit;
    
    /**
     * 创建时间戳（纳秒，SystemClock.elapsedRealtimeNanos）
     */
    long createdAtRealtimeNanos;
}
