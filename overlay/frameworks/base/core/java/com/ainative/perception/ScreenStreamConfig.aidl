package com.ainative.perception;

/**
 * ScreenStreamConfig - 屏幕流配置
 *
 * 服务端实现：AgentScreenService.startScreenStream(ScreenStreamConfig config, ...)
 *
 * 语义（与实现一致）：fps <= 0 时取 DEFAULT_FPS 并受 MAX_FPS 截断；
 * quality <= 0 时取 DEFAULT_QUALITY。
 */
parcelable ScreenStreamConfig {
    /** 目标帧率（每秒帧数） */
    int fps;

    /** 编码质量（0..100） */
    int quality;

    /** 编码格式（见 Bitmap.CompressFormat） */
    int format;
}
