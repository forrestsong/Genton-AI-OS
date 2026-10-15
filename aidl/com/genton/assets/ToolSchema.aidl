package com.genton.assets;

/**
 * 工具Schema（供Agent调用）
 */
parcelable ToolSchema {
    String assetId;                 // 资产ID
    String name;                    // 工具名（模型调用名，如 weather.open_today）
    String description;             // 工具语义描述
    String inputSchemaJson;         // 输入JSON Schema
    String outputSchemaJson;        // 输出JSON Schema
    String capability;              // 需要的系统能力
    int riskLevel;                  // 0/1/2
    int kind;                       // KIND_SKILL | KIND_DMI | KIND_MACRO
}
