// AssetQuery.aidl
package com.ainative.assets;

/**
 * 资产查询条件
 */
parcelable AssetQuery {
    // 按类型过滤（null = 全部）
    @nullable String assetType;
    
    // 按分类过滤
    @nullable String category;
    
    // 按意图动作过滤
    @nullable String intentAction;
    
    // 按状态过滤
    @nullable String state;
    
    // 按发布者过滤
    @nullable String publisherPackage;
    
    // 版本约束（SemVer 范围，如 ">=1.0.0 <2.0.0"）
    @nullable String versionRange;
    
    // 限制返回数量（0 = 不限制）
    int limit = 0;
    
    // 偏移量（用于分页）
    int offset = 0;
    
    // 排序字段：registeredAt / updatedAt / name / version
    String sortBy = "updatedAt";
    
    // 排序方向：asc / desc
    String sortOrder = "desc";
}
