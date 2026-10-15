package com.ainative.semantics;

import com.ainative.semantics.Entity;
import com.ainative.semantics.EntityQuery;
import com.ainative.semantics.RelationEdge;
import com.ainative.semantics.DisambiguateRequest;
import com.ainative.semantics.EntityCandidate;
import com.ainative.semantics.ContextPackRequest;
import com.ainative.semantics.ContextPack;
import com.ainative.semantics.VisibilityRule;
import com.ainative.semantics.SemanticsStats;
import com.ainative.semantics.IEntityChangeListener;

/**
 * 本体统一语义层服务（S4）
 * 
 * 统一物化记忆、通知、日历、联系人、文件索引、前台上下文为"实体 + 关系"，
 * 提供本体查询、歧义消解、任务上下文预打包、实体级授权过滤。
 * 
 * 红线：
 * 1. 所有查询在服务端按实体级授权过滤后才返回
 * 2. 一切读/打包走审计
 * 3. 敏感实体类型（金融/健康/账号）默认仅 trusted_assistant 可见
 */
interface IEntityQueryService {
    const int STATUS_OK            = 0;
    const int STATUS_DENIED        = 1;   // 无能力令牌 / 触及隐藏或敏感实体
    const int STATUS_NOT_FOUND     = 2;
    const int STATUS_INVALID_REQ   = 3;   // 参数不合法（深度/数量超限等）
    const int STATUS_INTERNAL_ERROR = -1;

    // —— 实体类型（ET_*）——
    const int ET_PERSON        = 1;
    const int ET_ORGANIZATION  = 2;
    const int ET_EVENT         = 3;   // 日历/日程
    const int ET_TASK          = 4;
    const int ET_PLACE         = 5;
    const int ET_MEDIA         = 6;
    const int ET_DOCUMENT      = 7;
    const int ET_APP           = 8;
    const int ET_AGENT_TASK    = 9;
    const int ET_CONVERSATION  = 10;
    const int ET_DEVICE        = 11;
    const int ET_ACCOUNT       = 12;  // 敏感类：默认受限

    // —— 关系类型（REL_*，0=不限）——
    const int REL_INVOLVES     = 1;   // 人/组织 参与 事件/任务
    const int REL_ABOUT        = 2;   // 文档/任务 关于 实体
    const int REL_CREATED_BY   = 3;
    const int REL_MENTIONS     = 4;   // 对话/文档 提及 实体
    const int REL_BELONGS_TO   = 5;   // 成员/从属
    const int REL_SCHEDULED_AT = 6;   // 事件 排期 于 时间（时间存属性）
    const int REL_LOCATED_AT   = 7;   // 地点关联
    const int REL_REFERENCES   = 8;   // 引用（文档↔文档/任务）
    const int REL_ASSIGNED_TO  = 9;   // 任务 指派 人/Agent

    // —— 变更类型（监听回调用）——
    const int CHANGE_UPSERT      = 0;
    const int CHANGE_DELETE      = 1;
    const int CHANGE_VISIBILITY  = 2;  // 实体被隐藏/恢复（触发上下文缓存失效）

    /**
     * 语义+过滤查询（服务端已按调用方授权过滤；includeRelations=true 时附带一跳边）
     */
    Entity[] query(in EntityQuery query);

    /**
     * 获取单个实体
     */
    Entity getEntity(in String entityId);

    /**
     * 一跳关系查询（供"和 X 相关的任务/文档"图遍历）
     */
    RelationEdge[] getRelated(in String entityId, in int relationType, in int limit);

    /**
     * 别名/文本提及 → 实体候选（上下文预打包与实体抽取的歧义消解）
     */
    EntityCandidate[] disambiguate(in DisambiguateRequest request);

    /**
     * 任务启动的上下文预打包（S4 核心：实体子图 + 相关记忆 + 系统记忆页，一次取齐）
     */
    ContextPack packContext(in ContextPackRequest request);

    // —— 实体级授权（由大脑/隐私仪表盘调用）——
    
    /**
     * 设置可见性规则
     */
    int setVisibility(in VisibilityRule rule);
    
    /**
     * 获取实体的可见性规则
     */
    VisibilityRule[] getVisibilityRules(in String entityId);

    /**
     * 订阅实体变更（驱动记忆页/上下文缓存失效）
     */
    int registerChangeListener(in IEntityChangeListener listener);
    
    /**
     * 取消订阅
     */
    int unregisterChangeListener(in IEntityChangeListener listener);

    /**
     * 获取统计信息
     */
    SemanticsStats getStats();
}
