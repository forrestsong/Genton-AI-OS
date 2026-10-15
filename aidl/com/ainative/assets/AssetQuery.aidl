package com.ainative.assets;

/**
 * 资产查询条件
 */
parcelable AssetQuery {
    int kind;                       // 0=不限 或 KIND_*
    String targetApp;               // 包名过滤（空=系统技能）
    String deviceModel;             // 机型过滤（空=不限）
    String[] labels;                // 标签过滤（空=不限）
    String semanticQuery;           // 自然语言检索（如"给今天天气"）
    int topK;                       // 返回数量上限（默认20）
    boolean onlyEnabled;            // 只返回enabled状态（默认true）
}
