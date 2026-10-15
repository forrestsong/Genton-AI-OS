package com.ainative.action;

/**
 * ComponentTarget - 组件定位目标
 *
 * 服务端实现：IAgentQueryService.resolveIntent(ComponentTarget target)
 * （当前为骨架契约，无实现方）。字段取自
 * 《轨道A-执行层AIDL与动作契约草案》§5.2。
 *
 * 注意（草案要求）：exported = false 的组件不可由 Agent 启动，
 * 执行中介必须拒绝，规划阶段应排除。
 */
parcelable ComponentTarget {
    /** 目标包名 */
    String packageName;

    /** 目标 Activity 名 */
    String activityName;

    /** 是否导出（false 时禁止 Agent 启动） */
    boolean exported;

    /** 是否默认桌面 */
    boolean isDefaultHome;
}
