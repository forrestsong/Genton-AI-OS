package com.genton.perception;

/**
 * ScreenFrame - 屏幕帧
 *
 * 服务端实现：AgentScreenService（captureScreen / createErrorFrame /
 * createMetadataOnlyFrame）
 *
 * 语义（与实现一致）：
 * - 出错帧：sequenceId = -1，data 为空，看 statusCode / errorMessage；
 * - 元数据帧：isMetadataOnly = true，data 为空但携带安全窗口统计；
 * - 时间戳一律使用单调时钟 System.nanoTime()。
 */
parcelable ScreenFrame {
    /** 帧序号（单调递增；出错帧为 -1） */
    long sequenceId;

    /** 采集时间戳（单调时钟纳秒） */
    long timestampNanos;

    /** 帧宽（像素） */
    int width;

    /** 帧高（像素） */
    int height;

    /** 编码格式 */
    int format;

    /** 编码后的像素数据（元数据帧/出错帧为空数组） */
    byte[] data;

    /** data 有效长度 */
    int dataLength;

    /** 是否包含安全窗口（FLAG_SECURE） */
    boolean hasSecureWindow;

    /** 安全窗口数量 */
    int secureWindowCount;

    /** 安全窗口区域序列化串（包名:left,top,right,bottom，分号分隔） */
    String secureRegions;

    /** 叠加层数量 */
    int overlayCount;

    /** 采集耗时（纳秒） */
    long captureLatencyNanos;

    /** 状态码（AgentScreenService.STATUS_*） */
    int statusCode;

    /** 错误信息（仅出错帧有效） */
    String errorMessage;

    /** 是否仅返回元数据（无像素数据） */
    boolean isMetadataOnly;
}
