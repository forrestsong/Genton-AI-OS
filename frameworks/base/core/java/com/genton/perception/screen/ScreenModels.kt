package com.genton.perception.screen

import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import com.genton.perception.ui.TrustLevel

/**
 * A2.2 帧捕获服务 - 屏幕帧数据模型
 * 
 * 职责：
 * 1. 封装屏幕截图数据
 * 2. 记录帧元数据（时间戳、显示信息）
 * 3. 标记安全窗口区域（掩码处理）
 * 4. 提供信任级别标记
 */
data class ScreenFrame(
    /**
     * 帧ID（用于审计关联）
     */
    val frameId: Long,
    
    /**
     * 帧时间戳（SystemClock.elapsedRealtimeNanos）
     */
    val timestampRealtimeNanos: Long,
    
    /**
     * 显示ID（默认0）
     */
    val displayId: Int,
    
    /**
     * 帧宽度
     */
    val width: Int,
    
    /**
     * 帧高度
     */
    val height: Int,
    
    /**
     * JPEG字节数组
     * v1实现：直接传输字节数组
     * v2（规划）：改用SharedMemory零拷贝
     */
    val jpeg: ByteArray,
    
    /**
     * 被掩码的安全窗口区域
     * 标记哪些区域因FLAG_SECURE而被遮挡
     */
    val maskedSecureRegions: Array<Rect>,
    
    /**
     * 是否部分安全
     * true：部分窗口有FLAG_SECURE，已掩码处理
     * false：无安全内容
     */
    val partiallySecure: Boolean,
    
    /**
     * 内容信任级别
     * 通常为APP_CONTENT（应用内容，可能含提示注入）
     */
    val trust: TrustLevel
) : Parcelable {
    
    /**
     * 获取JPEG大小（字节）
     */
    fun getJpegSize(): Int {
        return jpeg.size
    }
    
    /**
     * 是否包含安全内容
     */
    fun hasSecureContent(): Boolean {
        return partiallySecure || maskedSecureRegions.isNotEmpty()
    }
    
    /**
     * 获取安全区域总面积
     */
    fun getSecureArea(): Int {
        return maskedSecureRegions.sumOf { rect ->
            rect.width() * rect.height()
        }
    }
    
    /**
     * 获取安全区域占比
     */
    fun getSecureRatio(): Float {
        val totalArea = width * height
        if (totalArea == 0) return 0f
        
        val secureArea = getSecureArea()
        return secureArea.toFloat() / totalArea
    }
    
    /**
     * 检查坐标是否在安全区域内
     */
    fun isInSecureRegion(x: Int, y: Int): Boolean {
        return maskedSecureRegions.any { rect ->
            rect.contains(x, y)
        }
    }
    
    // Parcelable 实现
    constructor(parcel: Parcel) : this(
        frameId = parcel.readLong(),
        timestampRealtimeNanos = parcel.readLong(),
        displayId = parcel.readInt(),
        width = parcel.readInt(),
        height = parcel.readInt(),
        jpeg = parcel.createByteArray()!!,
        maskedSecureRegions = parcel.createTypedArray(Rect.CREATOR)!!,
        partiallySecure = parcel.readByte() != 0.toByte(),
        trust = TrustLevel.valueOf(parcel.readString()!!)
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(frameId)
        parcel.writeLong(timestampRealtimeNanos)
        parcel.writeInt(displayId)
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeByteArray(jpeg)
        parcel.writeTypedArray(maskedSecureRegions, flags)
        parcel.writeByte(if (partiallySecure) 1 else 0)
        parcel.writeString(trust.name)
    }
    
    override fun describeContents(): Int = 0
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as ScreenFrame
        
        if (frameId != other.frameId) return false
        if (!jpeg.contentEquals(other.jpeg)) return false
        if (!maskedSecureRegions.contentEquals(other.maskedSecureRegions)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = frameId.hashCode()
        result = 31 * result + jpeg.contentHashCode()
        result = 31 * result + maskedSecureRegions.contentHashCode()
        return result
    }
    
    companion object CREATOR : Parcelable.Creator<ScreenFrame> {
        override fun createFromParcel(parcel: Parcel): ScreenFrame {
            return ScreenFrame(parcel)
        }
        
        override fun newArray(size: Int): Array<ScreenFrame?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * A2.2 帧捕获服务 - 捕获请求参数
 * 
 * 职责：
 * 1. 定义截图参数（显示、区域、质量）
 * 2. 控制性能优化（缩放、超时）
 * 3. 审计追踪（reason）
 * 4. 安全内容授权控制
 */
data class CaptureRequest(
    /**
     * 显示ID（默认0）
     */
    val displayId: Int = 0,
    
    /**
     * 裁剪区域
     * null表示全屏
     */
    val crop: Rect? = null,
    
    /**
     * 缩放宽度上限（像素）
     * 默认1280，节省带宽
     */
    val maxWidth: Int = 1280,
    
    /**
     * JPEG质量（0-100）
     * 默认80
     */
    val jpegQuality: Int = 80,
    
    /**
     * 等待下一合成帧的超时时间（毫秒）
     * 默认50ms
     */
    val maxWaitMs: Long = 50,
    
    /**
     * 捕获原因（人类可读）
     * 写入审计日志
     */
    val reason: String,
    
    /**
     * 是否需要用户授权的安全内容
     * 默认false（拒绝安全窗口内容）
     */
    val requireUserAuthorizedSecureContent: Boolean = false
) : Parcelable {
    
    /**
     * 是否全屏捕获
     */
    fun isFullScreen(): Boolean {
        return crop == null
    }
    
    /**
     * 校验参数有效性
     */
    fun validate(): Boolean {
        if (maxWidth <= 0 || maxWidth > 4096) return false
        if (jpegQuality < 0 || jpegQuality > 100) return false
        if (maxWaitMs < 0 || maxWaitMs > 5000) return false
        if (reason.isBlank()) return false
        return true
    }
    
    // Parcelable 实现
    constructor(parcel: Parcel) : this(
        displayId = parcel.readInt(),
        crop = parcel.readParcelable(Rect::class.java.classLoader),
        maxWidth = parcel.readInt(),
        jpegQuality = parcel.readInt(),
        maxWaitMs = parcel.readLong(),
        reason = parcel.readString()!!,
        requireUserAuthorizedSecureContent = parcel.readByte() != 0.toByte()
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(displayId)
        parcel.writeParcelable(crop, flags)
        parcel.writeInt(maxWidth)
        parcel.writeInt(jpegQuality)
        parcel.writeLong(maxWaitMs)
        parcel.writeString(reason)
        parcel.writeByte(if (requireUserAuthorizedSecureContent) 1 else 0)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<CaptureRequest> {
        override fun createFromParcel(parcel: Parcel): CaptureRequest {
            return CaptureRequest(parcel)
        }
        
        override fun newArray(size: Int): Array<CaptureRequest?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * A2.2 帧捕获服务 - 帧流配置
 * 
 * 职责：
 * 1. 定义帧流参数（帧率、质量）
 * 2. 控制关键帧策略
 * 3. 性能优化配置
 */
data class ScreenStreamConfig(
    /**
     * 显示ID（默认0）
     */
    val displayId: Int = 0,
    
    /**
     * FPS提示（上限建议）
     * 默认10fps，实际由变化驱动
     */
    val fpsHint: Int = 10,
    
    /**
     * 仅关键帧模式
     * true：仅画面实质变化时回调（默认）
     * false：按fpsHint持续推送
     */
    val keyframesOnly: Boolean = true,
    
    /**
     * 缩放宽度上限（像素）
     * 默认1280
     */
    val maxWidth: Int = 1280,
    
    /**
     * JPEG质量（0-100）
     * 默认75（流模式降低质量）
     */
    val jpegQuality: Int = 75
) : Parcelable {
    
    /**
     * 校验参数有效性
     */
    fun validate(): Boolean {
        if (fpsHint <= 0 || fpsHint > 60) return false
        if (maxWidth <= 0 || maxWidth > 4096) return false
        if (jpegQuality < 0 || jpegQuality > 100) return false
        return true
    }
    
    // Parcelable 实现
    constructor(parcel: Parcel) : this(
        displayId = parcel.readInt(),
        fpsHint = parcel.readInt(),
        keyframesOnly = parcel.readByte() != 0.toByte(),
        maxWidth = parcel.readInt(),
        jpegQuality = parcel.readInt()
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(displayId)
        parcel.writeInt(fpsHint)
        parcel.writeByte(if (keyframesOnly) 1 else 0)
        parcel.writeInt(maxWidth)
        parcel.writeInt(jpegQuality)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<ScreenStreamConfig> {
        override fun createFromParcel(parcel: Parcel): ScreenStreamConfig {
            return ScreenStreamConfig(parcel)
        }
        
        override fun newArray(size: Int): Array<ScreenStreamConfig?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * 帧流句柄
 */
data class ScreenStreamHandle(
    val streamId: String,
    val displayId: Int,
    val startTime: Long
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        streamId = parcel.readString()!!,
        displayId = parcel.readInt(),
        startTime = parcel.readLong()
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(streamId)
        parcel.writeInt(displayId)
        parcel.writeLong(startTime)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<ScreenStreamHandle> {
        override fun createFromParcel(parcel: Parcel): ScreenStreamHandle {
            return ScreenStreamHandle(parcel)
        }
        
        override fun newArray(size: Int): Array<ScreenStreamHandle?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * 服务状态码
 */
object ScreenServiceStatus {
    const val STATUS_OK = 0                    // 成功
    const val STATUS_DENIED = 1                // 策略/权限拒绝
    const val STATUS_SECURE_CONTENT = 2        // 全屏安全且未授权
    const val STATUS_OVERLAY_DETECTED = 3      // 检测到可疑overlay
    const val STATUS_NO_ACTIVE_LEASE = 4       // 无活动会话租约
    const val STATUS_INTERNAL_ERROR = -1       // 内部错误
}

/**
 * 安全状态常量
 */
object SecureState {
    const val SECURE_NONE = 0      // 无安全内容
    const val SECURE_PARTIAL = 1   // 部分窗口安全 → 返回掩码帧
    const val SECURE_FULL = 2      // 全屏安全
}

/**
 * 帧监听器回调接口
 */
interface IAgentFrameListener {
    /**
     * 新帧回调
     */
    fun onFrame(frame: ScreenFrame)
    
    /**
     * 流错误回调
     */
    fun onStreamError(statusCode: Int, message: String)
}
