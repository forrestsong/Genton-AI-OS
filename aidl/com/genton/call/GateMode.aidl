// GateMode.aidl
package com.genton.call;

/**
 * 接管模式配置
 */
parcelable GateMode {
    /**
     * 接管模式：
     * 0 = OFF（默认，关闭接管）
     * 1 = L1_SILENT_AI（静默接起 + 留言转文字）
     * 2 = L2_AUDIO_DIALOG（AI 语音对话）
     * 3 = L3_RECORD_ONLY（仅记录 + 建议回拨）
     */
    int mode;

    /**
     * 来电方范围：
     * "default" = 默认（所有未知号码）
     * "unknown" = 仅未知号码
     * "prefix:+86138" = 特定号段
     * "group:spam" = 特定分组
     */
    String callerScope;

    /**
     * 开场白文本引用（L1/L2 模式，系统签发，防注入）
     * 如："greeting.default" / "greeting.business"
     */
    String greetingTextRef;

    /**
     * 是否已授权（用户需在设置中逐项授权）
     */
    boolean authorized;

    /**
     * 授权时间（System.currentTimeMillis()）
     */
    long authorizedAtMs;

    /**
     * 日处理配额（防骚扰号刷爆 AI 会话）
     * -1 = 无限制
     */
    int dailyHandleCap;

    /**
     * 今日已处理数量
     */
    int dailyHandleUsed;
}
