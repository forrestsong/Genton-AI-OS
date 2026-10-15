package com.genton.action;

/**
 * 动作信息
 * 
 * 统一表示系统动作、Skill、DMI、Macro
 * 与 AssetManifest 集成，实现资产与动作注册表统一
 * 
 * 对应任务：A8.2 资产与动作注册表统一
 */
parcelable ActionInfo {
    // 动作 ID（唯一标识）
    String actionId;
    
    // 动作名称
    String name;
    
    // 动作描述
    String description;
    
    // 动作类型：system / skill / dmi / macro
    String actionType;
    
    // 执行器类型（与 AssetManifest.executorType 对齐）
    String executorType;
    
    // 执行器配置（JSON 字符串）
    String executorConfig;
    
    // 关联的资产 ID（如果是资产型动作）
    @nullable String assetId;
    
    // 支持的参数列表（JSON Schema）
    String parametersSchema;
    
    // 所需权限
    String[] requiredPermissions;
    
    // 所需能力令牌
    String[] requiredCapabilities;
    
    // 分类（与 AssetManifest.category 对齐）
    String category;
    
    // 标签（用于搜索和分类）
    String[] tags;
    
    // 是否可用
    boolean enabled;
    
    // 平均执行延迟（毫秒）
    long avgLatencyMs;
    
    // 成功率（0.0-1.0）
    float successRate;
    
    // 注册时间戳
    long registeredAt;
}