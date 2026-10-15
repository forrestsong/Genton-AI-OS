// DecisionResult.aidl — 决策结果（B5.3）
// prefill-only 输出：类型化决策 + 概率，不生成自由文本
package com.genton.decision;

/** @hide */
parcelable DecisionResult {
    String scenario;

    // Choice: 选中候选 key；Noul: "true"/"false"（配合 confidence=P(true)）；Score: 期望等级（可小数）
    String selected;
    // Choice: 命中概率；Noul: P(true)；Score: 概率加权期望分
    float confidence;
    // 全候选概率（与 request.candidates 对齐）
    String[] probabilities;

    // 溯源与计量
    int modelVersion;
    String backend;              // "local" | "cloud" | "rule"（降级路径标识）
    long latencyMicros;
    long tokenUsed;

    // 状态：0=OK 1=MODEL_UNAVAILABLE 2=THRESHOLD_UNSET 3=BUDGET_EXCEEDED 4=CLOUD_UNAVAILABLE 5=TIMEOUT
    int statusCode;
    String fallbackReason;       // 低置信/降级原因（供 fail-safe 决策）
}
