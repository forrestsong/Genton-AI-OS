package com.ainative.policy;

import com.ainative.policy.PolicyDecision;
import com.ainative.policy.IntentEvaluationRequest;
import com.ainative.policy.CapabilityRequest;
import com.ainative.policy.CapabilityToken;
import com.ainative.policy.PolicyRuleView;
import com.ainative.policy.PolicyStats;
import com.ainative.policy.IPolicyListener;

/**
 * 策略引擎接口（A7.1 策略引擎服务端）
 * 
 * 职责：
 * - 策略编译与持有（策略即代码，开机 fail-stop 校验）
 * - 意图级预检 + 动作级令牌签发
 * - 确认决策回灌
 * - 域服务令牌三查（签名 + scope + 撤销）
 * - 策略热更新
 * 
 * 权限：
 * - evaluateIntent/requestCapability: com.ainative.permission.POLICY_ENGINE
 * - reportUserDecision: com.ainative.permission.SUBMIT_USER_DECISION（SystemUI）
 * - validateToken: com.ainative.permission.VALIDATE_TOKEN（各域服务）
 * - reloadPolicySet: 平台签名
 * 
 * 红线：
 * 1. DENY 永远优先于 ALLOW（含用户记忆化授权）
 * 2. 不认识的 capability/动作 = DENY
 * 3. 策略基线（baseline: true）不可被覆盖
 * 
 * @hide
 */
interface IPolicyEngine {
    // ==================== 状态码 ====================
    const int STATUS_OK = 0;
    const int STATUS_POLICY_DENY = 1;
    const int STATUS_UNKNOWN_CAP = 2;
    const int STATUS_INVALID_REQ = 3;
    const int STATUS_TOKEN_INVALID = 4;
    const int STATUS_CONFIRM_PENDING = 5;
    const int STATUS_INTERNAL_ERROR = -1;
    
    // ==================== 裁决常量（PolicyDecision.decision）====================
    const int DEC_GRANT = 0;
    const int DEC_DENY = 1;
    const int DEC_REQUIRE_CONFIRMATION = 2;
    const int DEC_REQUIRE_MITIGATION = 3;
    
    // ==================== 用户确认决策（reportUserDecision）====================
    const int USER_ALLOW_ONCE = 0;
    const int USER_ALLOW_TASK = 1;
    const int USER_DENY = 2;
    
    /**
     * 意图级预检（规划前调用）
     * 
     * 用途：Agent 规划前查询"这个意图可行吗/需要哪些确认"
     * 
     * @param request 意图评估请求
     * @param decision 输出裁决结果
     * @return STATUS_OK/STATUS_POLICY_DENY/STATUS_UNKNOWN_CAP
     */
    int evaluateIntent(in IntentEvaluationRequest request, out PolicyDecision decision);
    
    /**
     * 动作级令牌请求（执行前调用）
     * 
     * 用途：Agent 执行动作前申请能力令牌
     * 成功时返回签名令牌（CapabilityToken）
     * 
     * @param request 能力请求（含 taint 打标）
     * @param decision 输出裁决结果
     * @return STATUS_OK/STATUS_POLICY_DENY/STATUS_CONFIRM_PENDING
     */
    int requestCapability(in CapabilityRequest request, out PolicyDecision decision);
    
    /**
     * 用户决策回灌（SystemUI 确认宿主 → 本服务）
     *
     * 用途：用户在确认面板选择 allow_once/allow_task/deny
     * 成功时补签令牌并通过 out token 直接回灌到调用方（执行中介），
     * 由中介把令牌落到等待中的请求上下文（确认结果直回，不经大脑）。
     *
     * @param confirmId 确认 ID
     * @param userDecision USER_ALLOW_ONCE/USER_ALLOW_TASK/USER_DENY
     * @param token 输出补签令牌（allow 时非空；deny/无效时为空对象）
     * @return STATUS_OK/STATUS_INVALID_REQ
     */
    int reportUserDecision(in String confirmId, in int userDecision, out CapabilityToken token);
    
    /**
     * 域服务在线三查（签名 + scope + 撤销）
     * 
     * 用途：域服务验证令牌有效性（离线验签 + 在线撤销状态）
     * 
     * @param token 能力令牌
     * @param scopeIntentId 当前动作的 intentId（scope 锚定）
     * @param capability 能力名
     * @return true=有效，false=失效/伪造/撤销
     */
    boolean validateToken(in CapabilityToken token, in String scopeIntentId, in String capability);
    
    /**
     * 撤销令牌（Agent 注销/任务结束/用户停用）
     * 
     * @param agentId Agent ID
     * @param intentId 意图 ID（null=撤销该 Agent 全部令牌）
     * @return STATUS_OK
     */
    int revokeTokens(in String agentId, in String intentId);
    
    /**
     * 治理视图（隐私仪表盘）
     * 
     * 用途：展示该 Agent 被授予/拒绝的策略规则
     * 
     * @param agentId Agent ID
     * @return 适用规则列表（脱敏）
     */
    PolicyRuleView[] listApplicableRules(in String agentId);
    
    /**
     * 活跃路由域规则视图（只查询）
     * 
     * 用途：治理/规则编辑回显已装载的 notification/call_gate 域规则
     * （含开机平台基线 + reloadPolicySet 装载的用户规则，sys 基线不可覆盖）
     * 
     * @param domain 域（notification/call_gate），null=全部已装载域
     * @return 规则视图（脱敏：不含 when/then 条件细节，仅 id/effect/hit 统计）
     */
    PolicyRuleView[] listActiveRules(in String domain);
    
    /**
     * 策略热更新入口（平台签名 OTA 调用）
     * 
     * 用途：运行时热编译策略集，失败回滚并停发新令牌
     * 
     * @param signedPolicyBundle 签名策略包（JSON）
     * @param bundleVersion 输出策略版本（单元素数组，取 bundleVersion[0]）
     * @return STATUS_OK/STATUS_INVALID_REQ
     */
    int reloadPolicySet(in String signedPolicyBundle, out String[] bundleVersion);
    
    /**
     * 订阅策略变更/令牌撤销
     * 
     * 用途：域服务缓存失效、SystemUI 提示
     */
    int registerListener(in IPolicyListener listener);
    int unregisterListener(in IPolicyListener listener);
    
    /**
     * 获取统计数据
     */
    PolicyStats getStats();
}
