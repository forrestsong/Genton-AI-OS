package com.genton.assets;

/**
 * 执行报告（执行中介回灌）
 */
parcelable ExecutionReport {
    String assetId;
    String version;
    int outcome;                    // 0=success 1=failed_asset 2=failed_env 3=fallback_used
    long executionMs;               // 执行耗时（毫秒）
    String failReason;              // 失败原因（如"assertion: page not found"）
    String agentId;                 // Agent标识
    long timestampMs;               // 上报时间戳
}
