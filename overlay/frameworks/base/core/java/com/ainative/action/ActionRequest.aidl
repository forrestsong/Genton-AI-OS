package com.ainative.action;

import android.os.Bundle;

/**
 * ActionRequest Parcelable
 * 动作执行请求（A3.2 执行中介 / D2 草案 §3.2）
 *
 * 字段语义对齐《轨道A-执行层AIDL与动作契约草案》：
 * 1. actionName: 注册表中唯一动作名（与模型工具名 1:1）
 * 2. arguments: 扁平参数，key 对齐 registry.inputSchema
 * 3. taintedArgumentKeys: 值派生自 APP/WEB 内容的 key（模型层打标，见草案 §6）
 * 4. sourceIntentId: 触发本动作的意图（用于"执行是否匹配用户目标"校验）
 * 5. agentId: 发起 Agent（ACB.id）
 * 6. reason: 人类可读用途 → 审计
 *
 * 注：参数来源的逐 key 信任等级细分（A3.4 taint 裁决）不在本结构展开，
 * 由执行中介在构造 CapabilityRequest.argTaints 时统一按非 USER 来源标记。
 *
 * @hide
 */
parcelable ActionRequest {
    /** 注册表中唯一动作名（= ActionInfo.actionId，与模型工具名 1:1） */
    String actionName;

    /** 扁平参数（key 对齐 registry.inputSchema；执行前由中介序列化为 JSON 交执行器） */
    Bundle arguments;

    /** 值派生自 APP/WEB 内容的参数 key（模型打标，进入策略层 taint 检查） */
    String[] taintedArgumentKeys;

    /** 触发本动作的意图 ID（scope 锚定，须与令牌签发时一致） */
    String sourceIntentId;

    /** 发起 Agent 的 ID */
    String agentId;

    /** 人类可读用途（审计/确认流展示） */
    String reason;
}
