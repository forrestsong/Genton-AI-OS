// Genton (AI Native OS) - 确认决策回调
//
// 方向：SystemUI -> system_server 侧 ConfirmationHostService
// 用途：用户在选择面板上做出决策后，把决策交回服务端；
//       服务端再回灌策略引擎（reportUserDecision）并路由回执行中介。
package com.ainative.ui;

oneway interface IConfirmationDecisionCallback {
    /**
     * @param confirmId 确认 ID
     * @param userDecision IPolicyEngine.USER_ALLOW_ONCE/USER_ALLOW_TASK/USER_DENY
     */
    void onUserDecision(in String confirmId, in int userDecision);
}
