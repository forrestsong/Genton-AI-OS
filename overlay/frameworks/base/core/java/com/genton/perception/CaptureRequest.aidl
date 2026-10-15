package com.genton.perception;

/**
 * CaptureRequest - 屏幕捕获请求
 *
 * 服务端实现：AgentScreenService.captureScreen(CaptureRequest request)
 *
 * 语义（与实现一致）：
 * - format / quality 传入编码器（encodeBitmap）；
 * - scale 为缩放系数，取值 (0,1) 时按比例缩小，<=0 表示原始尺寸；
 * - maskSecureWindows 为 true 时安全窗口不进入像素数据。
 */
parcelable CaptureRequest {
    /** 编码格式（见 Bitmap.CompressFormat，如 JPEG/PNG） */
    int format;

    /** 编码质量（0..100） */
    int quality;

    /** 是否掩码安全窗口（FLAG_SECURE） */
    boolean maskSecureWindows;

    /** 缩放系数（0 表示不缩放） */
    float scale;
}
