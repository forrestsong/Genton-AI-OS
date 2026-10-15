package com.android.server.genton.perception

import android.graphics.Rect
import android.view.WindowManager
import android.view.Display
import com.android.server.wm.WindowManagerService
import com.android.server.wm.WindowState
import com.genton.perception.screen.ScreenModels.SecureState

/**
 * A2.2 FLAG_SECURE检测器
 *
 * 职责：
 * 1. 检测当前显示的所有FLAG_SECURE窗口
 * 2. 检测可疑overlay窗口（TYPE_APPLICATION_OVERLAY层叠在敏感内容上）
 * 3. 生成需要掩码的区域列表
 * 4. 判断是否允许捕获（全屏安全拒绝）
 *
 * 协同：
 * - 与WindowManagerService深度集成
 * - 与UITreeService共享安全区域信息
 * - 与ACBPolicyEngine的capture_screen能力令牌协同鉴权
 */
class SecureWindowDetector(
    private val wms: WindowManagerService
) {
    /**
     * 检测结果
     */
    data class SecureDetectionResult(
        val secureState: Int,                   // SecureState.NONE/PARTIAL/FULL
        val secureRegions: Array<Rect>,         // 需要掩码的区域
        val overlayDetected: Boolean,           // 是否检测到可疑overlay
        val allowCapture: Boolean,              // 是否允许捕获
        val denyReason: String?                 // 拒绝原因（审计日志）
    ) {
        companion object {
            fun allowWithNoSecure() = SecureDetectionResult(
                secureState = SecureState.NONE,
                secureRegions = emptyArray(),
                overlayDetected = false,
                allowCapture = true,
                denyReason = null
            )

            fun allowWithPartialSecure(regions: Array<Rect>) = SecureDetectionResult(
                secureState = SecureState.PARTIAL,
                secureRegions = regions,
                overlayDetected = false,
                allowCapture = true,
                denyReason = null
            )

            fun denyFullScreenSecure() = SecureDetectionResult(
                secureState = SecureState.FULL,
                secureRegions = emptyArray(),
                overlayDetected = false,
                allowCapture = false,
                denyReason = "Full-screen FLAG_SECURE content detected"
            )

            fun denyOverlay(secureRegions: Array<Rect>) = SecureDetectionResult(
                secureState = if (secureRegions.isEmpty()) SecureState.NONE else SecureState.PARTIAL,
                secureRegions = secureRegions,
                overlayDetected = true,
                allowCapture = false,
                denyReason = "Suspicious overlay detected over secure content"
            )
        }
    }

    /**
     * 窗口安全信息
     */
    private data class WindowSecurityInfo(
        val window: WindowState,
        val bounds: Rect,
        val hasSecureFlag: Boolean,
        val isOverlay: Boolean,
        val zOrder: Int
    )

    /**
     * 检测安全窗口
     *
     * @param displayId 显示ID
     * @param requireUserAuthorizedSecureContent 是否需要用户授权捕获安全内容（TEE授权）
     * @return 检测结果
     */
    fun detectSecureWindows(
        displayId: Int,
        requireUserAuthorizedSecureContent: Boolean
    ): SecureDetectionResult {
        synchronized(wms.mGlobalLock) {
            // 1. 收集所有可见窗口的安全信息
            val windowInfos = collectVisibleWindowSecurityInfo(displayId)

            if (windowInfos.isEmpty()) {
                return SecureDetectionResult.allowWithNoSecure()
            }

            // 2. 检测可疑overlay
            val suspiciousOverlay = detectSuspiciousOverlay(windowInfos)
            if (suspiciousOverlay) {
                val secureRegions = windowInfos
                    .filter { it.hasSecureFlag }
                    .map { it.bounds }
                    .toTypedArray()
                return SecureDetectionResult.denyOverlay(secureRegions)
            }

            // 3. 分类安全窗口
            val secureWindows = windowInfos.filter { it.hasSecureFlag }
            if (secureWindows.isEmpty()) {
                return SecureDetectionResult.allowWithNoSecure()
            }

            // 4. 检查是否全屏安全内容
            val displayBounds = getDisplayBounds(displayId)
            val isFullScreenSecure = checkIfFullScreenSecure(secureWindows, displayBounds)

            if (isFullScreenSecure) {
                // 全屏安全且未授权 → 拒绝捕获
                if (!requireUserAuthorizedSecureContent) {
                    return SecureDetectionResult.denyFullScreenSecure()
                }
                // 已授权 → 允许捕获但标记风险
                return SecureDetectionResult(
                    secureState = SecureState.FULL,
                    secureRegions = emptyArray(),
                    overlayDetected = false,
                    allowCapture = true,
                    denyReason = null
                )
            }

            // 5. 部分安全 → 返回掩码区域
            val maskedRegions = mergeSecureRegions(secureWindows.map { it.bounds })
            return SecureDetectionResult.allowWithPartialSecure(maskedRegions)
        }
    }

    /**
     * 收集可见窗口的安全信息
     */
    private fun collectVisibleWindowSecurityInfo(displayId: Int): List<WindowSecurityInfo> {
        val windowInfos = mutableListOf<WindowSecurityInfo>()

        // 遍历WindowManagerService的窗口列表
        val displayContent = wms.mRoot.getDisplayContent(displayId) ?: return emptyList()

        displayContent.forAllWindows({ window ->
            if (window.isVisible && !window.isAnimating) {
                val bounds = Rect()
                window.getBounds(bounds)

                windowInfos.add(
                    WindowSecurityInfo(
                        window = window,
                        bounds = bounds,
                        hasSecureFlag = window.attrs.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                        isOverlay = window.attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        zOrder = window.mLayer
                    )
                )
            }
        }, true /* traverseTopToBottom */)

        return windowInfos
    }

    /**
     * 检测可疑overlay
     *
     * 规则：TYPE_APPLICATION_OVERLAY层叠在FLAG_SECURE窗口之上
     */
    private fun detectSuspiciousOverlay(windowInfos: List<WindowSecurityInfo>): Boolean {
        val secureWindows = windowInfos.filter { it.hasSecureFlag }
        val overlays = windowInfos.filter { it.isOverlay }

        if (secureWindows.isEmpty() || overlays.isEmpty()) {
            return false
        }

        // 检查是否有overlay在secure窗口上方且有重叠
        overlays.forEach { overlay ->
            secureWindows.forEach { secure ->
                // overlay的zOrder更高（在上方）且区域重叠
                if (overlay.zOrder > secure.zOrder && Rect.intersects(overlay.bounds, secure.bounds)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 检查是否全屏安全内容
     *
     * 规则：安全窗口覆盖显示区域>=90%
     */
    private fun checkIfFullScreenSecure(secureWindows: List<WindowSecurityInfo>, displayBounds: Rect): Boolean {
        val displayArea = displayBounds.width() * displayBounds.height()
        if (displayArea <= 0) return false

        // 计算安全窗口的并集面积
        val unionBounds = mergeSecureRegions(secureWindows.map { it.bounds })
        val secureArea = unionBounds.sumOf { it.width() * it.height() }

        return secureArea >= displayArea * 0.9
    }

    /**
     * 合并重叠的安全区域
     *
     * 使用简单的并集算法，避免过多碎片化掩码
     */
    private fun mergeSecureRegions(regions: List<Rect>): Array<Rect> {
        if (regions.isEmpty()) return emptyArray()
        if (regions.size == 1) return arrayOf(regions[0])

        val merged = mutableListOf<Rect>()
        val sorted = regions.sortedBy { it.left }

        var current = Rect(sorted[0])

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (Rect.intersects(current, next) || isAdjacent(current, next)) {
                // 合并
                current.union(next)
            } else {
                // 不重叠，保存当前并开始新区域
                merged.add(Rect(current))
                current = Rect(next)
            }
        }
        merged.add(current)

        return merged.toTypedArray()
    }

    /**
     * 检查两个矩形是否相邻（间隔<16px）
     */
    private fun isAdjacent(r1: Rect, r2: Rect): Boolean {
        val threshold = 16
        return (r1.right >= r2.left - threshold && r1.left <= r2.right + threshold &&
                r1.bottom >= r2.top - threshold && r1.top <= r2.bottom + threshold)
    }

    /**
     * 获取显示边界
     */
    private fun getDisplayBounds(displayId: Int): Rect {
        val displayContent = wms.mRoot.getDisplayContent(displayId)
        val bounds = Rect()
        displayContent?.getBounds(bounds)
        return bounds
    }

    /**
     * 快速检查是否存在任何安全窗口
     *
     * 用于审计日志快速预判
     */
    fun hasAnySecureWindow(displayId: Int): Boolean {
        synchronized(wms.mGlobalLock) {
            val windowInfos = collectVisibleWindowSecurityInfo(displayId)
            return windowInfos.any { it.hasSecureFlag }
        }
    }
}
