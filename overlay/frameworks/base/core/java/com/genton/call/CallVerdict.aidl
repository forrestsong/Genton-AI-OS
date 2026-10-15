// CallVerdict.aidl
package com.genton.call;

/**
 * 来电判定结果
 */
parcelable CallVerdict {
    /**
     * 路由决策：
     * 0 = RING_NATIVE（原生响铃）
     * 1 = AGENT_HANDLE（AI 接管）
     * 2 = RECORD_ONLY（仅记录）
     */
    int route;

    /**
     * 接管模式（当 route = AGENT_HANDLE 时有效）：
     * 1 = L1_SILENT_AI（静默接起 + 留言转文字）
     * 2 = L2_AUDIO_DIALOG（AI 语音对话）
     * 3 = L3_RECORD_ONLY（仅记录）
     */
    int handleMode;

    /**
     * 匹配的策略规则 ID（审计用）
     */
    String ruleId;

    /**
     * 策略引擎决策引用（审计用）
     */
    String policyRef;

    /**
     * 判定原因（用户可见，如"联系人"/"白名单"/"反骚扰模型"）
     */
    String reason;

    /**
     * 是否 baseline 保护（紧急号码/系统号码强制原生响铃）
     */
    boolean baselineGuard;

    /**
     * 开场白文本引用（L1/L2 模式，系统签发，防注入）
     */
    String greetingTextRef;

    /**
     * 判定耗时（ms，性能监控）
     */
    long elapsedMs;
}
