package com.genton.brain.prediction.gate

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.genton.brain.prediction.PrefetchActionType

/**
 * 权限检查器
 * 
 * 检查目标 App 的权限状态，预判可能触发的权限请求：
 * - 运行时敏感权限（相机/麦克风/定位等）
 * - 存储权限
 * - 特殊权限（无障碍/悬浮窗等）
 * 
 * 未授权场景采取降级策略：
 * - 预创建 Activity → 降级为仅预热
 * - 预取数据 → 跳过
 * 
 * @author AI Native OS Team
 * @since A5
 */
class PermissionChecker(private val context: Context) {
    companion object {
        private const val TAG = "PermissionChecker"
    }
    
    /**
     * 敏感运行时权限
     */
    object SensitivePermissions {
        val RUNTIME_PERMISSIONS = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        
        val STORAGE_PERMISSIONS = setOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        
        val ALL_SENSITIVE = RUNTIME_PERMISSIONS + STORAGE_PERMISSIONS
    }
    
    /**
     * 检查 App 权限状态
     * 
     * @param packageName 目标 App 包名
     * @param actionType 预取动作类型
     * @return 权限检查结果
     */
    fun checkAppPermissions(
        packageName: String,
        actionType: PrefetchActionType
    ): PermissionCheckResult {
        try {
            val packageManager = context.packageManager
            val packageInfo = try {
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            } catch (e: PackageManager.NameNotFoundException) {
                return PermissionCheckResult.Unknown("Package not found: $packageName")
            }
            
            // 1. 获取 App 请求的权限
            val requestedPermissions = packageInfo.requestedPermissions ?: emptyArray()
            if (requestedPermissions.isEmpty()) {
                // 无权限请求，放行
                return PermissionCheckResult.Granted
            }
            
            // 2. 获取已授权的权限
            val grantedPermissions = getGrantedPermissions(packageInfo)
            
            // 3. 根据动作类型判断需要的权限
            val requiredPermissions = getRequiredPermissions(actionType, requestedPermissions)
            
            if (requiredPermissions.isEmpty()) {
                // 该动作不需要敏感权限
                return PermissionCheckResult.Granted
            }
            
            // 4. 检查是否有未授权的敏感权限
            val ungrantedPermissions = requiredPermissions.filter { it !in grantedPermissions }
            
            return if (ungrantedPermissions.isEmpty()) {
                PermissionCheckResult.Granted
            } else {
                PermissionCheckResult.Denied(ungrantedPermissions)
            }
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to check app permissions", e)
            return PermissionCheckResult.Unknown("Permission check failed: ${e.message}")
        }
    }
    
    /**
     * 检查是否为系统或特权 App
     */
    fun isSystemOrPrivilegedApp(packageName: String): Boolean {
        try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            
            val isSystemApp = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            
            return isSystemApp || isUpdatedSystemApp
        } catch (e: Exception) {
            return false
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 获取已授权的权限集合
     */
    private fun getGrantedPermissions(packageInfo: PackageInfo): Set<String> {
        val requestedPermissions = packageInfo.requestedPermissions ?: return emptySet()
        val permissionFlags = packageInfo.requestedPermissionsFlags ?: return emptySet()
        
        return requestedPermissions.filterIndexed { index, _ ->
            (permissionFlags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        }.toSet()
    }
    
    /**
     * 根据动作类型获取需要的权限
     */
    private fun getRequiredPermissions(
        actionType: PrefetchActionType,
        requestedPermissions: Array<String>
    ): List<String> {
        return when (actionType) {
            PrefetchActionType.PREWARM_APP -> {
                // 预热不需要权限
                emptyList()
            }
            PrefetchActionType.PRECREATE_ACTIVITY -> {
                // 预创建可能触发运行时权限
                requestedPermissions.filter { it in SensitivePermissions.RUNTIME_PERMISSIONS }
            }
            PrefetchActionType.PREFETCH_DATA -> {
                // 预取数据可能触发存储/网络权限
                requestedPermissions.filter { 
                    it in SensitivePermissions.STORAGE_PERMISSIONS ||
                    it == Manifest.permission.INTERNET
                }
            }
            PrefetchActionType.PRELOAD_MACRO -> {
                // 预装载宏可能触发多种权限
                requestedPermissions.filter { it in SensitivePermissions.ALL_SENSITIVE }
            }
            PrefetchActionType.PRESUBSCRIBE_UI -> {
                // 预订阅 UI 需要无障碍服务（非运行时权限，单独检查）
                emptyList()
            }
            else -> emptyList()
        }
    }
}

/**
 * 权限检查结果
 */
sealed class PermissionCheckResult {
    /**
     * 权限已授予
     */
    object Granted : PermissionCheckResult()
    
    /**
     * 权限被拒绝
     * @param missingPermissions 缺失的权限列表
     */
    data class Denied(val missingPermissions: List<String>) : PermissionCheckResult()
    
    /**
     * 无法检查（包不存在/异常）
     * @param reason 原因
     */
    data class Unknown(val reason: String) : PermissionCheckResult()
}
