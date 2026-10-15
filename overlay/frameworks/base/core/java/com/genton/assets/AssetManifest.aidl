// AssetManifest.aidl
package com.genton.assets;

/**
 * 资产清单
 * 
 * 统一描述 Skill/DMI/Macro 三类资产
 */
parcelable AssetManifest {
    // 唯一 ID（由系统生成）
    String assetId;
    
    // 资产类型：skill / dmi / macro
    String assetType;
    
    // 资产名称
    String name;
    
    // 资产描述
    String description;
    
    // 版本号（SemVer 格式，如 "1.2.3"）
    String version;
    
    // 资产分类（如 "weather", "calendar", "communication"）
    String category;
    
    // 支持的意图动作列表（如 ["查天气", "明天天气"]）
    String[] supportedIntents;
    
    // 执行器类型（skill/dmi/macro，用于动作注册表统一）
    String executorType;
    
    // 执行器配置（JSON 字符串）
    String executorConfig;
    
    // 所需权限列表
    String[] requiredPermissions;
    
    // 所需能力令牌
    String[] requiredCapabilities;
    
    // 发布者信息
    String publisher;
    String publisherPackage;
    
    // 绑定的 Android SDK 版本范围
    int minSdkVersion;
    int maxSdkVersion;
    
    // 当前状态：registered / enabled / disabled / suspended
    String state;
    
    // 灰度比例（0.0-1.0）
    float stagedRatio;
    
    // 注册时间戳
    long registeredAt;
    
    // 最后更新时间戳
    long updatedAt;
    
    // 扩展元数据（JSON 字符串）
    String metadata;
}
