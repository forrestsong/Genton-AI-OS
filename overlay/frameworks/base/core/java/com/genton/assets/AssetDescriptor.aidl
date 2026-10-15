package com.genton.assets;

/**
 * 资产描述符（查询返回的轻量视图）
 */
parcelable AssetDescriptor {
    String assetId;
    int kind;                       // KIND_SKILL | KIND_DMI | KIND_MACRO
    String version;
    String name;                    // 工具名
    String description;             // 语义描述
    
    String targetAppPackage;
    String appVersionRange;
    String[] deviceModels;
    
    String capability;              // 能力名
    int riskLevel;                  // 0/1/2
    int status;                     // ASSET_STATUS_*
    
    String[] labels;
    
    // 统计信息
    int hitCount;                   // 命中次数
    float successRate;              // 成功率（0.0-1.0）
    long lastUsedMs;                // 最后使用时间戳
}
