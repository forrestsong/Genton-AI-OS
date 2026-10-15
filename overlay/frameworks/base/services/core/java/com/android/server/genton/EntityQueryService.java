package com.android.server.genton;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.genton.semantics.*;
import com.genton.memory.MemoryEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本体统一语义层服务（S4）
 * 
 * 核心功能：
 * 1. 实体查询与图遍历：query/getEntity/getRelated
 * 2. 歧义消解：disambiguate（别名/文本提及 → 实体候选）
 * 3. 上下文预打包：packContext（实体子图 + 相关记忆 + 系统记忆页）
 * 4. 实体级授权：按调用方过滤可见性规则
 * 5. 变更监听：onEntityChanged 驱动缓存失效
 * 6. 图级删除：遗忘联动
 * 
 * @author AI Native OS Team
 * @since A3 Milestone
 */
public class EntityQueryService extends IEntityQueryService.Stub {
    private static final String TAG = "EntityQueryService";

    // 性能目标（P95）
    private static final long TARGET_QUERY_MS = 10;
    private static final long TARGET_PACK_MS = 100;
    private static final long TARGET_DISAMBIGUATE_MS = 50;

    // 子图打包限制
    private static final int MAX_RELATION_DEPTH = 3;
    private static final int MAX_ENTITIES_IN_PACK = 100;
    private static final int MAX_MEMORIES_IN_PACK = 50;

    // 低置信度阈值
    private static final float MIN_CONFIDENCE_THRESHOLD = 0.6f;

    private final Context mContext;
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;

    // 实体存储（内存索引，实际应持久化）
    private final Map<String, Entity> mEntities = new HashMap<>();
    
    // 关系边存储（fromEntityId -> List<RelationEdge>）
    private final Map<String, List<RelationEdge>> mRelations = new HashMap<>();
    
    // 可见性规则（entityId -> List<VisibilityRule>）
    private final Map<String, List<VisibilityRule>> mVisibilityRules = new HashMap<>();
    
    // 实体缓存（LRU）
    private final LinkedHashMap<String, Entity> mEntityCache = new LinkedHashMap<String, Entity>(
        100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entity> eldest) {
            return size() > 100;
        }
    };
    
    // 上下文打包缓存（packId -> ContextPack）
    private final LinkedHashMap<Long, ContextPack> mPackCache = new LinkedHashMap<Long, ContextPack>(
        50, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ContextPack> eldest) {
            return size() > 50;
        }
    };

    // 变更监听器
    private final RemoteCallbackList<IEntityChangeListener> mChangeListeners = new RemoteCallbackList<>();

    // Pack ID 生成器
    private final AtomicLong mPackIdGenerator = new AtomicLong(1);

    // 统计信息
    private long mQueryCount = 0;
    private long mQueryTotalMs = 0;
    private long mCacheHits = 0;
    private long mCacheMisses = 0;

    // 审计埋点
    private AuditInstrumentation mAuditInstrumentation;

    public EntityQueryService(Context context) {
        mContext = context;

        // 创建后台线程
        mHandlerThread = new HandlerThread("EntityQueryService");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        // 初始化审计埋点
        mAuditInstrumentation = new AuditInstrumentation(context);

        Slog.i(TAG, "EntityQueryService initialized");
    }

    /**
     * 启动服务（在 SystemServer 中调用）
     */
    public void start() {
        // 加载持久化数据
        loadPersistedData();

        Slog.i(TAG, "EntityQueryService started");
    }

    @Override
    public Entity[] query(EntityQuery query) {
        long startMs = System.currentTimeMillis();

        // 权限检查
        if (!checkPermission("semantics.query")) {
            Slog.w(TAG, "query denied: missing capability");
            return new Entity[0];
        }

        if (query == null || query.queryText == null) {
            return new Entity[0];
        }

        List<Entity> results = new ArrayList<>();

        synchronized (mEntities) {
            // 简化实现：文本匹配（实际应使用 embedding + 图谱检索融合）
            for (Entity entity : mEntities.values()) {
                // 类型过滤
                if (query.entityType != 0 && entity.entityType != query.entityType) {
                    continue;
                }

                // 文本匹配（label 或 aliases）
                boolean matches = false;
                String queryLower = query.queryText.toLowerCase();
                
                if (entity.label != null && entity.label.toLowerCase().contains(queryLower)) {
                    matches = true;
                }
                
                if (!matches && entity.aliases != null) {
                    for (String alias : entity.aliases) {
                        if (alias != null && alias.toLowerCase().contains(queryLower)) {
                            matches = true;
                            break;
                        }
                    }
                }

                if (matches) {
                    // 授权过滤
                    if (isEntityVisible(entity.entityId)) {
                        results.add(entity);
                    }
                }

                if (results.size() >= query.topK) {
                    break;
                }
            }
        }

        // 审计埋点
        String summary = String.format("查询实体 query='%s' type=%d results=%d",
            query.queryText, query.entityType, results.size());
        mAuditInstrumentation.recordPerceptionRead(
            "entity_query",
            getCallingAgentId(),
            null,
            summary,
            "query_" + System.currentTimeMillis()
        );

        // 统计
        long elapsedMs = System.currentTimeMillis() - startMs;
        mQueryCount++;
        mQueryTotalMs += elapsedMs;

        if (elapsedMs > TARGET_QUERY_MS) {
            Slog.w(TAG, String.format("query slow: %dms (target %dms)", elapsedMs, TARGET_QUERY_MS));
        }

        return results.toArray(new Entity[0]);
    }

    @Override
    public Entity getEntity(String entityId) {
        // 权限检查
        if (!checkPermission("semantics.query")) {
            return null;
        }

        if (entityId == null) {
            return null;
        }

        // 尝试缓存
        Entity cached = mEntityCache.get(entityId);
        if (cached != null) {
            mCacheHits++;
            
            // 授权过滤
            if (!isEntityVisible(entityId)) {
                return null;
            }
            
            return cached;
        }

        mCacheMisses++;

        synchronized (mEntities) {
            Entity entity = mEntities.get(entityId);
            
            if (entity != null) {
                // 授权过滤
                if (!isEntityVisible(entityId)) {
                    return null;
                }
                
                mEntityCache.put(entityId, entity);
            }
            
            return entity;
        }
    }

    @Override
    public RelationEdge[] getRelated(String entityId, int relationType, int limit) {
        // 权限检查
        if (!checkPermission("semantics.query")) {
            return new RelationEdge[0];
        }

        if (entityId == null || limit <= 0) {
            return new RelationEdge[0];
        }

        // 授权过滤：源实体必须可见
        if (!isEntityVisible(entityId)) {
            return new RelationEdge[0];
        }

        List<RelationEdge> results = new ArrayList<>();

        synchronized (mRelations) {
            List<RelationEdge> edges = mRelations.get(entityId);
            if (edges != null) {
                for (RelationEdge edge : edges) {
                    // 关系类型过滤
                    if (relationType != 0 && edge.relationType != relationType) {
                        continue;
                    }

                    // 目标实体授权过滤
                    if (isEntityVisible(edge.toEntityId)) {
                        results.add(edge);
                    }

                    if (results.size() >= limit) {
                        break;
                    }
                }
            }
        }

        return results.toArray(new RelationEdge[0]);
    }

    @Override
    public EntityCandidate[] disambiguate(DisambiguateRequest request) {
        long startMs = System.currentTimeMillis();

        // 权限检查
        if (!checkPermission("semantics.query")) {
            return new EntityCandidate[0];
        }

        if (request == null || request.mentionText == null) {
            return new EntityCandidate[0];
        }

        List<EntityCandidate> candidates = new ArrayList<>();

        synchronized (mEntities) {
            String mentionLower = request.mentionText.toLowerCase();

            for (Entity entity : mEntities.values()) {
                // 授权过滤
                if (!isEntityVisible(entity.entityId)) {
                    continue;
                }

                float score = 0.0f;
                String reason = "";

                // 别名精确匹配
                if (entity.aliases != null) {
                    for (String alias : entity.aliases) {
                        if (alias != null && alias.equalsIgnoreCase(request.mentionText)) {
                            score = 1.0f;
                            reason = "别名精确匹配";
                            break;
                        }
                    }
                }

                // Label 匹配
                if (score == 0.0f && entity.label != null) {
                    if (entity.label.equalsIgnoreCase(request.mentionText)) {
                        score = 0.95f;
                        reason = "名称精确匹配";
                    } else if (entity.label.toLowerCase().contains(mentionLower)) {
                        score = 0.7f;
                        reason = "名称部分匹配";
                    }
                }

                // 上下文共现（简化实现）
                if (score > 0.0f && request.contextEntityIds != null) {
                    for (String contextId : request.contextEntityIds) {
                        if (hasRelation(contextId, entity.entityId)) {
                            score += 0.1f;
                            reason += " + 上下文共现";
                            break;
                        }
                    }
                    score = Math.min(score, 1.0f);
                }

                if (score > 0.0f) {
                    EntityCandidate candidate = new EntityCandidate();
                    candidate.entityId = entity.entityId;
                    candidate.label = entity.label;
                    candidate.entityType = entity.entityType;
                    candidate.score = score;
                    candidate.reason = reason;
                    
                    candidates.add(candidate);
                }
            }
        }

        // 按分数排序
        candidates.sort((a, b) -> Float.compare(b.score, a.score));

        // 限制返回数量
        if (candidates.size() > request.topK) {
            candidates = candidates.subList(0, request.topK);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs > TARGET_DISAMBIGUATE_MS) {
            Slog.w(TAG, String.format("disambiguate slow: %dms (target %dms)", 
                elapsedMs, TARGET_DISAMBIGUATE_MS));
        }

        return candidates.toArray(new EntityCandidate[0]);
    }

    @Override
    public ContextPack packContext(ContextPackRequest request) {
        long startMs = System.currentTimeMillis();

        // 权限检查
        if (!checkPermission("semantics.pack")) {
            return null;
        }

        if (request == null || request.seedEntityIds == null || request.seedEntityIds.length == 0) {
            return null;
        }

        // 参数校验
        if (request.relationDepth > MAX_RELATION_DEPTH) {
            Slog.w(TAG, String.format("packContext: depth %d exceeds limit %d", 
                request.relationDepth, MAX_RELATION_DEPTH));
            return null;
        }

        // 生成 Pack ID
        long packId = mPackIdGenerator.incrementAndGet();

        // 子图遍历（BFS）
        Set<String> visitedEntities = new HashSet<>();
        Set<String> currentLevel = new HashSet<>();
        List<Entity> packEntities = new ArrayList<>();
        List<RelationEdge> packEdges = new ArrayList<>();

        // 初始化：种子实体
        for (String seedId : request.seedEntityIds) {
            if (isEntityVisible(seedId)) {
                currentLevel.add(seedId);
            }
        }

        // BFS 遍历
        for (int depth = 0; depth <= request.relationDepth && !currentLevel.isEmpty(); depth++) {
            Set<String> nextLevel = new HashSet<>();

            for (String entityId : currentLevel) {
                if (visitedEntities.contains(entityId)) {
                    continue;
                }

                visitedEntities.add(entityId);

                // 添加实体
                Entity entity = getEntity(entityId);
                if (entity != null && entity.confidence >= MIN_CONFIDENCE_THRESHOLD) {
                    packEntities.add(entity);

                    // 添加关系边
                    if (depth < request.relationDepth) {
                        RelationEdge[] edges = getRelated(entityId, 0, 50);
                        for (RelationEdge edge : edges) {
                            packEdges.add(edge);
                            nextLevel.add(edge.toEntityId);
                        }
                    }
                }

                // 限制实体数量
                if (packEntities.size() >= MAX_ENTITIES_IN_PACK) {
                    break;
                }
            }

            currentLevel = nextLevel;

            if (packEntities.size() >= MAX_ENTITIES_IN_PACK) {
                break;
            }
        }

        // 获取相关记忆（简化实现：TODO 集成记忆服务）
        MemoryEntry[] memories = new MemoryEntry[0];

        // 系统记忆页摘要（TODO 集成记忆服务）
        String systemMemorySummary = "";
        if (request.includeSystemMemoryPage) {
            systemMemorySummary = "用户偏好：简洁风格；常用设备：Pixel 8 Pro";
        }

        // Token 估算（简化）
        int estimatedTokens = packEntities.size() * 50 + packEdges.size() * 20 + 
            memories.length * 100 + systemMemorySummary.length() / 4;

        // 构建 ContextPack
        ContextPack pack = new ContextPack();
        pack.packId = packId;
        pack.builtAtRealtimeNanos = System.nanoTime();
        pack.entities = packEntities.toArray(new Entity[0]);
        pack.edges = packEdges.toArray(new RelationEdge[0]);
        pack.relevantMemories = memories;
        pack.systemMemorySummary = systemMemorySummary;
        pack.estimatedTokens = estimatedTokens;

        // 缓存
        mPackCache.put(packId, pack);

        // 审计埋点
        String summary = String.format("打包上下文 seeds=%d entities=%d edges=%d tokens=%d task='%s'",
            request.seedEntityIds.length, pack.entities.length, pack.edges.length, 
            estimatedTokens, request.taskDescription);
        mAuditInstrumentation.recordSystemEvent(
            "entity_pack",
            "context_packed",
            summary
        );

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (elapsedMs > TARGET_PACK_MS) {
            Slog.w(TAG, String.format("packContext slow: %dms (target %dms)", 
                elapsedMs, TARGET_PACK_MS));
        }

        Slog.i(TAG, String.format("Context packed: packId=%d entities=%d edges=%d tokens=%d (%dms)",
            packId, pack.entities.length, pack.edges.length, estimatedTokens, elapsedMs));

        return pack;
    }

    @Override
    public int setVisibility(VisibilityRule rule) {
        // 权限检查（需要管理员权限）
        if (!checkPermission("semantics.admin")) {
            return IEntityQueryService.STATUS_DENIED;
        }

        if (rule == null || rule.entityId == null) {
            return IEntityQueryService.STATUS_INVALID_REQ;
        }

        synchronized (mVisibilityRules) {
            List<VisibilityRule> rules = mVisibilityRules.get(rule.entityId);
            if (rules == null) {
                rules = new ArrayList<>();
                mVisibilityRules.put(rule.entityId, rules);
            }

            // 移除旧规则（相同主体）
            rules.removeIf(r -> r.subjectType.equals(rule.subjectType) && 
                               r.subjectId.equals(rule.subjectId));

            // 添加新规则
            rules.add(rule);
        }

        // 通知变更
        notifyEntityChanged(rule.entityId, IEntityQueryService.CHANGE_VISIBILITY);

        Slog.i(TAG, String.format("Visibility rule set: entity=%s subject=%s/%s hidden=%b",
            rule.entityId, rule.subjectType, rule.subjectId, rule.hidden));

        return IEntityQueryService.STATUS_OK;
    }

    @Override
    public VisibilityRule[] getVisibilityRules(String entityId) {
        // 权限检查
        if (!checkPermission("semantics.admin")) {
            return new VisibilityRule[0];
        }

        if (entityId == null) {
            return new VisibilityRule[0];
        }

        synchronized (mVisibilityRules) {
            List<VisibilityRule> rules = mVisibilityRules.get(entityId);
            if (rules == null) {
                return new VisibilityRule[0];
            }
            return rules.toArray(new VisibilityRule[0]);
        }
    }

    @Override
    public int registerChangeListener(IEntityChangeListener listener) {
        if (listener == null) {
            return IEntityQueryService.STATUS_INVALID_REQ;
        }

        mChangeListeners.register(listener);
        Slog.i(TAG, "Entity change listener registered");

        return IEntityQueryService.STATUS_OK;
    }

    @Override
    public int unregisterChangeListener(IEntityChangeListener listener) {
        if (listener == null) {
            return IEntityQueryService.STATUS_INVALID_REQ;
        }

        mChangeListeners.unregister(listener);
        Slog.i(TAG, "Entity change listener unregistered");

        return IEntityQueryService.STATUS_OK;
    }

    @Override
    public SemanticsStats getStats() {
        SemanticsStats stats = new SemanticsStats();
        
        synchronized (mEntities) {
            stats.entityCount = mEntities.size();
        }
        
        synchronized (mRelations) {
            long edgeCount = 0;
            for (List<RelationEdge> edges : mRelations.values()) {
                edgeCount += edges.size();
            }
            stats.relationCount = edgeCount;
        }
        
        stats.assertionCount = 0; // TODO: 集成记忆服务统计
        
        long totalAccess = mCacheHits + mCacheMisses;
        stats.cacheHitRate = totalAccess > 0 ? (mCacheHits * 100 / totalAccess) : 0;
        
        stats.avgQueryMs = mQueryCount > 0 ? (mQueryTotalMs / mQueryCount) : 0;
        
        return stats;
    }

    // ===== 内部方法 =====

    /**
     * 加载持久化数据
     */
    private void loadPersistedData() {
        // TODO: 从数据库加载实体和关系
        Slog.d(TAG, "Persisted data loaded");
    }

    /**
     * 检查实体是否对当前调用方可见
     */
    private boolean isEntityVisible(String entityId) {
        String callerId = getCallingAgentId();
        
        synchronized (mVisibilityRules) {
            List<VisibilityRule> rules = mVisibilityRules.get(entityId);
            if (rules == null) {
                return true; // 默认可见
            }

            for (VisibilityRule rule : rules) {
                // 全局隐藏
                if (rule.subjectId.equals("*") && rule.hidden) {
                    return false;
                }

                // 针对调用方的规则
                if (rule.subjectId.equals(callerId) && rule.hidden) {
                    return false;
                }
            }
        }

        // 敏感实体检查
        Entity entity = mEntities.get(entityId);
        if (entity != null && entity.sensitive) {
            // 仅 trusted_assistant 可见敏感实体
            return callerId.equals("trusted_assistant") || callerId.equals("system");
        }

        return true;
    }

    /**
     * 检查两个实体之间是否有关系
     */
    private boolean hasRelation(String fromEntityId, String toEntityId) {
        synchronized (mRelations) {
            List<RelationEdge> edges = mRelations.get(fromEntityId);
            if (edges != null) {
                for (RelationEdge edge : edges) {
                    if (edge.toEntityId.equals(toEntityId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 通知实体变更
     */
    private void notifyEntityChanged(String entityId, int changeType) {
        mHandler.post(() -> {
            int count = mChangeListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                try {
                    mChangeListeners.getBroadcastItem(i).onEntityChanged(entityId, changeType);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify entity change", e);
                }
            }
            mChangeListeners.finishBroadcast();
        });

        // 失效缓存
        mEntityCache.remove(entityId);
    }

    /**
     * 权限检查
     */
    private boolean checkPermission(String capability) {
        try {
            IPolicyEngineService policyEngine = IPolicyEngineService.Stub.asInterface(
                ServiceManager.getService("policy_engine"));
            
            if (policyEngine != null) {
                String agentId = getCallingAgentId();
                int callingUid = Binder.getCallingUid();
                
                return policyEngine.checkCapability(agentId, capability, callingUid);
            } else {
                Slog.w(TAG, "PolicyEngine not available");
                return false;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to check permission: " + capability, e);
            return false;
        }
    }

    /**
     * 获取调用者 Agent ID
     */
    private String getCallingAgentId() {
        try {
            int callingUid = Binder.getCallingUid();
            
            IAgentRuntimeService runtimeService = IAgentRuntimeService.Stub.asInterface(
                ServiceManager.getService("agent_runtime"));
            
            if (runtimeService != null) {
                String agentId = runtimeService.getAgentIdByUid(callingUid);
                if (agentId != null) {
                    return agentId;
                }
            }
            
            return "uid:" + callingUid;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get calling agent ID", e);
            return "unknown";
        }
    }

    // ===== 公开 API（供系统内部调用）=====

    /**
     * 创建或更新实体
     */
    public void upsertEntity(Entity entity) {
        if (entity == null || entity.entityId == null) {
            return;
        }

        synchronized (mEntities) {
            mEntities.put(entity.entityId, entity);
        }

        notifyEntityChanged(entity.entityId, IEntityQueryService.CHANGE_UPSERT);
        
        Slog.d(TAG, String.format("Entity upserted: id=%s type=%d label=%s",
            entity.entityId, entity.entityType, entity.label));
    }

    /**
     * 删除实体（图级删除）
     */
    public void deleteEntity(String entityId) {
        if (entityId == null) {
            return;
        }

        synchronized (mEntities) {
            mEntities.remove(entityId);
        }

        // 删除相关边
        synchronized (mRelations) {
            mRelations.remove(entityId);
            
            // 删除指向该实体的边
            for (List<RelationEdge> edges : mRelations.values()) {
                edges.removeIf(edge -> edge.toEntityId.equals(entityId));
            }
        }

        notifyEntityChanged(entityId, IEntityQueryService.CHANGE_DELETE);
        
        Slog.i(TAG, String.format("Entity deleted (graph-level): id=%s", entityId));
    }

    /**
     * 添加关系边
     */
    public void addRelation(RelationEdge edge) {
        if (edge == null || edge.fromEntityId == null || edge.toEntityId == null) {
            return;
        }

        synchronized (mRelations) {
            List<RelationEdge> edges = mRelations.get(edge.fromEntityId);
            if (edges == null) {
                edges = new ArrayList<>();
                mRelations.put(edge.fromEntityId, edges);
            }
            edges.add(edge);
        }

        Slog.d(TAG, String.format("Relation added: %s -[%d]-> %s",
            edge.fromEntityId, edge.relationType, edge.toEntityId));
    }
}
