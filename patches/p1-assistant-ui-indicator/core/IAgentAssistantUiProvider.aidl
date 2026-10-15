// Genton (AI Native OS) - 助手 UI 提供方面
//
// 实现方：SystemUI（应用 UID 无法注册系统服务，故经
//         IAgentAssistantUi.registerUiProvider 反向交出本接口）
// 调用方：system_server 侧 AgentAssistantUiService（代理转发）
package com.ainative.ui;

import com.ainative.ui.SuggestionCapsuleData;

interface IAgentAssistantUiProvider {
    oneway void requestAssistantSurface(in String reason);
    oneway void showSuggestion(in SuggestionCapsuleData capsule);
    oneway void navigateTo(in int section);
    oneway void setActivityIndicator(in int kind, in boolean active);
}
