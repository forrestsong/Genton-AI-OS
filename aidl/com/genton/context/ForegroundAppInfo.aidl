// ForegroundAppInfo.aidl
package com.genton.context;

/**
 * 前台应用信息
 */
parcelable ForegroundAppInfo {
    /**
     * 包名
     */
    String packageName;

    /**
     * 入口Activity（不含UI内容）
     */
    @nullable String activityName;

    /**
     * 应用标签（本地化）
     */
    @nullable String appLabel;

    /**
     * 进入前台的时间（System.currentTimeMillis()）
     */
    long foregroundSinceMs;

    /**
     * 是否为系统UI（SystemUI/Launcher）
     */
    boolean isSystemUi;
}
