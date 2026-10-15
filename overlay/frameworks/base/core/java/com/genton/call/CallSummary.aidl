// CallSummary.aidl
package com.genton.call;

/**
 * 通话摘要（本地生成）
 */
parcelable CallSummary {
    /**
     * 摘要文本（用户可见，不落长期记忆除非用户要求）
     */
    String summaryText;

    /**
     * 是否建议回拨
     */
    boolean callbackSuggested;

    /**
     * 回拨号码（脱敏显示）
     */
    String callbackNumberMasked;

    /**
     * 使用的接管模式（1=L1, 2=L2, 3=L3）
     */
    int handleModeUsed;

    /**
     * 审计引用（"查看本次接管详情"）
     */
    String auditRef;

    /**
     * 通话时长（秒）
     */
    int durationSec;

    /**
     * 转写字数（用于统计）
     */
    int transcriptWordCount;

    /**
     * 摘要生成时间（System.currentTimeMillis()）
     */
    long generatedAtMs;
}
