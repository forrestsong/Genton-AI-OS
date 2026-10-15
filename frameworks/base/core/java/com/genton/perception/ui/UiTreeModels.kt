package com.genton.perception.ui

import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable

/**
 * A2.1 UI树服务 - UI节点数据模型
 * 
 * 职责：
 * 1. 表示UI树中的单个节点
 * 2. 记录节点属性（类型、文本、边界、状态）
 * 3. 支持递归子节点结构
 * 4. 提供稳定的节点ID（会话内）
 * 5. 记录可执行动作类型
 */
data class UiNode(
    /**
     * 稳定节点ID（会话内）
     * 用于后续动作定位和增量变更追踪
     * 格式：n_{hash}_{index}
     */
    val nodeId: String,
    
    /**
     * 应用包名
     */
    val packageName: String?,
    
    /**
     * 组件类名（如 Button、EditText、TextView）
     */
    val className: String?,
    
    /**
     * 资源ID（如 com.example:id/submit_button）
     */
    val resourceId: String?,
    
    /**
     * 节点文本内容（已按 maskPii 处理）
     */
    val text: String?,
    
    /**
     * 内容描述（无障碍）
     */
    val contentDescription: String?,
    
    /**
     * 输入框提示文本
     */
    val hint: String?,
    
    /**
     * 节点边界（屏幕坐标）
     */
    val bounds: Rect,
    
    /**
     * 可见性
     * VISIBLE(0) / PARTIAL(1) / OFFSCREEN(2) / INVISIBLE(3)
     */
    val visibility: Int,
    
    /**
     * 是否可点击
     */
    val clickable: Boolean,
    
    /**
     * 是否可滚动
     */
    val scrollable: Boolean,
    
    /**
     * 是否已聚焦
     */
    val focused: Boolean,
    
    /**
     * 是否可编辑（输入框）
     */
    val editable: Boolean,
    
    /**
     * 是否已选中（复选框、单选按钮）
     */
    val checked: Boolean,
    
    /**
     * 是否已启用
     */
    val enabled: Boolean,
    
    /**
     * 可执行动作类型
     * 如：click, longClick, scrollDown, scrollUp, inputText, back
     */
    val actionTypes: Array<String>,
    
    /**
     * 子节点数组
     */
    val children: Array<UiNode>
) : Parcelable {
    
    /**
     * 是否为容器节点
     */
    fun isContainer(): Boolean {
        return children.isNotEmpty()
    }
    
    /**
     * 是否为叶子节点
     */
    fun isLeaf(): Boolean {
        return children.isEmpty()
    }
    
    /**
     * 是否可交互
     */
    fun isInteractive(): Boolean {
        return clickable || scrollable || editable
    }
    
    /**
     * 是否可见
     */
    fun isVisible(): Boolean {
        return visibility == Visibility.VISIBLE || visibility == Visibility.PARTIAL
    }
    
    /**
     * 获取简短描述（用于日志）
     */
    fun getShortDescription(): String {
        val type = className?.substringAfterLast('.') ?: "Unknown"
        val content = text ?: contentDescription ?: resourceId ?: ""
        return "$type($nodeId): ${content.take(20)}"
    }
    
    /**
     * 递归查找节点
     */
    fun findNodeById(targetNodeId: String): UiNode? {
        if (nodeId == targetNodeId) {
            return this
        }
        
        children.forEach { child ->
            val found = child.findNodeById(targetNodeId)
            if (found != null) {
                return found
            }
        }
        
        return null
    }
    
    /**
     * 获取所有后代节点（扁平化）
     */
    fun getAllDescendants(): List<UiNode> {
        val result = mutableListOf<UiNode>()
        
        fun traverse(node: UiNode) {
            result.add(node)
            node.children.forEach { traverse(it) }
        }
        
        traverse(this)
        return result
    }
    
    /**
     * 计算树深度
     */
    fun getDepth(): Int {
        if (children.isEmpty()) {
            return 1
        }
        return 1 + (children.maxOfOrNull { it.getDepth() } ?: 0)
    }
    
    /**
     * 计算节点总数
     */
    fun countNodes(): Int {
        return 1 + children.sumOf { it.countNodes() }
    }
    
    // Parcelable 实现
    constructor(parcel: Parcel) : this(
        nodeId = parcel.readString()!!,
        packageName = parcel.readString(),
        className = parcel.readString(),
        resourceId = parcel.readString(),
        text = parcel.readString(),
        contentDescription = parcel.readString(),
        hint = parcel.readString(),
        bounds = parcel.readParcelable(Rect::class.java.classLoader)!!,
        visibility = parcel.readInt(),
        clickable = parcel.readByte() != 0.toByte(),
        scrollable = parcel.readByte() != 0.toByte(),
        focused = parcel.readByte() != 0.toByte(),
        editable = parcel.readByte() != 0.toByte(),
        checked = parcel.readByte() != 0.toByte(),
        enabled = parcel.readByte() != 0.toByte(),
        actionTypes = parcel.createStringArray()!!,
        children = parcel.createTypedArray(CREATOR)!!
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(nodeId)
        parcel.writeString(packageName)
        parcel.writeString(className)
        parcel.writeString(resourceId)
        parcel.writeString(text)
        parcel.writeString(contentDescription)
        parcel.writeString(hint)
        parcel.writeParcelable(bounds, flags)
        parcel.writeInt(visibility)
        parcel.writeByte(if (clickable) 1 else 0)
        parcel.writeByte(if (scrollable) 1 else 0)
        parcel.writeByte(if (focused) 1 else 0)
        parcel.writeByte(if (editable) 1 else 0)
        parcel.writeByte(if (checked) 1 else 0)
        parcel.writeByte(if (enabled) 1 else 0)
        parcel.writeStringArray(actionTypes)
        parcel.writeTypedArray(children, flags)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<UiNode> {
        override fun createFromParcel(parcel: Parcel): UiNode {
            return UiNode(parcel)
        }
        
        override fun newArray(size: Int): Array<UiNode?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * 可见性常量
 */
object Visibility {
    const val VISIBLE = 0      // 完全可见
    const val PARTIAL = 1      // 部分可见
    const val OFFSCREEN = 2    // 离屏（滚动可见）
    const val INVISIBLE = 3    // 不可见
}

/**
 * 动作类型常量
 */
object ActionType {
    const val CLICK = "click"
    const val LONG_CLICK = "longClick"
    const val SCROLL_DOWN = "scrollDown"
    const val SCROLL_UP = "scrollUp"
    const val SCROLL_LEFT = "scrollLeft"
    const val SCROLL_RIGHT = "scrollRight"
    const val INPUT_TEXT = "inputText"
    const val CLEAR_TEXT = "clearText"
    const val BACK = "back"
    const val SELECT = "select"
    const val EXPAND = "expand"
    const val COLLAPSE = "collapse"
}

/**
 * A2.1 UI树服务 - UI快照数据模型
 * 
 * 职责：
 * 1. 封装完整的UI树快照
 * 2. 记录快照元数据（时间戳、焦点窗口）
 * 3. 标记安全区域（不可见内容）
 * 4. 提供信任级别标记
 */
data class UiSnapshot(
    /**
     * 快照ID（用于审计关联和增量追踪）
     */
    val snapshotId: Long,
    
    /**
     * 显示ID（通常为0，多屏场景使用）
     */
    val displayId: Int,
    
    /**
     * 快照时间戳（SystemClock.elapsedRealtimeNanos）
     */
    val timestampRealtimeNanos: Long,
    
    /**
     * 焦点应用包名
     */
    val focusedPackage: String?,
    
    /**
     * 焦点Activity名称
     */
    val focusedActivity: String?,
    
    /**
     * UI树根节点
     */
    val root: UiNode,
    
    /**
     * 安全窗口区域（Agent视为不可见）
     * 涵盖支付、DRM等安全窗口
     */
    val secureRegions: Array<Rect>,
    
    /**
     * 信任级别
     * TRUSTED_SYSTEM / USER_INPUT / APP_CONTENT / WEB_CONTENT
     */
    val trust: TrustLevel
) : Parcelable {
    
    /**
     * 查找节点
     */
    fun findNode(nodeId: String): UiNode? {
        return root.findNodeById(nodeId)
    }
    
    /**
     * 获取所有节点（扁平化）
     */
    fun getAllNodes(): List<UiNode> {
        return root.getAllDescendants()
    }
    
    /**
     * 获取可交互节点
     */
    fun getInteractiveNodes(): List<UiNode> {
        return getAllNodes().filter { it.isInteractive() }
    }
    
    /**
     * 获取可见节点
     */
    fun getVisibleNodes(): List<UiNode> {
        return getAllNodes().filter { it.isVisible() }
    }
    
    /**
     * 检查区域是否在安全区域内
     */
    fun isInSecureRegion(bounds: Rect): Boolean {
        return secureRegions.any { secureRect ->
            Rect.intersects(secureRect, bounds)
        }
    }
    
    /**
     * 获取统计信息
     */
    fun getStats(): SnapshotStats {
        val allNodes = getAllNodes()
        return SnapshotStats(
            totalNodes = allNodes.size,
            maxDepth = root.getDepth(),
            interactiveNodes = allNodes.count { it.isInteractive() },
            visibleNodes = allNodes.count { it.isVisible() },
            editableNodes = allNodes.count { it.editable },
            secureRegionCount = secureRegions.size
        )
    }
    
    // Parcelable 实现
    constructor(parcel: Parcel) : this(
        snapshotId = parcel.readLong(),
        displayId = parcel.readInt(),
        timestampRealtimeNanos = parcel.readLong(),
        focusedPackage = parcel.readString(),
        focusedActivity = parcel.readString(),
        root = parcel.readParcelable(UiNode::class.java.classLoader)!!,
        secureRegions = parcel.createTypedArray(Rect.CREATOR)!!,
        trust = TrustLevel.valueOf(parcel.readString()!!)
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(snapshotId)
        parcel.writeInt(displayId)
        parcel.writeLong(timestampRealtimeNanos)
        parcel.writeString(focusedPackage)
        parcel.writeString(focusedActivity)
        parcel.writeParcelable(root, flags)
        parcel.writeTypedArray(secureRegions, flags)
        parcel.writeString(trust.name)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<UiSnapshot> {
        override fun createFromParcel(parcel: Parcel): UiSnapshot {
            return UiSnapshot(parcel)
        }
        
        override fun newArray(size: Int): Array<UiSnapshot?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * 信任级别枚举
 */
enum class TrustLevel {
    TRUSTED_SYSTEM,    // 系统自身产出（低危）
    USER_INPUT,        // 用户直接输入（中危）
    APP_CONTENT,       // 应用内容（高危，可能含提示注入）
    WEB_CONTENT        // 网页/远程内容（最高危）
}

/**
 * 快照统计信息
 */
data class SnapshotStats(
    val totalNodes: Int,
    val maxDepth: Int,
    val interactiveNodes: Int,
    val visibleNodes: Int,
    val editableNodes: Int,
    val secureRegionCount: Int
)

/**
 * UI树请求参数
 */
data class UiTreeRequest(
    val displayId: Int = 0,
    val maxDepth: Int = 32,              // 树深度上限（防御恶意深树）
    val includeOffscreen: Boolean = false, // 是否包含离屏元素
    val maskPii: Boolean = true,          // 是否掩码PII
    val maxWaitMs: Long = 200             // 快照一致性等待上界
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        displayId = parcel.readInt(),
        maxDepth = parcel.readInt(),
        includeOffscreen = parcel.readByte() != 0.toByte(),
        maskPii = parcel.readByte() != 0.toByte(),
        maxWaitMs = parcel.readLong()
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(displayId)
        parcel.writeInt(maxDepth)
        parcel.writeByte(if (includeOffscreen) 1 else 0)
        parcel.writeByte(if (maskPii) 1 else 0)
        parcel.writeLong(maxWaitMs)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<UiTreeRequest> {
        override fun createFromParcel(parcel: Parcel): UiTreeRequest {
            return UiTreeRequest(parcel)
        }
        
        override fun newArray(size: Int): Array<UiTreeRequest?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * 服务状态码
 */
object UiTreeStatus {
    const val STATUS_OK = 0
    const val STATUS_DENIED = 1              // 策略/权限拒绝
    const val STATUS_STALE = 2               // 快照已过期
    const val STATUS_SERVICE_UNAVAIL = 3     // 目标窗口不可达
    const val STATUS_INTERNAL_ERROR = -1     // 内部错误
}
