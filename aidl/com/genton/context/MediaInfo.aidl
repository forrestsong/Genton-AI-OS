// MediaInfo.aidl
package com.genton.context;

/**
 * 媒体会话信息
 */
parcelable MediaInfo {
    /**
     * 媒体会话所属包名
     */
    String packageName;

    /**
     * 是否正在播放
     */
    boolean isPlaying;

    /**
     * 媒体标题（脱敏后）
     */
    @nullable String title;

    /**
     * 媒体类型（music/video/podcast）
     */
    @nullable String mediaType;

    /**
     * 是否为本地媒体（vs 流媒体）
     */
    boolean isLocal;
}
