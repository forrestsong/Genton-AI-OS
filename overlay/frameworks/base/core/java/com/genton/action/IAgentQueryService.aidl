package com.genton.action;

import com.genton.action.AppInfo;
import com.genton.action.ActionInfo;
import com.genton.action.ComponentTarget;

/**
 * IAgentQueryService
 * AI Native OS 系统服务接口
 * 自动生成 - 请勿手动编辑
 */
interface IAgentQueryService {

    List<AppInfo> queryInstalledApps();

    ActionInfo resolveIntent(in ComponentTarget target);

}