// TranscriptDelta.aidl
package com.genton.call;

/**
 * 转写增量（本地 ASR 结果）
 */
parcelable TranscriptDelta {
    /**
     * 转写文本（ASR 增量）
     */
    String text;

    /**
     * 说话人：
     * false = 来电方
     * true = AI（TTS）
     */
    boolean speaker;

    /**
     * 通道：
     * 0 = live（实时对话）
     * 1 = voicemail_style（留言风格）
     */
    int channel;

    /**
     * 时间戳（相对于会话开始，ms）
     */
    long offsetMs;

    /**
     * 是否为最终结果（ASR 可能有中间结果）
     */
    boolean isFinal;
}
