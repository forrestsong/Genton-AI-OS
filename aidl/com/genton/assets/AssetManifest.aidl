package com.genton.assets;

/**
 * 资产清单
 * 
 * 定义资产的元数据、目标平台、能力声明、执行配置
 */
parcelable AssetManifest {
    String id;                      // 资产唯一ID（如 "m.weather.open_today"）
    int kind;                       // KIND_SKILL | KIND_DMI | KIND_MACRO
    String version;                 // 资产语义版本（独立于App版本）
    
    // 目标平台
    String targetAppPackage;        // 目标App包名（skill可为空=系统级）
    String appVersionRange;         // App版本范围（如 ">=4.2, <5"）dmi/macro必填
    String[] deviceModels;          // 机型白名单（空=不限）
    String osRange;                 // OS版本范围（如 ">=A-0.4"）
    
    // 能力与风险
    String capability;              // 须已在动作注册表登记的能力名
    int riskLevel;                  // 0/1/2（0=无副作用）
    int confirmation;               // 0=default 1=always（由策略最终裁决）
    
    // 执行配置
    int executor;                   // KIND_SKILL | KIND_DMI | KIND_MACRO
    int executionMode;              // 0=semantic_direct(免渲染直连) 1=macro_inject(有参宏，需渲染目标)
    String artifactType;            // compiled_macro | dmi_binding | skill_bundle
    String checksum;                // sha256-...
    String signatureRef;            // 平台/开发者签名引用
    
    // Schema与降级
    String inputSchemaJson;         // 输入JSON Schema
    String outputSchemaJson;        // 输出JSON Schema
    String[] fallbacks;             // 降级链（资产ID列表）
    
    // 元数据
    String[] labels;                // 标签（如 weather, high_frequency）
    String description;             // 工具语义描述
    String name;                    // 工具名（模型调用名）
}
