package com.genton.audit;

/**
 * 审计事件（Audit Event）
 * 
 * 哈希链结构：
 * - 每条事件包含 prevHash（前序事件哈希）
 * - eventId = SHA-256(seq + timestamp + category + agentId + summary + payloadHash)
 * - 周期性 TEE 锚定确保链外可独立校验
 * 
 * 不可变性：
 * - 一旦写入，永不修改
 * - 无 update/delete 接口
 * - 隐私保护通过脱敏查询实现
 * 
 * 证据分离：
 * - 事件只存摘要和哈希
 * - 原始证据（截图/轨迹）按保留策略存冷存储
 * - evidenceRefs 指向证据 ID
 */
parcelable AuditEvent {
    /**
     * 序号（服务签发，单调递增）
     * 用途：
     * - 链完整性校验
     * - 时间线排序
     * - 范围查询
     */
    long seq;
    
    /**
     * 事件 ID（内容哈希，防重放/防伪造）
     * 计算：SHA-256(seq + timestamp + category + agentId + summary + payloadHash)
     * 
     * 用途：
     * - 防篡改校验
     * - 事件去重
     */
    String eventId;
    
    /**
     * 时间戳（SystemClock.elapsedRealtimeNanos）
     * 使用 realtimeNanos 而非 currentTimeMillis 防止时间回拨攻击
     */
    long timestampRealtimeNanos;
    
    /**
     * 事件分类（CAT_* 常量）
     * 取值：
     * - CAT_PERCEPTION_READ (1)：感知读取
     * - CAT_ACTION_EXECUTION (2)：动作执行
     * - CAT_MEMORY_ACCESS (3)：记忆访问
     * - CAT_POLICY_DECISION (4)：策略决策
     * - CAT_CAPABILITY_ISSUED (5)：能力授予
     * - CAT_CONFIRMATION (6)：用户确认
     * - CAT_SYSTEM (7)：系统事件
     */
    int category;
    
    /**
     * 关联 Agent ID
     * 
     * 规则：
     * - 空字符串 = 系统行为（非 Agent 触发）
     * - 格式：agent_<uuid>
     * 
     * 脱敏：
     * - USER 视角：不可见（脱敏）
     * - ADMIN_BRAIN 视角：完整 agentId
     */
    String agentId;
    
    /**
     * 关联意图 ID（用户任务锚点）
     * 
     * 用途：
     * - 隐私仪表盘按意图聚类展示
     * - "查看某次任务的所有操作"
     * 
     * 规则：
     * - 空字符串 = 无关联意图（系统后台任务）
     * - 格式：intent_<uuid>
     */
    String intentId;
    
    /**
     * 脱敏摘要（展示/检索用）
     * 
     * 示例：
     * - "读取屏幕内容"
     * - "执行动作: 点击发送按钮"
     * - "策略拒绝: 风险等级过高"
     * - "用户确认: 允许一次"
     * 
     * 脱敏规则：
     * - 不含 PII（联系人姓名/电话/消息内容）
     * - 不含敏感参数值
     * - 可直接展示给用户
     */
    String summary;
    
    /**
     * 原始负载哈希（不存明文）
     * 
     * 用途：
     * - 完整性校验（负载未被篡改）
     * - 司法取证时与原始证据比对
     * 
     * 计算：
     * - 动作执行：SHA-256(actionId + argumentsJson)
     * - 策略决策：SHA-256(ruleId + decision + reason)
     * - 记忆访问：SHA-256(memoryContent)
     */
    String payloadHash;
    
    /**
     * 证据引用（截图/轨迹/UI 快照 ID）
     * 
     * 格式：
     * - screenshot_<timestamp>
     * - uitree_<timestamp>
     * - trace_<actionRequestId>
     * 
     * 存储：
     * - 原始证据存冷存储（/data/system/genton/evidence/）
     * - 按保留策略定期清理
     * - 司法解锁时导出
     * 
     * 脱敏：
     * - USER 视角：不可见
     * - ADMIN_BRAIN 视角：完整引用列表
     */
    String[] evidenceRefs;
    
    /**
     * 前序事件哈希（构成链）
     * 
     * 计算：prevHash = hash(prev event)
     * 
     * 规则：
     * - 首条事件：prevHash = "0000...0000"（64 个 0）
     * - 后续事件：prevHash = SHA-256(prev.eventId)
     * 
     * 校验：
     * - verifyChain 检查每条事件的 prevHash 与前序事件匹配
     */
    String prevHash;
    
    /**
     * 所属 TEE 锚点引用
     * 
     * 格式：anchor_<seq>_<timestamp>
     * 
     * 用途：
     * - 指向最近一次 TEE 锚定
     * - 锚点包含该区间的默克尔根
     * - 锚点签名存 TEE 安全存储
     * 
     * 周期：
     * - 每 N 条事件（如 100 条）
     * - 或每次用户确认操作
     */
    String anchorRef;
}
