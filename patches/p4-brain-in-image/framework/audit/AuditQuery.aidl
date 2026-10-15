package com.genton.audit;

/**
 * 审计查询条件（Audit Query）
 * 
 * 用途：
 * - 隐私仪表盘查询"某段时间/某个意图"的操作
 * - 大脑查询"某个 Agent"的历史行为
 * - 司法取证查询完整审计链
 * 
 * 脱敏保证：
 * - viewerRole 决定返回的字段范围
 * - USER 仅见摘要，ADMIN_BRAIN 见完整信息
 */
parcelable AuditQuery {
    /**
     * 查看者角色（决定脱敏程度）
     * 
     * 取值：
     * - 0 = USER：普通用户视角（脱敏摘要）
     *   - 可见字段：seq, timestamp, category, summary
     *   - 不可见：agentId, payloadHash, evidenceRefs
     * 
     * - 1 = ADMIN_BRAIN：管理员/大脑视角（完整信息）
     *   - 可见全部字段
     *   - 用于调试、取证、系统分析
     * 
     * 权限检查：
     * - USER 角色：任何持有 READ_AUDIT 权限的应用
     * - ADMIN_BRAIN 角色：仅特权大脑和系统服务
     */
    int viewerRole;
    
    /**
     * 起始序号（0 = 从最早）
     * 
     * 用途：
     * - 范围查询
     * - 增量拉取（记录上次查询的最大 seq）
     */
    long seqFrom;
    
    /**
     * 结束序号（0 = 到最新）
     * 
     * 用途：
     * - 范围查询
     * - 链完整性校验特定区间
     */
    long seqTo;

    /** 时间范围（毫秒；0 表示不限） */
    long timeFromMs;
    long timeToMs;
    
    /**
     * Agent ID 过滤（空 = 全部）
     * 
     * 用途：
     * - 查看"某个 Agent"的所有行为
     * - 审计某个 Agent 是否有异常行为
     * 
     * 示例：
     * - "agent_shopping_assistant"
     * - "agent_calendar_bot"
     */
    String agentIdFilter;
    
    /**
     * 事件分类过滤（0 = 全部）
     * 
     * 用途：
     * - 仅查看"动作执行"类事件
     * - 仅查看"用户确认"类事件
     * 
     * 取值：CAT_* 常量
     */
    int categoryFilter;
    
    /**
     * 意图 ID 过滤（空 = 全部）
     * 
     * 用途：
     * - 隐私仪表盘"查看某次任务的所有操作"
     * - 意图聚类展示
     * 
     * 示例：
     * - "intent_book_hotel_20260905_143022"
     * - 展示：截图 → 意图识别 → 动作规划 → 执行 → 确认 → 完成
     */
    String intentIdFilter;
    
    /**
     * 返回数量限制
     * 
     * 默认：200
     * 最大：1000（防止内存溢出）
     * 
     * 分页：
     * - 第一页：seqFrom=0, limit=200
     * - 第二页：seqFrom=上次最大 seq+1, limit=200
     */
    int limit = 200;
}
