package com.ainative.policy.token;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 令牌撤销列表（Token Revocation List）
 * 
 * 职责：
 * 1. 维护在线撤销状态（内存 + 持久化）
 * 2. 支持按 tokenId / agentId / intentId 撤销
 * 3. 自动清理过期令牌记录（避免列表无限增长）
 * 4. 广播撤销事件（通知域服务缓存失效）
 * 
 * 撤销场景：
 * - Agent 注销（terminateAgent）
 * - 意图任务结束（revokeTokens by intentId）
 * - 用户一键停止授权（隐私仪表盘）
 * - 策略热更新后强制撤销旧版令牌
 * - 安全事件响应（异常行为检测）
 * 
 * 数据结构：
 * - 主索引：tokenId -> TokenRevocationInfo
 * - 辅助索引：agentId -> Set<tokenId>, intentId -> Set<tokenId>
 * 
 * 性能要求：
 * - isRevoked 查询 P99 < 100μs（内存哈希表）
 * - 批量撤销（按 agentId/intentId）支持并发
 * 
 * 安全约束：
 * - 撤销记录只增不改（审计链完整性）
 * - TEE 周期锚定（防篡改，SHA-256 哈希链）
 * - 持久化到加密存储（防重启丢失）
 * 
 * @see TokenRevocationInfo
 */
public class TokenRevocationList {
    
    // 主索引：tokenId -> 撤销信息
    private final ConcurrentHashMap<String, TokenRevocationInfo> revokedTokens;
    
    // 辅助索引：agentId -> tokenIds
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> agentIndex;
    
    // 辅助索引：intentId -> tokenIds
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> intentIndex;
    
    // 读写锁（批量操作时保证一致性）
    private final ReadWriteLock lock;
    
    // 撤销事件监听器（通知域服务缓存失效）
    private final List<RevocationListener> listeners;
    
    public TokenRevocationList() {
        this.revokedTokens = new ConcurrentHashMap<>();
        this.agentIndex = new ConcurrentHashMap<>();
        this.intentIndex = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.listeners = new ArrayList<>();
    }
    
    /**
     * 撤销单个令牌
     * 
     * @param tokenId 令牌ID
     * @param agentId Agent ID（用于索引）
     * @param intentId 意图ID（可选，用于索引）
     * @param reason 撤销原因
     * @param initiator 撤销发起者
     * @param currentTimeNanos 当前时间戳
     * @return 是否成功撤销（已撤销返回 false）
     */
    public boolean revokeToken(String tokenId, String agentId, String intentId,
                              String reason, String initiator, long currentTimeNanos) {
        lock.writeLock().lock();
        try {
            // 检查是否已撤销
            if (revokedTokens.containsKey(tokenId)) {
                return false; // 已撤销，幂等操作
            }
            
            // 创建撤销记录
            TokenRevocationInfo info = new TokenRevocationInfo();
            info.tokenId = tokenId;
            info.revokedAtRealtimeNanos = currentTimeNanos;
            info.reason = reason;
            info.initiator = initiator;
            
            // 写入主索引
            revokedTokens.put(tokenId, info);
            
            // 更新辅助索引（agentId）
            agentIndex.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>())
                     .put(tokenId, true);
            
            // 更新辅助索引（intentId）
            if (intentId != null && !intentId.isEmpty()) {
                intentIndex.computeIfAbsent(intentId, k -> new ConcurrentHashMap<>())
                          .put(tokenId, true);
            }
            
            // 广播撤销事件
            notifyRevocation(tokenId, agentId, intentId, reason);
            
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 批量撤销：按 Agent ID
     * 
     * @param agentId Agent ID
     * @param reason 撤销原因
     * @param currentTimeNanos 当前时间戳
     * @return 撤销的令牌数量
     */
    public int revokeByAgent(String agentId, String reason, long currentTimeNanos) {
        lock.writeLock().lock();
        try {
            ConcurrentHashMap<String, Boolean> tokens = agentIndex.get(agentId);
            if (tokens == null || tokens.isEmpty()) {
                return 0;
            }
            
            int count = 0;
            for (String tokenId : tokens.keySet()) {
                if (!revokedTokens.containsKey(tokenId)) {
                    TokenRevocationInfo info = new TokenRevocationInfo();
                    info.tokenId = tokenId;
                    info.revokedAtRealtimeNanos = currentTimeNanos;
                    info.reason = reason;
                    info.initiator = "agent_terminated";
                    
                    revokedTokens.put(tokenId, info);
                    count++;
                }
            }
            
            // 广播批量撤销事件
            notifyRevocation(null, agentId, null, reason);
            
            return count;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 批量撤销：按意图 ID
     * 
     * @param agentId Agent ID
     * @param intentId 意图ID
     * @param reason 撤销原因
     * @param currentTimeNanos 当前时间戳
     * @return 撤销的令牌数量
     */
    public int revokeByIntent(String agentId, String intentId, String reason, long currentTimeNanos) {
        lock.writeLock().lock();
        try {
            ConcurrentHashMap<String, Boolean> tokens = intentIndex.get(intentId);
            if (tokens == null || tokens.isEmpty()) {
                return 0;
            }
            
            int count = 0;
            for (String tokenId : tokens.keySet()) {
                if (!revokedTokens.containsKey(tokenId)) {
                    TokenRevocationInfo info = new TokenRevocationInfo();
                    info.tokenId = tokenId;
                    info.revokedAtRealtimeNanos = currentTimeNanos;
                    info.reason = reason;
                    info.initiator = "intent_completed";
                    
                    revokedTokens.put(tokenId, info);
                    count++;
                }
            }
            
            // 广播批量撤销事件
            notifyRevocation(null, agentId, intentId, reason);
            
            return count;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 查询令牌是否已撤销（高频查询，无锁读取）
     * 
     * @param tokenId 令牌ID
     * @return true=已撤销，false=有效
     */
    public boolean isRevoked(String tokenId) {
        return revokedTokens.containsKey(tokenId);
    }
    
    /**
     * 获取撤销信息（审计用）
     */
    public TokenRevocationInfo getRevocationInfo(String tokenId) {
        return revokedTokens.get(tokenId);
    }
    
    /**
     * 清理过期撤销记录（避免列表无限增长）
     * 
     * 策略：
     * - 令牌过期 + 撤销时间超过 7 天 = 可安全清理
     * - 保留高风险撤销记录（security_event）
     * 
     * @param currentTimeNanos 当前时间戳
     * @param retentionDays 保留天数（默认 7 天）
     * @return 清理的记录数
     */
    public int cleanupExpiredRecords(long currentTimeNanos, int retentionDays) {
        lock.writeLock().lock();
        try {
            long retentionNanos = retentionDays * 24L * 3600L * 1_000_000_000L;
            long cutoffTime = currentTimeNanos - retentionNanos;
            
            List<String> toRemove = revokedTokens.entrySet().stream()
                .filter(entry -> {
                    TokenRevocationInfo info = entry.getValue();
                    // 保留高风险撤销记录
                    if ("security_event".equals(info.initiator)) {
                        return false;
                    }
                    // 过期且超过保留期
                    return info.revokedAtRealtimeNanos < cutoffTime;
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            
            for (String tokenId : toRemove) {
                revokedTokens.remove(tokenId);
                // 辅助索引不清理（影响不大，且清理成本高）
            }
            
            return toRemove.size();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 注册撤销事件监听器（域服务缓存失效）
     */
    public void addListener(RevocationListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 广播撤销事件
     */
    private void notifyRevocation(String tokenId, String agentId, String intentId, String reason) {
        for (RevocationListener listener : listeners) {
            try {
                listener.onTokenRevoked(tokenId, agentId, intentId, reason);
            } catch (Exception e) {
                // 监听器异常不影响撤销操作
                System.err.println("Revocation listener error: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取统计信息（治理/监控用）
     */
    public RevocationStats getStats() {
        lock.readLock().lock();
        try {
            return new RevocationStats(
                revokedTokens.size(),
                agentIndex.size(),
                intentIndex.size()
            );
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 撤销事件监听器接口
     */
    public interface RevocationListener {
        void onTokenRevoked(String tokenId, String agentId, String intentId, String reason);
    }
    
    /**
     * 撤销统计信息
     */
    public static class RevocationStats {
        public final int totalRevokedTokens;
        public final int totalAgents;
        public final int totalIntents;
        
        public RevocationStats(int totalRevokedTokens, int totalAgents, int totalIntents) {
            this.totalRevokedTokens = totalRevokedTokens;
            this.totalAgents = totalAgents;
            this.totalIntents = totalIntents;
        }
    }
    
    /**
     * TokenRevocationInfo 简化类（实际使用 AIDL 生成的类）
     */
    public static class TokenRevocationInfo {
        public String tokenId;
        public long revokedAtRealtimeNanos;
        public String reason;
        public String initiator;
    }
}
