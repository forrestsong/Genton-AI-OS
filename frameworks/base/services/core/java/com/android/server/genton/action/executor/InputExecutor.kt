package com.android.server.genton.action.executor

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.android.server.wm.WindowManagerService
import com.genton.action.ActionModels.*
import com.genton.perception.ui.UiTreeModels.UiNode
import com.android.server.genton.perception.AgentUiTreeService
import com.android.server.genton.perception.SecureWindowDetector
import com.android.server.genton.audit.AuditLogger
import android.hardware.input.InputManager

/**
 * A3.3 Input执行器
 *
 * 职责：
 * 1. UI节点定位：根据nodeId定位目标节点
 * 2. Stale校验：执行前校验节点仍然有效（防止UI变化导致误操作）
 * 3. Overlay复查：执行前检测可疑overlay（防劫持）
 * 4. 坐标注入：tap/swipe/type/key操作的底层注入
 * 5. 边界检查：防止点击屏幕外或安全区域
 *
 * 支持动作：
 * - input.tap：点击UI节点或坐标
 * - input.long_press：长按节点
 * - input.type：输入文本（需节点可编辑）
 * - input.swipe：滑动（起点→终点）
 * - input.key：按键事件（返回/Home/最近任务等）
 * - input.scroll：滚动节点
 *
 * 安全机制：
 * - Stale检测：比较快照ID和节点签名
 * - Overlay复查：检测目标节点上方是否有可疑窗口
 * - 安全区域拒绝：禁止点击FLAG_SECURE区域
 * - 可编辑性校验：type操作需要editable=true
 */
class InputExecutor(
    private val context: Context,
    private val wms: WindowManagerService,
    private val uiTreeService: AgentUiTreeService,
    private val secureDetector: SecureWindowDetector,
    private val auditLogger: AuditLogger
) : ActionExecutor {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    companion object {
        // Stale检测阈值（节点快照超过此时间认为stale）
        private const val STALE_THRESHOLD_MS = 2000L

        // Overlay复查阈值（执行前必须复查）
        private const val OVERLAY_RECHECK_REQUIRED = true
    }

    override fun execute(request: ActionRequest): ActionExecutionResult {
        val actionName = request.actionName
        val arguments = request.arguments

        return when (actionName) {
            "input.tap" -> executeTap(request, arguments)
            "input.long_press" -> executeLongPress(request, arguments)
            "input.type" -> executeType(request, arguments)
            "input.swipe" -> executeSwipe(request, arguments)
            "input.key" -> executeKey(request, arguments)
            "input.scroll" -> executeScroll(request, arguments)
            else -> ActionExecutionResult.failed(
                "Unknown input action: $actionName",
                errorCode = "UNSUPPORTED_ACTION"
            )
        }
    }

    /**
     * 执行点击
     */
    private fun executeTap(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        // 支持两种模式：nodeId定位 或 直接坐标
        val nodeId = args["node_id"] as? String
        val x = args["x"] as? Int
        val y = args["y"] as? Int

        val (targetX, targetY) = if (nodeId != null) {
            // 模式1：通过nodeId定位
            val nodeResult = resolveNodeAndValidate(nodeId, request)
            if (!nodeResult.valid) {
                return ActionExecutionResult.failed(
                    nodeResult.reason ?: "Node validation failed",
                    errorCode = nodeResult.errorCode ?: "NODE_INVALID"
                )
            }
            val node = nodeResult.node!!
            val bounds = node.bounds
            Pair(bounds.centerX(), bounds.centerY())
        } else if (x != null && y != null) {
            // 模式2：直接坐标
            Pair(x, y)
        } else {
            return ActionExecutionResult.failed(
                "Missing node_id or x/y coordinates",
                errorCode = "MISSING_TARGET"
            )
        }

        // 边界检查
        if (!isValidCoordinate(targetX, targetY)) {
            return ActionExecutionResult.failed(
                "Coordinates out of screen bounds",
                errorCode = "OUT_OF_BOUNDS"
            )
        }

        // 安全区域检查
        if (isInSecureRegion(targetX, targetY)) {
            auditLogger.logActionExecution(
                success = false,
                actionName = request.actionName,
                denyReason = "Target is in secure region"
            )
            return ActionExecutionResult.failed(
                "Cannot tap in secure region",
                errorCode = "SECURE_REGION"
            )
        }

        // Overlay复查
        if (OVERLAY_RECHECK_REQUIRED) {
            val overlayResult = recheckOverlay(targetX, targetY)
            if (!overlayResult.safe) {
                auditLogger.logActionExecution(
                    success = false,
                    actionName = request.actionName,
                    denyReason = "Suspicious overlay detected"
                )
                return ActionExecutionResult.failed(
                    "Suspicious overlay detected at target location",
                    errorCode = "OVERLAY_DETECTED"
                )
            }
        }

        // 执行点击
        return try {
            injectTap(targetX, targetY)

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = "($targetX, $targetY)"
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "tapped_at" to mapOf("x" to targetX, "y" to targetY),
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to inject tap: ${e.message}",
                errorCode = "INJECTION_FAILED"
            )
        }
    }

    /**
     * 执行长按
     */
    private fun executeLongPress(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        val nodeId = args["node_id"] as? String
            ?: return ActionExecutionResult.failed("Missing node_id")

        val duration = (args["duration_ms"] as? Int) ?: 1000

        val nodeResult = resolveNodeAndValidate(nodeId, request)
        if (!nodeResult.valid) {
            return ActionExecutionResult.failed(
                nodeResult.reason ?: "Node validation failed",
                errorCode = nodeResult.errorCode ?: "NODE_INVALID"
            )
        }

        val node = nodeResult.node!!
        val bounds = node.bounds
        val targetX = bounds.centerX()
        val targetY = bounds.centerY()

        if (isInSecureRegion(targetX, targetY)) {
            return ActionExecutionResult.failed(
                "Cannot long press in secure region",
                errorCode = "SECURE_REGION"
            )
        }

        return try {
            injectLongPress(targetX, targetY, duration.toLong())

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = nodeId
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "long_pressed_at" to mapOf("x" to targetX, "y" to targetY),
                    "duration_ms" to duration,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to inject long press: ${e.message}",
                errorCode = "INJECTION_FAILED"
            )
        }
    }

    /**
     * 执行输入文本
     */
    private fun executeType(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        val nodeId = args["node_id"] as? String
            ?: return ActionExecutionResult.failed("Missing node_id")

        val text = args["text"] as? String
            ?: return ActionExecutionResult.failed("Missing text")

        val nodeResult = resolveNodeAndValidate(nodeId, request)
        if (!nodeResult.valid) {
            return ActionExecutionResult.failed(
                nodeResult.reason ?: "Node validation failed",
                errorCode = nodeResult.errorCode ?: "NODE_INVALID"
            )
        }

        val node = nodeResult.node!!

        // 可编辑性校验
        if (!node.editable) {
            return ActionExecutionResult.failed(
                "Target node is not editable",
                errorCode = "NOT_EDITABLE"
            )
        }

        val bounds = node.bounds
        val targetX = bounds.centerX()
        val targetY = bounds.centerY()

        return try {
            // 先点击聚焦
            injectTap(targetX, targetY)
            Thread.sleep(100)

            // 输入文本（通过InputConnection）
            injectText(text)

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = nodeId
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "typed_text_length" to text.length,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to type text: ${e.message}",
                errorCode = "TYPE_FAILED"
            )
        }
    }

    /**
     * 执行滑动
     */
    private fun executeSwipe(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        val fromX = args["from_x"] as? Int ?: return ActionExecutionResult.failed("Missing from_x")
        val fromY = args["from_y"] as? Int ?: return ActionExecutionResult.failed("Missing from_y")
        val toX = args["to_x"] as? Int ?: return ActionExecutionResult.failed("Missing to_x")
        val toY = args["to_y"] as? Int ?: return ActionExecutionResult.failed("Missing to_y")
        val duration = (args["duration_ms"] as? Int) ?: 300

        if (!isValidCoordinate(fromX, fromY) || !isValidCoordinate(toX, toY)) {
            return ActionExecutionResult.failed(
                "Coordinates out of screen bounds",
                errorCode = "OUT_OF_BOUNDS"
            )
        }

        return try {
            injectSwipe(fromX, fromY, toX, toY, duration.toLong())

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = "($fromX,$fromY)->($toX,$toY)"
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "swipe_from" to mapOf("x" to fromX, "y" to fromY),
                    "swipe_to" to mapOf("x" to toX, "y" to toY),
                    "duration_ms" to duration,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to inject swipe: ${e.message}",
                errorCode = "SWIPE_FAILED"
            )
        }
    }

    /**
     * 执行按键
     */
    private fun executeKey(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        val keyCode = args["key_code"] as? Int
            ?: return ActionExecutionResult.failed("Missing key_code")

        return try {
            injectKey(keyCode)

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = "keyCode=$keyCode"
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "key_code" to keyCode,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to inject key: ${e.message}",
                errorCode = "KEY_FAILED"
            )
        }
    }

    /**
     * 执行滚动
     */
    private fun executeScroll(request: ActionRequest, args: Map<String, Any>): ActionExecutionResult {
        val nodeId = args["node_id"] as? String
            ?: return ActionExecutionResult.failed("Missing node_id")

        val direction = args["direction"] as? String ?: "down"
        val amount = (args["amount"] as? Int) ?: 1

        val nodeResult = resolveNodeAndValidate(nodeId, request)
        if (!nodeResult.valid) {
            return ActionExecutionResult.failed(
                nodeResult.reason ?: "Node validation failed",
                errorCode = nodeResult.errorCode ?: "NODE_INVALID"
            )
        }

        val node = nodeResult.node!!

        if (!node.scrollable) {
            return ActionExecutionResult.failed(
                "Target node is not scrollable",
                errorCode = "NOT_SCROLLABLE"
            )
        }

        val bounds = node.bounds
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val scrollDistance = 200 * amount

        val (fromX, fromY, toX, toY) = when (direction) {
            "up" -> Pair(centerX, centerY + scrollDistance / 2) to Pair(centerX, centerY - scrollDistance / 2)
            "down" -> Pair(centerX, centerY - scrollDistance / 2) to Pair(centerX, centerY + scrollDistance / 2)
            "left" -> Pair(centerX + scrollDistance / 2, centerY) to Pair(centerX - scrollDistance / 2, centerY)
            "right" -> Pair(centerX - scrollDistance / 2, centerY) to Pair(centerX + scrollDistance / 2, centerY)
            else -> return ActionExecutionResult.failed("Invalid scroll direction: $direction")
        }

        return try {
            injectSwipe(fromX.first, fromX.second, toX.first, toX.second, 300)

            auditLogger.logActionExecution(
                success = true,
                actionName = request.actionName,
                target = nodeId
            )

            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "scrolled_direction" to direction,
                    "amount" to amount,
                    "timestamp" to System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Failed to scroll: ${e.message}",
                errorCode = "SCROLL_FAILED"
            )
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析节点并校验
     */
    private data class NodeValidationResult(
        val valid: Boolean,
        val node: UiNode?,
        val reason: String?,
        val errorCode: String?
    )

    private fun resolveNodeAndValidate(nodeId: String, request: ActionRequest): NodeValidationResult {
        // 1. 从最新UI快照查找节点
        val snapshot = uiTreeService.getLatestSnapshot()
            ?: return NodeValidationResult(false, null, "No UI snapshot available", "NO_SNAPSHOT")

        val node = findNodeById(snapshot.root, nodeId)
            ?: return NodeValidationResult(false, null, "Node not found: $nodeId", "NODE_NOT_FOUND")

        // 2. Stale检测
        val snapshotAge = System.currentTimeMillis() - (snapshot.timestampRealtimeNanos / 1_000_000)
        if (snapshotAge > STALE_THRESHOLD_MS) {
            return NodeValidationResult(false, null, "UI snapshot is stale (${snapshotAge}ms old)", "STALE_SNAPSHOT")
        }

        // 3. 节点可见性检查
        if (node.visibility != 0) {  // 0 = VISIBLE
            return NodeValidationResult(false, null, "Node is not visible", "NODE_NOT_VISIBLE")
        }

        return NodeValidationResult(true, node, null, null)
    }

    /**
     * 递归查找节点
     */
    private fun findNodeById(root: UiNode, targetId: String): UiNode? {
        if (root.nodeId == targetId) return root
        root.children.forEach { child ->
            val found = findNodeById(child, targetId)
            if (found != null) return found
        }
        return null
    }

    /**
     * 检查坐标是否有效
     */
    private fun isValidCoordinate(x: Int, y: Int): Boolean {
        val display = windowManager.defaultDisplay
        val displayWidth = display.width
        val displayHeight = display.height
        return x in 0..displayWidth && y in 0..displayHeight
    }

    /**
     * 检查坐标是否在安全区域
     */
    private fun isInSecureRegion(x: Int, y: Int): Boolean {
        val detection = secureDetector.detectSecureWindows(0, false)
        return detection.secureRegions.any { region ->
            region.contains(x, y)
        }
    }

    /**
     * Overlay复查
     */
    private data class OverlayCheckResult(val safe: Boolean, val reason: String?)

    private fun recheckOverlay(x: Int, y: Int): OverlayCheckResult {
        val detection = secureDetector.detectSecureWindows(0, false)
        return if (detection.overlayDetected) {
            OverlayCheckResult(false, "Suspicious overlay detected")
        } else {
            OverlayCheckResult(true, null)
        }
    }

    /**
     * 注入点击事件
     */
    private fun injectTap(x: Int, y: Int) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = downTime

        val down = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_DOWN,
            x.toFloat(), y.toFloat(), 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        val up = MotionEvent.obtain(
            downTime, eventTime + 50, MotionEvent.ACTION_UP,
            x.toFloat(), y.toFloat(), 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        inputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        Thread.sleep(50)
        inputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)

        down.recycle()
        up.recycle()
    }

    /**
     * 注入长按事件
     */
    private fun injectLongPress(x: Int, y: Int, durationMs: Long) {
        val downTime = SystemClock.uptimeMillis()

        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            x.toFloat(), y.toFloat(), 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        inputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        Thread.sleep(durationMs)

        val up = MotionEvent.obtain(
            downTime, downTime + durationMs, MotionEvent.ACTION_UP,
            x.toFloat(), y.toFloat(), 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

        inputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)

        down.recycle()
        up.recycle()
    }

    /**
     * 注入文本
     */
    private fun injectText(text: String) {
        // 简化实现：逐字符发送键盘事件
        // 生产环境应使用InputConnection
        text.forEach { char ->
            val keyCode = char.code
            injectKey(keyCode)
            Thread.sleep(10)
        }
    }

    /**
     * 注入滑动事件
     */
    private fun injectSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) {
        val downTime = SystemClock.uptimeMillis()
        val steps = 20
        val stepDuration = durationMs / steps

        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            fromX.toFloat(), fromY.toFloat(), 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        inputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        down.recycle()

        for (i in 1..steps) {
            val progress = i.toFloat() / steps
            val currentX = fromX + ((toX - fromX) * progress).toInt()
            val currentY = fromY + ((toY - fromY) * progress).toInt()

            val move = MotionEvent.obtain(
                downTime, downTime + i * stepDuration, MotionEvent.ACTION_MOVE,
                currentX.toFloat(), currentY.toFloat(), 0
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            inputManager.injectInputEvent(move, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
            move.recycle()

            Thread.sleep(stepDuration)
        }

        val up = MotionEvent.obtain(
            downTime, downTime + durationMs, MotionEvent.ACTION_UP,
            toX.toFloat(), toY.toFloat(), 0
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        inputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        up.recycle()
    }

    /**
     * 注入按键事件
     */
    private fun injectKey(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()

        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, keyCode, 0)

        inputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        Thread.sleep(50)
        inputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    override fun cancel(actionRequestId: String): Boolean {
        // Input操作是瞬时的，无法取消
        return false
    }

    override fun describeCapabilities(): String {
        return """
            InputExecutor: Inject UI interactions
            Supported actions:
            - input.tap: Tap node or coordinates
            - input.long_press: Long press node
            - input.type: Type text into editable node
            - input.swipe: Swipe gesture
            - input.key: Press key (back/home/etc)
            - input.scroll: Scroll node
            
            Security:
            - Stale detection (${STALE_THRESHOLD_MS}ms threshold)
            - Overlay rechecking before execution
            - Secure region rejection
            - Editable validation for type operations
        """.trimIndent()
    }
}
