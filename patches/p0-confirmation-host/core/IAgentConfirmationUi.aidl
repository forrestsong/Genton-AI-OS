// Genton (AI Native OS) - 确认 UI 提供方面
//
// 实现方：SystemUI（应用 UID，无法注册系统服务，故通过
//         IAgentConfirmationHost.registerUiProvider 反向交出本接口）
// 调用方：system_server 侧 ConfirmationHostService
//
// 安全约束（D5 §5.1）：
//   - 面板由 SystemUI 独占绘制，全屏安全窗口（FLAG_SECURE）
//   - 任何 Agent 不可发起、不可伪造、不可覆盖
//   - 用户决策由 system_server 侧回灌策略引擎，本接口只负责呈现与收集
package com.ainative.ui;

import com.ainative.ui.ConfirmationUiRequest;
import com.ainative.ui.IConfirmationDecisionCallback;

interface IAgentConfirmationUi {
    /**
     * 展示确认面板（全屏独占）
     * @param request 由策略引擎签发的确认请求
     */
    oneway void show(in ConfirmationUiRequest request);

    /**
     * 关闭确认面板
     * @param confirmId 确认 ID
     * @param reason DISMISS_* 常量（见 IAgentConfirmationHost）
     */
    oneway void dismiss(in String confirmId, in int reason);

    /**
     * 用户决策回调注册（SystemUI -> 服务端）
     * 说明：决策经此回调交回服务端，由服务端回灌策略引擎并路由回执行中介，
     *       保证「决策不经大脑」。
     */
    oneway void setDecisionCallback(in IConfirmationDecisionCallback callback);
}
