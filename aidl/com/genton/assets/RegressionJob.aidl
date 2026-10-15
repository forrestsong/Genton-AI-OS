package com.genton.assets;

/**
 * 回归测试任务
 */
parcelable RegressionJob {
    String jobId;                   // 任务ID
    int scope;                      // 0=单资产 1=本机全部启用资产
    int progress;                   // 0~100
    int passCount;                  // 通过数量
    int failCount;                  // 失败数量
    String[] failedAssetIds;        // 失败的资产ID列表
    long startTimeMs;               // 开始时间戳
    long estimatedEndTimeMs;        // 预计完成时间戳
}
