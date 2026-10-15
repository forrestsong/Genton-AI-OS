package com.ainative.assets;

/**
 * 资产变更信息
 */
parcelable AssetUpdateInfo {
    String assetId;
    int changeType;                 // 0=added 1=updated 2=disabled 3=enabled 4=removed
    String version;                 // 新版本号
    int newStatus;                  // 新状态
    String reason;                  // 变更原因（如"版本漂移"/"失败自动停用"）
    long timestampMs;               // 变更时间戳
}
