package com.ainative.action;

/**
 * AppInfo - 已安装应用信息
 *
 * 服务端实现：IAgentQueryService.queryInstalledApps()（当前为骨架契约，无实现方）。
 * 字段取自《轨道A-执行层AIDL与动作契约草案》§5.2。
 */
parcelable AppInfo {
    /** 应用包名 */
    String packageName;

    /** 应用展示名 */
    String label;

    /** 是否启用 */
    boolean enabled;

    /** 是否系统应用 */
    boolean isSystemApp;

    /** 首次安装时间（墙钟毫秒，PackageInfo.firstInstallTime） */
    long firstInstallTime;
}
