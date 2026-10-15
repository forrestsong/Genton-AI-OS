package com.ainative.memory;

import com.ainative.memory.MemoryEntryDraft;
import com.ainative.memory.MemoryEntry;
import com.ainative.memory.MemoryQuery;
import com.ainative.memory.ForgetRequest;
import com.ainative.memory.ForgetJob;
import com.ainative.memory.ProvenanceInfo;
import com.ainative.memory.MemoryStats;
import com.ainative.memory.IAgentMemoryListener;

/**
 * Agent 记忆服务（S4）
 * 
 * 三层记忆（工作/长期/系统）+ 向量/知识图谱索引
 * 
 * 红线：
 * 1. 记忆写入只接受"来源可溯"的数据（provenance 强制）
 * 2. 内容按来源 taint 化，杜绝长期记忆被间接投毒
 * 3. 每条记忆落库前经 PII 掩码与分级加密（密钥在 TEE 域）
 * 4. 来自 APP_CONTENT / WEB_CONTENT 的内容默认不得直接提升为长期记忆
 */
interface IAgentMemoryService {
    const int STATUS_OK            = 0;
    const int STATUS_DENIED        = 1;   // 无能力令牌/超出访问边界/来源不合规
    const int STATUS_PII_REJECT    = 2;   // 命中敏感 PII 规则，拒绝入长期记忆
    const int STATUS_NOT_FOUND     = 3;
    const int STATUS_STATE_CONFLICT = 4;
    const int STATUS_INTERNAL_ERROR = -1;

    // kind 常量
    const int KIND_WORKING   = 0;  // 工作记忆（当前会话上下文）
    const int KIND_LONG_TERM = 1;  // 长期记忆（语义化事实/偏好）
    const int KIND_SYSTEM    = 2;  // 系统记忆（用户身份、隐私偏好、设备事实）

    // 来源类型常量
    const int SOURCE_USER_STMT      = 0;  // 用户陈述
    const int SOURCE_SCREEN         = 1;  // 屏幕内容
    const int SOURCE_NOTIFICATION   = 2;  // 通知
    const int SOURCE_APP_DATA       = 3;  // 应用数据
    const int SOURCE_WEB            = 4;  // 网页内容
    const int SOURCE_MODEL_EXTRACT  = 5;  // 模型抽取

    /**
     * 写入一条记忆（大脑已完成抽取的"准备内容"）
     * 服务侧完成掩码/加密/索引/provenance
     * 
     * @param draft 记忆草稿
     * @param entry 输出：已写入的记忆条目
     * @return STATUS_OK/STATUS_DENIED/STATUS_PII_REJECT
     */
    int write(in MemoryEntryDraft draft, out MemoryEntry entry);

    /**
     * 语义+结构化查询
     * 返回调用方有权读取的结果，内容为掩码后的呈现版本
     * 
     * @param query 查询请求
     * @return 记忆条目数组
     */
    MemoryEntry[] query(in MemoryQuery query);

    /**
     * 按 ID 获取单条记忆
     * 
     * @param memoryId 记忆唯一标识
     * @return 记忆条目，若无权限或不存在返回 null
     */
    MemoryEntry getById(String memoryId);

    /**
     * 按 ID 删除单条记忆（写审计 memory.delete）
     * 
     * @param memoryId 记忆唯一标识
     * @return STATUS_OK/STATUS_DENIED/STATUS_NOT_FOUND
     */
    int deleteById(String memoryId);

    /**
     * "清除关于 X 的所有记忆"：异步任务
     * 结果经 onForgetCompleted 返回
     * 
     * @param request 遗忘请求
     * @param job 输出：遗忘任务信息
     * @return STATUS_OK/STATUS_DENIED
     */
    int forget(in ForgetRequest request, out ForgetJob job);

    /**
     * 记忆来源追溯（隐私仪表盘"这条记忆哪来的"）
     * 
     * @param memoryId 记忆唯一标识
     * @return 溯源信息，若无权限或不存在返回 null
     */
    ProvenanceInfo getProvenance(String memoryId);

    /**
     * 订阅记忆变更（大脑/SystemUI 按需）
     * 
     * @param listener 监听器
     * @return STATUS_OK
     */
    int registerListener(in IAgentMemoryListener listener);

    /**
     * 取消订阅记忆变更
     * 
     * @param listener 监听器
     * @return STATUS_OK
     */
    int unregisterListener(in IAgentMemoryListener listener);

    /**
     * 获取统计信息（各层条数/容量/最近同步时间）
     * 
     * @return 统计信息
     */
    MemoryStats getStats();
}
