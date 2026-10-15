// IAgentDecisionService.aidl — 本地决策模型服务（B5.3）
// NeoHorse-Jev-4B prefill-only 结构化决策：Choice/Noul/Score 三类带概率决策
// 旗舰版/通用版双后端：旗舰 SoC 本地推理 / 其余机型经 IAgentInferenceRouter→CloudBroker 云端决策
// 红线：模型只出"带概率的建议"，授权/deny 默认/路由常量仍由策略引擎（A7）确定性裁决
package com.genton.decision;

import com.genton.decision.DecisionRequest;
import com.genton.decision.DecisionResult;

/** @hide */
interface IAgentDecisionService {
    /** Choice：候选选择，返回选中候选 + 全候选概率分布 */
    DecisionResult choose(in DecisionRequest request);

    /** Noul：是非判定，返回 P(true) */
    DecisionResult judge(in DecisionRequest request);

    /** Score：分级评分，返回等级分布 + 期望分 */
    DecisionResult score(in DecisionRequest request);

    /**
     * 决策后端健康/能力查询（供路由与适配器降级判定）
     * @return 后端状态码：0=本地就绪 1=云端可用 2=本地不可用（降级云端）3=全部不可用（走规则路径）
     */
    int getBackendStatus();

    /**
     * 决策阈值表版本（decision_thresholds_v1.json 装载版本，供审计/联调对账）
     */
    String getThresholdVersion();
}
