package com.android.server.genton;

import android.content.Context;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本体语义服务端
 * 
 * 核心职责：
 * 1. 实体查询服务（IEntityQueryService）
 * 2. 实体变更监听（EntityChangeListener）
 * 3. 实体级授权（按 Agent 控制实体可见性）
 * 4. 图级删除（级联删除关联实体）
 * 5. packContext 子图打包（为 Agent 打包上下文子图）
 * 
 * 本体模型：
 * - 实体（Entity）：人/地点/事件/物品/组织
 * - 关系（Relation）：实体间的语义关系
 * - 属性（Attribute）：实体的属性键值对
 * 
 * 实体类型：
 * - PERSON：人（姓名/电话/公司/职位）
 * - PLACE：地点（地址/经纬度）
 * - EVENT：事件（时间/地点/参与者）
 * - THING：物品（名称/型号/价格）
 * - ORGANIZATION：组织（名称/行业/规模）
 * 
 * 关系类型：
 * - WORKS_AT：在...工作
 * - LOCATED_IN：位于...
 * - PARTICIPATED_IN：参与...
 * - OWNS：拥有...
 * - RELATED_TO：关联...
 * 
 * 实体级授权：
 * - 每个实体绑定 ownerAgentId
 * - 其他 Agent 需要授权才能访问
 * - 支持 ALLOW/DENY 列表
 * 
 * 图级删除：
 * - 删除实体时级联删除关联关系
 * - 删除关系时不影响实体
 * - 支持软删除（标记删除）和硬删除
 * 
 * packContext 子图打包：
 * - 从种子实体出发，按半径扩展子图
 * - Token 预算裁剪（控制上下文大小）
 * - 与 ACB checkpoint 联动
 * 
 * 对应任务：A5.4
 * 前置依赖：A1.3 (服务骨架), C3.1 (本体 schema)
 * 草案来源：D6
 * 
 * @author AI Native OS Team
 * @since A3 (智能与端云)
 */
public class EntitySemanticService {
    private static final String TAG = "EntitySemanticService";
    
    // 实体类型常量
    public static final String ENTITY_TYPE_PERSON = "person";
    public static final String ENTITY_TYPE_PLACE = "place";
    public static final String ENTITY_TYPE_EVENT = "event";
    public static final String ENTITY_TYPE_THING = "thing";
    public static final String ENTITY_TYPE_ORGANIZATION = "organization";
    
    // 关系类型常量
    public static final String RELATION_WORKS_AT = "works_at";
    public static final String RELATION_LOCATED_IN = "located_in";
    public static final String RELATION_PARTICIPATED_IN = "participated_in";
    public static final String RELATION_OWNS = "owns";
    public static final String RELATION_RELATED_TO = "related_to";
    
    private final Context mContext;
    
    // 实体存储（entityId → Entity）
    private final ConcurrentHashMap<String, Entity> mEntities;
    
    // 关系存储（relationId → Relation）
    private final ConcurrentHashMap<String, Relation> mRelations;
    
    // 实体索引（entityType → Set<entityId>）
    private final ConcurrentHashMap<String, Set<String>> mEntityTypeIndex;
    
    // 关系索引（fromEntityId → Set<relationId>）
    private final ConcurrentHashMap<String, Set<String>> mOutgoingRelations;
    
    // 关系索引（toEntityId → Set<relationId>）
    private final ConcurrentHashMap<String, Set<String>> mIncomingRelations;
    
    // 实体变更监听器
    private final CopyOnWriteArrayList<EntityChangeListener> mChangeListeners;
    
    // 实体 ID 生成器
    private final AtomicLong mEntityIdSequence = new AtomicLong(1);
    private final AtomicLong mRelationIdSequence = new AtomicLong(1);
    
    // 审计埋点
    private final AuditInstrumentation mAuditInstrumentation;
    
    public EntitySemanticService(Context context) {
        mContext = context;
        mEntities = new ConcurrentHashMap<>();
        mRelations = new ConcurrentHashMap<>();
        mEntityTypeIndex = new ConcurrentHashMap<>();
        mOutgoingRelations = new ConcurrentHashMap<>();
        mIncomingRelations = new ConcurrentHashMap<>();
        mChangeListeners = new CopyOnWriteArrayList<>();
        mAuditInstrumentation = new AuditInstrumentation(context);
        
        Slog.i(TAG, "EntitySemanticService initialized");
    }
    
    /**
     * 创建实体
     * 
     * @param request 创建请求
     * @return 实体 ID
     */
    public String createEntity(EntityCreateRequest request) {
        try {
            // 生成实体 ID
            String entityId = generateEntityId();
            
            // 创建实体
            Entity entity = new Entity();
            entity.entityId = entityId;
            entity.entityType = request.entityType;
            entity.name = request.name;
            entity.attributes = request.attributes != null ? new HashMap<>(request.attributes) : new HashMap<>();
            entity.ownerAgentId = request.agentId;
            entity.createdAtMs = System.currentTimeMillis();
            entity.updatedAtMs = entity.createdAtMs;
            entity.deleted = false;
            
            // 存储实体
            mEntities.put(entityId, entity);
            
            // 更新类型索引
            mEntityTypeIndex.computeIfAbsent(entity.entityType, k -> ConcurrentHashMap.newKeySet()).add(entityId);
            
            // 通知监听器
            notifyEntityCreated(entity);
            
            // 审计埋点
            mAuditInstrumentation.recordSystemEvent(
                "entity_semantic",
                "entity_created",
                String.format("创建实体 type=%s name=%s", entity.entityType, entity.name)
            );
            
            Slog.i(TAG, String.format("Entity created: id=%s, type=%s, name=%s",
                entityId, entity.entityType, entity.name));
            
            return entityId;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create entity", e);
            return null;
        }
    }
    
    /**
     * 查询实体
     * 
     * @param query 查询条件
     * @return 实体列表
     */
    public List<Entity> queryEntities(EntityQuery query) {
        List<Entity> results = new ArrayList<>();
        
        try {
            Set<String> candidateIds = new HashSet<>();
            
            // 按类型过滤
            if (query.entityType != null) {
                Set<String> typeIds = mEntityTypeIndex.get(query.entityType);
                if (typeIds != null) {
                    candidateIds.addAll(typeIds);
                }
            } else {
                candidateIds.addAll(mEntities.keySet());
            }
            
            // 遍历候选实体
            for (String entityId : candidateIds) {
                Entity entity = mEntities.get(entityId);
                if (entity == null || entity.deleted) {
                    continue;
                }
                
                // 实体级授权检查
                if (!checkEntityAccess(entity, query.requestAgentId)) {
                    continue;
                }
                
                // 名称过滤
                if (query.namePattern != null && !entity.name.contains(query.namePattern)) {
                    continue;
                }
                
                // 属性过滤
                if (query.attributeFilters != null) {
                    boolean match = true;
                    for (Map.Entry<String, String> filter : query.attributeFilters.entrySet()) {
                        String value = entity.attributes.get(filter.getKey());
                        if (!filter.getValue().equals(value)) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                }
                
                results.add(entity);
                
                if (results.size() >= query.limit) {
                    break;
                }
            }
            
            Slog.d(TAG, String.format("Entity query: %d results", results.size()));
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to query entities", e);
        }
        
        return results;
    }
    
    /**
     * 创建关系
     * 
     * @param request 创建请求
     * @return 关系 ID
     */
    public String createRelation(RelationCreateRequest request) {
        try {
            // 验证实体存在
            Entity fromEntity = mEntities.get(request.fromEntityId);
            Entity toEntity = mEntities.get(request.toEntityId);
            
            if (fromEntity == null || toEntity == null) {
                Slog.w(TAG, "Cannot create relation: entity not found");
                return null;
            }
            
            // 生成关系 ID
            String relationId = generateRelationId();
            
            // 创建关系
            Relation relation = new Relation();
            relation.relationId = relationId;
            relation.relationType = request.relationType;
            relation.fromEntityId = request.fromEntityId;
            relation.toEntityId = request.toEntityId;
            relation.attributes = request.attributes != null ? new HashMap<>(request.attributes) : new HashMap<>();
            relation.createdAtMs = System.currentTimeMillis();
            
            // 存储关系
            mRelations.put(relationId, relation);
            
            // 更新关系索引
            mOutgoingRelations.computeIfAbsent(request.fromEntityId, k -> ConcurrentHashMap.newKeySet()).add(relationId);
            mIncomingRelations.computeIfAbsent(request.toEntityId, k -> ConcurrentHashMap.newKeySet()).add(relationId);
            
            // 通知监听器
            notifyRelationCreated(relation);
            
            Slog.i(TAG, String.format("Relation created: id=%s, type=%s, from=%s, to=%s",
                relationId, relation.relationType, request.fromEntityId, request.toEntityId));
            
            return relationId;
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create relation", e);
            return null;
        }
    }
    
    /**
     * 删除实体（图级删除）
     * 
     * @param entityId 实体 ID
     * @param hardDelete true=硬删除，false=软删除
     * @return 删除的实体和关系数量
     */
    public DeleteResult deleteEntity(String entityId, boolean hardDelete) {
        DeleteResult result = new DeleteResult();
        
        try {
            Entity entity = mEntities.get(entityId);
            if (entity == null) {
                return result;
            }
            
            // 软删除：仅标记删除
            if (!hardDelete) {
                entity.deleted = true;
                entity.deletedAtMs = System.currentTimeMillis();
                result.entitiesDeleted = 1;
                
                // 通知监听器
                notifyEntityDeleted(entity);
                
                Slog.i(TAG, "Entity soft deleted: " + entityId);
                return result;
            }
            
            // 硬删除：级联删除关联关系
            Set<String> relationsToDelete = new HashSet<>();
            
            // 收集出边关系
            Set<String> outgoing = mOutgoingRelations.get(entityId);
            if (outgoing != null) {
                relationsToDelete.addAll(outgoing);
            }
            
            // 收集入边关系
            Set<String> incoming = mIncomingRelations.get(entityId);
            if (incoming != null) {
                relationsToDelete.addAll(incoming);
            }
            
            // 删除关系
            for (String relationId : relationsToDelete) {
                Relation relation = mRelations.remove(relationId);
                if (relation != null) {
                    result.relationsDeleted++;
                    notifyRelationDeleted(relation);
                }
            }
            
            // 清理关系索引
            mOutgoingRelations.remove(entityId);
            mIncomingRelations.remove(entityId);
            
            // 删除实体
            mEntities.remove(entityId);
            
            // 清理类型索引
            if (entity.entityType != null) {
                Set<String> typeIds = mEntityTypeIndex.get(entity.entityType);
                if (typeIds != null) {
                    typeIds.remove(entityId);
                }
            }
            
            result.entitiesDeleted = 1;
            
            // 通知监听器
            notifyEntityDeleted(entity);
            
            // 审计埋点
            mAuditInstrumentation.recordSystemEvent(
                "entity_semantic",
                "entity_deleted",
                String.format("删除实体 id=%s cascade=%d", entityId, result.relationsDeleted)
            );
            
            Slog.i(TAG, String.format("Entity hard deleted: id=%s, cascaded %d relations",
                entityId, result.relationsDeleted));
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to delete entity", e);
        }
        
        return result;
    }
    
    /**
     * 打包上下文子图
     * 
     * @param request 打包请求
     * @return 上下文子图
     */
    public ContextSubgraph packContext(PackContextRequest request) {
        ContextSubgraph subgraph = new ContextSubgraph();
        subgraph.entities = new ArrayList<>();
        subgraph.relations = new ArrayList<>();
        
        try {
            Set<String> visitedEntities = new HashSet<>();
            Set<String> visitedRelations = new HashSet<>();
            
            // BFS 扩展子图
            List<String> currentLayer = new ArrayList<>(request.seedEntityIds);
            int currentRadius = 0;
            int totalTokens = 0;
            
            while (currentRadius < request.maxRadius && !currentLayer.isEmpty()) {
                List<String> nextLayer = new ArrayList<>();
                
                for (String entityId : currentLayer) {
                    if (visitedEntities.contains(entityId)) {
                        continue;
                    }
                    
                    Entity entity = mEntities.get(entityId);
                    if (entity == null || entity.deleted) {
                        continue;
                    }
                    
                    // 实体级授权检查
                    if (!checkEntityAccess(entity, request.requestAgentId)) {
                        continue;
                    }
                    
                    // Token 预算检查
                    int entityTokens = estimateEntityTokens(entity);
                    if (totalTokens + entityTokens > request.tokenBudget) {
                        Slog.d(TAG, "Token budget exceeded, stopping expansion");
                        break;
                    }
                    
                    // 添加实体
                    subgraph.entities.add(entity);
                    visitedEntities.add(entityId);
                    totalTokens += entityTokens;
                    
                    // 扩展关系
                    Set<String> outgoing = mOutgoingRelations.get(entityId);
                    if (outgoing != null) {
                        for (String relationId : outgoing) {
                            if (visitedRelations.contains(relationId)) {
                                continue;
                            }
                            
                            Relation relation = mRelations.get(relationId);
                            if (relation != null) {
                                subgraph.relations.add(relation);
                                visitedRelations.add(relationId);
                                nextLayer.add(relation.toEntityId);
                            }
                        }
                    }
                }
                
                currentLayer = nextLayer;
                currentRadius++;
                
                if (totalTokens >= request.tokenBudget) {
                    break;
                }
            }
            
            subgraph.totalTokens = totalTokens;
            subgraph.radius = currentRadius;
            
            Slog.i(TAG, String.format("Context packed: entities=%d, relations=%d, tokens=%d, radius=%d",
                subgraph.entities.size(), subgraph.relations.size(), totalTokens, currentRadius));
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to pack context", e);
        }
        
        return subgraph;
    }
    
    /**
     * 注册实体变更监听器
     */
    public void registerChangeListener(EntityChangeListener listener) {
        if (listener != null && !mChangeListeners.contains(listener)) {
            mChangeListeners.add(listener);
            Slog.d(TAG, "Change listener registered");
        }
    }
    
    /**
     * 取消注册实体变更监听器
     */
    public void unregisterChangeListener(EntityChangeListener listener) {
        if (listener != null) {
            mChangeListeners.remove(listener);
            Slog.d(TAG, "Change listener unregistered");
        }
    }
    
    /**
     * 获取统计信息
     */
    public EntityStats getStats() {
        EntityStats stats = new EntityStats();
        stats.totalEntities = mEntities.size();
        stats.totalRelations = mRelations.size();
        stats.entityTypes = mEntityTypeIndex.size();
        return stats;
    }
    
    // ========== 内部方法 ==========
    
    /**
     * 实体级授权检查
     */
    private boolean checkEntityAccess(Entity entity, String requestAgentId) {
        // 所有者始终有权限
        if (entity.ownerAgentId != null && entity.ownerAgentId.equals(requestAgentId)) {
            return true;
        }
        
        // TODO: 实现 ALLOW/DENY 列表
        // 当前：允许所有 Agent 访问
        return true;
    }
    
    /**
     * 估算实体的 Token 数量
     */
    private int estimateEntityTokens(Entity entity) {
        // 简单估算：名称 + 属性数量 * 10
        int tokens = entity.name != null ? entity.name.length() / 4 : 0;
        tokens += entity.attributes.size() * 10;
        return Math.max(10, tokens);
    }
    
    /**
     * 生成实体 ID
     */
    private String generateEntityId() {
        long seq = mEntityIdSequence.getAndIncrement();
        long timestamp = System.currentTimeMillis();
        return String.format("entity_%d_%d", timestamp, seq);
    }
    
    /**
     * 生成关系 ID
     */
    private String generateRelationId() {
        long seq = mRelationIdSequence.getAndIncrement();
        long timestamp = System.currentTimeMillis();
        return String.format("rel_%d_%d", timestamp, seq);
    }
    
    /**
     * 通知实体创建
     */
    private void notifyEntityCreated(Entity entity) {
        for (EntityChangeListener listener : mChangeListeners) {
            try {
                listener.onEntityCreated(entity);
            } catch (Exception e) {
                Slog.e(TAG, "Listener failed on entity created", e);
            }
        }
    }
    
    /**
     * 通知实体删除
     */
    private void notifyEntityDeleted(Entity entity) {
        for (EntityChangeListener listener : mChangeListeners) {
            try {
                listener.onEntityDeleted(entity);
            } catch (Exception e) {
                Slog.e(TAG, "Listener failed on entity deleted", e);
            }
        }
    }
    
    /**
     * 通知关系创建
     */
    private void notifyRelationCreated(Relation relation) {
        for (EntityChangeListener listener : mChangeListeners) {
            try {
                listener.onRelationCreated(relation);
            } catch (Exception e) {
                Slog.e(TAG, "Listener failed on relation created", e);
            }
        }
    }
    
    /**
     * 通知关系删除
     */
    private void notifyRelationDeleted(Relation relation) {
        for (EntityChangeListener listener : mChangeListeners) {
            try {
                listener.onRelationDeleted(relation);
            } catch (Exception e) {
                Slog.e(TAG, "Listener failed on relation deleted", e);
            }
        }
    }
    
    // ========== 数据结构 ==========
    
    public static class Entity {
        public String entityId;
        public String entityType;
        public String name;
        public Map<String, String> attributes;
        public String ownerAgentId;
        public long createdAtMs;
        public long updatedAtMs;
        public boolean deleted;
        public long deletedAtMs;
    }
    
    public static class Relation {
        public String relationId;
        public String relationType;
        public String fromEntityId;
        public String toEntityId;
        public Map<String, String> attributes;
        public long createdAtMs;
    }
    
    public static class EntityCreateRequest {
        public String agentId;
        public String entityType;
        public String name;
        public Map<String, String> attributes;
    }
    
    public static class EntityQuery {
        public String requestAgentId;
        public String entityType;
        public String namePattern;
        public Map<String, String> attributeFilters;
        public int limit = 100;
    }
    
    public static class RelationCreateRequest {
        public String relationType;
        public String fromEntityId;
        public String toEntityId;
        public Map<String, String> attributes;
    }
    
    public static class PackContextRequest {
        public String requestAgentId;
        public List<String> seedEntityIds;
        public int maxRadius = 2;
        public int tokenBudget = 4000;
    }
    
    public static class ContextSubgraph {
        public List<Entity> entities;
        public List<Relation> relations;
        public int totalTokens;
        public int radius;
    }
    
    public static class DeleteResult {
        public int entitiesDeleted;
        public int relationsDeleted;
    }
    
    public static class EntityStats {
        public int totalEntities;
        public int totalRelations;
        public int entityTypes;
    }
    
    /**
     * 实体变更监听器接口
     */
    public interface EntityChangeListener {
        void onEntityCreated(Entity entity);
        void onEntityDeleted(Entity entity);
        void onRelationCreated(Relation relation);
        void onRelationDeleted(Relation relation);
    }
}
