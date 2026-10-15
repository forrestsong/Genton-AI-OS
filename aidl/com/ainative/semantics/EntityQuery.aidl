package com.ainative.semantics;

/**
 * 实体查询请求
 */
parcelable EntityQuery {
    /**
     * 自然语言语义查询（端侧 embedding + 图谱检索融合）
     */
    String queryText;
    
    /**
     * 实体类型过滤（0 = 不限）
     */
    int entityType;
    
    /**
     * 标签过滤
     */
    String[] tagFilters;
    
    /**
     * 返回结果数量上限
     */
    int topK = 20;
    
    /**
     * 是否附带一跳 RelationEdge（用于图预览）
     */
    boolean includeRelations;
}
