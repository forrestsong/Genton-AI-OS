package com.genton.audit;

import com.genton.audit.AuditEvent;
import com.genton.audit.AuditQuery;
import com.genton.audit.ChainStatus;
import com.genton.audit.ActivityIndicator;
import com.genton.audit.IAuditListener;

/**
 * 审计服务接口（Audit Service）
 * 
 * 职责：
 * 1. 防篡改审计链写入（哈希链 + TEE 周期锚定）
 * 2. 按 viewerRole 脱敏查询
 * 3. 链完整性校验
 * 4. 活性流订阅（驱动 SystemUI 指示器）
 * 
 * 安全约束：
 * - 写权限：仅系统服务和特权大脑（signature|privileged）
 * - 读权限：按 viewerRole 脱敏（USER 仅见摘要，ADMIN_BRAIN 见完整）
 * - 不可变：无 update/delete 接口
 * - TEE 锚定：周期性锚点写入安全存储
 */
interface IAuditService {
    // ========== 返回码 ==========
    
    const int STATUS_OK             = 0;
    const int STATUS_DENIED         = 1;   // 写权限不足/读权限不足
    const int STATUS_INVALID_EVENT  = 2;   // category/schema 未注册
    const int STATUS_INTERNAL_ERROR = -1;
    
    // ========== 事件分类（7 大类） ==========
    
    /**
     * 感知读取：读屏/UI 树/通知/上下文
     * 
     * 典型事件：
     * - UI 树捕获（agentId + 时间戳）
     * - 截图捕获（含掩码标记）
     * - 通知读取（通知内容哈希）
     * - 上下文订阅（订阅项列表）
     */
    const int CAT_PERCEPTION_READ   = 1;
    
    /**
     * 动作执行：提交/成功/失败/取消
     * 
     * 典型事件：
     * - submitAction（actionId + 参数哈希 + intentId）
     * - 执行成功（结果哈希）
     * - 执行失败（错误码）
     * - 用户取消
     */
    const int CAT_ACTION_EXECUTION  = 2;
    
    /**
     * 记忆访问：read/write/forget
     * 
     * 典型事件：
     * - 记忆写入（内容哈希 + provenance）
     * - 记忆查询（查询条件 + 命中数）
     * - 忘记操作（实体 ID + 删除数量）
     */
    const int CAT_MEMORY_ACCESS     = 3;
    
    /**
     * 策略决策：令牌发放/拒绝/撤销
     * 
     * 典型事件：
     * - evaluateIntent（决策结果）
     * - requestCapability（令牌签发/拒绝原因）
     * - revokeToken（撤销原因）
     */
    const int CAT_POLICY_DECISION   = 4;
    
    /**
     * 能力授予：能力令牌签发详情
     * 
     * 典型事件：
     * - 令牌签发（tokenId + scope + TTL）
     * - 令牌验证失败（失败原因）
     */
    const int CAT_CAPABILITY_ISSUED = 5;
    
    /**
     * 用户确认：展示/结果
     * 
     * 典型事件：
     * - 确认请求展示（confirmId + 摘要）
     * - 用户决策（允许/拒绝/永久允许）
     */
    const int CAT_CONFIRMATION      = 6;
    
    /**
     * 系统事件：服务启动/升级/挂起
     * 
     * 典型事件：
     * - 服务启动（版本号）
     * - 策略包更新（版本 + 签名）
     * - Agent 注册/注销
     */
    const int CAT_SYSTEM            = 7;
    
    // ========== 核心方法 ==========
    
    /**
     * 写入一条审计事件
     * 
     * 调用方限制：
     * - 需要 WRITE_AUDIT 权限（signature|privileged）
     * - 仅系统服务和特权大脑可调用
     * 
     * 处理流程：
     * 1. 校验 category 有效性
     * 2. 生成 seq（单调递增）
     * 3. 计算 eventId = hash(event 字段)
     * 4. 计算 prevHash = hash(prev event)
     * 5. 周期性 TEE 锚定
     * 6. 持久化到审计日志
     * 7. 通知活性监听器
     * 
     * @param event 审计事件（seq/eventId/prevHash 由服务填充）
     * @param eventId 输出参数，返回事件 ID（单元素数组，取 eventId[0]）
     * @return STATUS_OK / STATUS_DENIED / STATUS_INVALID_EVENT
     */
    int record(in AuditEvent event, out String[] eventId);
    
    /**
     * 查询审计事件（脱敏视图）
     * 
     * viewerRole 脱敏规则：
     * - USER (0)：仅摘要级（summary + 时间 + 分类），不含 agentId/证据引用/负载哈希
     * - ADMIN_BRAIN (1)：完整信息（含 agentId/evidenceRefs/payloadHash）
     * 
     * 权限检查：
     * - 需要 READ_AUDIT 权限
     * - SystemUI/隐私仪表盘使用 USER 角色
     * - 大脑/管理工具使用 ADMIN_BRAIN 角色
     * 
     * @param query 查询条件（时间范围/分类/agentId/intentId）
     * @return 脱敏后的事件数组（按 seq 升序）
     */
    AuditEvent[] query(in AuditQuery query);
    
    /**
     * 校验链完整性
     * 
     * 校验内容：
     * 1. 每条事件的 prevHash 与前序事件匹配
     * 2. eventId = hash(event fields) 正确
     * 3. TEE 锚点签名有效
     * 4. seq 单调递增无跳跃
     * 
     * 用途：
     * - 隐私仪表盘显示"链完整 ✓"
     * - 司法取证完整性证明
     * 
     * @param fromSeq 起始序号（0 = 从头）
     * @param toSeq 结束序号（0 = 到末尾）
     * @return 校验状态（是否完整/断裂位置/最近锚点）
     */
    ChainStatus verifyChain(in long fromSeq, in long toSeq);
    
    /**
     * 获取当前链锚点信息
     * 
     * 返回：
     * - 最近一次 TEE 锚定的 seq
     * - 锚点时间戳
     * - 锚点引用（TEE 存储位置）
     * 
     * @return 锚点状态
     */
    ChainStatus getChainAnchor();
    
    /**
     * 订阅活性流（驱动 SystemUI 活性指示器）
     * 
     * 活性流特点：
     * - 高频低配：仅 category + agentId + active 标记
     * - 不含敏感负载
     * - 用于驱动状态栏"在读屏/在操作/在录音"芯片
     * 
     * @param listener 活性监听器
     * @return STATUS_OK / STATUS_DENIED
     */
    int registerActivityListener(in IAuditListener listener);
    
    /**
     * 取消订阅活性流
     * 
     * @param listener 活性监听器
     * @return STATUS_OK
     */
    int unregisterActivityListener(in IAuditListener listener);
}
