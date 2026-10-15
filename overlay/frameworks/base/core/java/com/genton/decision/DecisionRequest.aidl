// DecisionRequest.aidl — 决策请求（B5.3）
// 决策由应用/系统定义（候选/问题/等级），模型只做带概率的预测
package com.genton.decision;

/** @hide */
parcelable DecisionRequest {
    // 场景标识（用于阈值表/审计/计量；如 intent_classify / notification_category / callgate_spam）
    String scenario;

    // 决策输入（state 文本；或 imageRef 指向已掩码的帧，单图+文本联合输入走 S5-h 通道）
    String state;
    String imageRef;

    // Choice: 有序候选 key（与 candidateDescs 对齐）；Noul: 空；Score: 有序等级（低→高）
    String[] candidates;
    String[] candidateDescs;
    // Noul 的是否问题 / 决策指令
    String question;

    // 硬约束（供候选过滤/路由；由策略引擎与推理路由注入）
    // local_only / cloud_denied / offline_ok / low_energy / sensitive
    String[] constraints;

    // 计量与预算（ACB AgentBudget 联动）
    long budgetMicros;
    long deadlineNanos;          // 单调时钟截止（超时走降级）

    // 调用方（审计/阈值归属）
    String callerAgentId;
    String intentId;
}
