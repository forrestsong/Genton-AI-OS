package com.genton.perception;

import com.genton.perception.UiNode;
import com.genton.perception.UiTreeDiff;

/**
 * UiSnapshot - UI 树快照
 *
 * 服务端实现：AgentUiTreeService（captureViaPrimary / captureViaFallback /
 * createErrorSnapshot）
 *
 * 语义（与实现一致）：
 * - 出错快照：sequenceId = -1，看 statusCode / errorMessage；
 * - captureSource 取 "wm"（WindowManager 主通道）或 "accessibility"（回退通道）；
 * - isIncremental 为 true 时 incrementalDiff 有效；
 * - 时间戳一律使用单调时钟 System.nanoTime()。
 */
parcelable UiSnapshot {
    /** 快照序号（单调递增；出错快照为 -1） */
    long sequenceId;

    /** 捕获时间戳（单调时钟纳秒） */
    long timestampNanos;

    /** UI 树根节点 */
    UiNode rootNode;

    /** 捕获耗时（纳秒） */
    long captureLatencyNanos;

    /** 捕获通道（"wm" / "accessibility"） */
    String captureSource;

    /** 状态码（AgentUiTreeService.STATUS_*） */
    int statusCode;

    /** 错误信息（仅出错快照有效） */
    String errorMessage;

    /** 增量差异（isIncremental 为 true 时有效） */
    UiTreeDiff incrementalDiff;

    /** 是否增量快照 */
    boolean isIncremental;
}
