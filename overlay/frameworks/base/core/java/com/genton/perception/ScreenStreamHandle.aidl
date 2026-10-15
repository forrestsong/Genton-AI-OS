package com.genton.perception;

/**
 * ScreenStreamHandle - 屏幕流句柄
 *
 * 服务端实现：AgentScreenService.startScreenStream() 返回，
 * stopScreenStream(ScreenStreamHandle handle) 使用（以 streamId 定位流）。
 */
parcelable ScreenStreamHandle {
    /** 流标识（服务端签发） */
    String streamId;

    /** 实际帧率 */
    int fps;

    /** 编码格式 */
    int format;

    /** 编码质量 */
    int quality;

    /** 起始时间戳（单调时钟纳秒） */
    long timestampNanos;
}
