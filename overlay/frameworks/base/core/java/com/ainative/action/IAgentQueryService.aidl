package com.ainative.action;

import com.ainative.action.AppInfo;
import com.ainative.action.ActionInfo;
import com.ainative.action.ComponentTarget;

/**
 * IAgentQueryService
 * AI Native OS 系统服务接口
 * 自动生成 - 请勿手动编辑
 */
interface IAgentQueryService {

    List<AppInfo> queryInstalledApps();

    ActionInfo resolveIntent(in ComponentTarget target);

}