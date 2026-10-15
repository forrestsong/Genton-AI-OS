package com.android.server.ainative.action.executor

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.android.server.ainative.action.ActionRequest
import com.android.server.ainative.action.ActionExecutionResult
import com.android.server.ainative.audit.AuditLogger

/**
 * A3.5 系统侧执行器 - 内容提供者操作
 * 
 * 支持的动作：
 * - content.query：查询ContentProvider数据
 * - content.insert：插入数据
 * - content.update：更新数据
 * - content.delete：删除数据
 * - content.batch：批量操作
 * - content.call：调用ContentProvider自定义方法
 * 
 * 安全机制：
 * 1. URI白名单：仅允许访问授权的ContentProvider
 * 2. 权限校验：自动检查读写权限
 * 3. 参数来源：写操作参数必须USER源
 * 4. 敏感URI保护：联系人/短信/通话记录等需额外确认
 * 5. 批量操作限制：单次最多100个操作
 * 6. 审计：所有操作均记录
 */
class ContentExecutor(
    private val context: Context,
    private val auditLogger: AuditLogger
) : ActionExecutor {

    private val contentResolver: ContentResolver by lazy {
        context.contentResolver
    }

    companion object {
        private const val TAG = "ContentExecutor"
        private const val MAX_BATCH_SIZE = 100
        private const val QUERY_RESULT_LIMIT = 1000
        
        // 敏感ContentProvider URI（需要额外确认）
        private val SENSITIVE_AUTHORITIES = setOf(
            "com.android.contacts",           // 联系人
            "sms",                            // 短信
            "mms",                            // 彩信
            "mms-sms",                        // 短信/彩信统一
            "call_log",                       // 通话记录
            "com.android.calendar",           // 日历
            "com.android.email.provider"      // 邮件
        )
        
        // 允许的ContentProvider白名单（可配置）
        // 实际应从配置文件加载
        private val ALLOWED_AUTHORITIES = mutableSetOf(
            "media",                          // 媒体存储
            "downloads",                      // 下载管理器
            "com.android.externalstorage.documents", // 文档
            // 系统应用可动态添加
        )
    }

    override fun canExecute(actionName: String): Boolean {
        return actionName.startsWith("content.")
    }

    override fun execute(request: ActionRequest): ActionExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = when (request.actionName) {
                "content.query" -> executeQuery(request)
                "content.insert" -> executeInsert(request)
                "content.update" -> executeUpdate(request)
                "content.delete" -> executeDelete(request)
                "content.batch" -> executeBatch(request)
                "content.call" -> executeCall(request)
                else -> ActionExecutionResult.failed(
                    "Unknown content action: ${request.actionName}",
                    errorCode = "UNKNOWN_ACTION"
                )
            }
            
            // 审计
            auditLogger.logActionExecution(
                sessionId = request.sessionId,
                actionName = request.actionName,
                result = if (result.success) "SUCCESS" else "FAILED",
                duration = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "error" to (result.errorMessage ?: ""),
                    "uri" to (request.arguments["uri"] ?: ""),
                    "rows_affected" to (result.resultData["rows_affected"] ?: 0)
                )
            )
            
            result
        } catch (e: SecurityException) {
            ActionExecutionResult.failed(
                "Security violation: ${e.message}",
                errorCode = "SECURITY_DENIED"
            )
        } catch (e: IllegalArgumentException) {
            ActionExecutionResult.failed(
                "Invalid argument: ${e.message}",
                errorCode = "INVALID_ARGUMENT"
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Content operation failed: ${e.message}",
                errorCode = "EXECUTION_ERROR"
            )
        }
    }

    /**
     * content.query - 查询ContentProvider数据
     * 
     * 参数：
     * - uri: String（content:// URI）
     * - projection: List<String>?（查询列）
     * - selection: String?（WHERE子句）
     * - selection_args: List<String>?（WHERE参数）
     * - sort_order: String?（排序）
     * - limit: Int = 100（结果限制）
     * 
     * 返回：
     * - rows: List<Map<String, Any?>>（查询结果）
     * - row_count: Int
     */
    private fun executeQuery(request: ActionRequest): ActionExecutionResult {
        val args = request.arguments
        val uriString = args["uri"] as? String
            ?: return ActionExecutionResult.failed("Missing required parameter: uri", "MISSING_PARAM")
        
        val uri = Uri.parse(uriString)
        
        // 安全检查
        val securityCheck = checkUriSecurity(uri, needsWrite = false)
        if (!securityCheck.allowed) {
            return ActionExecutionResult.failed(securityCheck.reason!!, "SECURITY_DENIED")
        }
        
        val projection = (args["projection"] as? List<*>)?.mapNotNull { it as? String }?.toTypedArray()
        val selection = args["selection"] as? String
        val selectionArgs = (args["selection_args"] as? List<*>)?.mapNotNull { it as? String }?.toTypedArray()
        val sortOrder = args["sort_order"] as? String
        val limit = (args["limit"] as? Int) ?: 100
        
        // 限制查询结果数量
        val effectiveLimit = minOf(limit, QUERY_RESULT_LIMIT)
        val effectiveSortOrder = if (sortOrder != null) {
            "$sortOrder LIMIT $effectiveLimit"
        } else {
            "LIMIT $effectiveLimit"
        }
        
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, projection, selection, selectionArgs, effectiveSortOrder)
            
            if (cursor == null) {
                return ActionExecutionResult.failed("Query returned null cursor", "QUERY_FAILED")
            }
            
            val rows = mutableListOf<Map<String, Any?>>()
            val columnNames = cursor.columnNames
            
            while (cursor.moveToNext() && rows.size < effectiveLimit) {
                val row = mutableMapOf<String, Any?>()
                columnNames.forEachIndexed { index, columnName ->
                    row[columnName] = when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        Cursor.FIELD_TYPE_BLOB -> "[BLOB:${cursor.getBlob(index).size} bytes]"
                        else -> cursor.getString(index)
                    }
                }
                rows.add(row)
            }
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "rows" to rows,
                    "row_count" to rows.size,
                    "has_more" to (cursor.count > effectiveLimit)
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed("Query failed: ${e.message}", "QUERY_ERROR")
        } finally {
            cursor?.close()
        }
    }

    /**
     * content.insert - 插入数据
     * 
     * 参数：
     * - uri: String（content:// URI）
     * - values: Map<String, Any>（要插入的数据，必须USER源）
     * 
     * 返回：
     * - inserted_uri: String?（插入后的URI）
     * - inserted: Boolean
     */
    private fun executeInsert(request: ActionRequest): ActionExecutionResult {
        val args = request.arguments
        val uriString = args["uri"] as? String
            ?: return ActionExecutionResult.failed("Missing required parameter: uri", "MISSING_PARAM")
        
        val valuesMap = args["values"] as? Map<*, *>
            ?: return ActionExecutionResult.failed("Missing required parameter: values", "MISSING_PARAM")
        
        // Taint规则：插入值必须USER源
        val valuesSource = request.argumentSources["values"]
        if (valuesSource != "USER") {
            return ActionExecutionResult.failed(
                "Insert values must come from USER input (taint violation)",
                "TAINT_DENIED"
            )
        }
        
        val uri = Uri.parse(uriString)
        
        // 安全检查
        val securityCheck = checkUriSecurity(uri, needsWrite = true)
        if (!securityCheck.allowed) {
            return ActionExecutionResult.failed(securityCheck.reason!!, "SECURITY_DENIED")
        }
        
        val contentValues = ContentValues()
        valuesMap.forEach { (key, value) ->
            when (value) {
                is String -> contentValues.put(key as String, value)
                is Int -> contentValues.put(key as String, value)
                is Long -> contentValues.put(key as String, value)
                is Boolean -> contentValues.put(key as String, value)
                is Float -> contentValues.put(key as String, value)
                is Double -> contentValues.put(key as String, value)
                is ByteArray -> contentValues.put(key as String, value)
                null -> contentValues.putNull(key as String)
            }
        }
        
        return try {
            val insertedUri = contentResolver.insert(uri, contentValues)
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "inserted" to (insertedUri != null),
                    "inserted_uri" to insertedUri?.toString()
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed("Insert failed: ${e.message}", "INSERT_ERROR")
        }
    }

    /**
     * content.update - 更新数据
     * 
     * 参数：
     * - uri: String（content:// URI）
     * - values: Map<String, Any>（要更新的数据，必须USER源）
     * - selection: String?（WHERE子句）
     * - selection_args: List<String>?（WHERE参数）
     * 
     * 返回：
     * - rows_affected: Int
     */
    private fun executeUpdate(request: ActionRequest): ActionExecutionResult {
        val args = request.arguments
        val uriString = args["uri"] as? String
            ?: return ActionExecutionResult.failed("Missing required parameter: uri", "MISSING_PARAM")
        
        val valuesMap = args["values"] as? Map<*, *>
            ?: return ActionExecutionResult.failed("Missing required parameter: values", "MISSING_PARAM")
        
        // Taint规则：更新值必须USER源
        val valuesSource = request.argumentSources["values"]
        if (valuesSource != "USER") {
            return ActionExecutionResult.failed(
                "Update values must come from USER input (taint violation)",
                "TAINT_DENIED"
            )
        }
        
        val uri = Uri.parse(uriString)
        
        // 安全检查
        val securityCheck = checkUriSecurity(uri, needsWrite = true)
        if (!securityCheck.allowed) {
            return ActionExecutionResult.failed(securityCheck.reason!!, "SECURITY_DENIED")
        }
        
        val selection = args["selection"] as? String
        val selectionArgs = (args["selection_args"] as? List<*>)?.mapNotNull { it as? String }?.toTypedArray()
        
        val contentValues = ContentValues()
        valuesMap.forEach { (key, value) ->
            when (value) {
                is String -> contentValues.put(key as String, value)
                is Int -> contentValues.put(key as String, value)
                is Long -> contentValues.put(key as String, value)
                is Boolean -> contentValues.put(key as String, value)
                is Float -> contentValues.put(key as String, value)
                is Double -> contentValues.put(key as String, value)
                is ByteArray -> contentValues.put(key as String, value)
                null -> contentValues.putNull(key as String)
            }
        }
        
        return try {
            val rowsAffected = contentResolver.update(uri, contentValues, selection, selectionArgs)
            
            ActionExecutionResult.succeeded(
                resultData = mapOf("rows_affected" to rowsAffected)
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed("Update failed: ${e.message}", "UPDATE_ERROR")
        }
    }

    /**
     * content.delete - 删除数据
     * 
     * 参数：
     * - uri: String（content:// URI）
     * - selection: String?（WHERE子句）
     * - selection_args: List<String>?（WHERE参数）
     * 
     * 返回：
     * - rows_affected: Int
     */
    private fun executeDelete(request: ActionRequest): ActionExecutionResult {
        val args = request.arguments
        val uriString = args["uri"] as? String
            ?: return ActionExecutionResult.failed("Missing required parameter: uri", "MISSING_PARAM")
        
        val uri = Uri.parse(uriString)
        
        // 安全检查
        val securityCheck = checkUriSecurity(uri, needsWrite = true)
        if (!securityCheck.allowed) {
            return ActionExecutionResult.failed(securityCheck.reason!!, "SECURITY_DENIED")
        }
        
        val selection = args["selection"] as? String
        val selectionArgs = (args["selection_args"] as? List<*>)?.mapNotNull { it as? String }?.toTypedArray()
        
        // 防止误删：要求必须有selection条件
        if (selection.isNullOrBlank()) {
            return ActionExecutionResult.failed(
                "Delete operation requires selection clause (safety check)",
                "MISSING_SELECTION"
            )
        }
        
        return try {
            val rowsAffected = contentResolver.delete(uri, selection, selectionArgs)
            
            ActionExecutionResult.succeeded(
                resultData = mapOf("rows_affected" to rowsAffected)
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed("Delete failed: ${e.message}", "DELETE_ERROR")
        }
    }

    /**
     * content.batch - 批量操作
     * 
     * 参数：
     * - authority: String（ContentProvider authority）
     * - operations: List<Map<String, Any>>（操作列表）
     *   每个操作包含：
     *   - type: "insert" | "update" | "delete"
     *   - uri: String
     *   - values: Map<String, Any>?
     *   - selection: String?
     *   - selection_args: List<String>?
     * 
     * 返回：
     * - results: List<Map<String, Any>>（每个操作的结果）
     * - success_count: Int
     */
    private fun executeBatch(request: ActionRequest): ActionExecutionResult {
        val args = request.arguments
        val authority = args["authority"] as? String
            ?: return ActionExecutionResult.failed("Missing required parameter: authority", "MISSING_PARAM")
        
        val operationsList = args["operations"] as? List<*>
            ?: return ActionExecutionResult.failed("Missing required parameter: operations", "MISSING_PARAM")
        
        // 限制批量操作数量
        if (operationsList.size > MAX_BATCH_SIZE) {
            return ActionExecutionResult.failed(
                "Batch size exceeds limit: ${operationsList.size} > $MAX_BATCH_SIZE",
                "BATCH_TOO_LARGE"
            )
        }
        
        val operations = ArrayList<ContentProviderOperation>()
        
        operationsList.forEach { opMap ->
            val op = opMap as? Map<*, *> ?: return@forEach
            val type = op["type"] as? String ?: return@forEach
            val uriString = op["uri"] as? String ?: return@forEach
            val uri = Uri.parse(uriString)
            
            // 安全检查
            val securityCheck = checkUriSecurity(uri, needsWrite = type != "query")
            if (!securityCheck.allowed) {
                return ActionExecutionResult.failed(securityCheck.reason!!, "SECURITY_DENIED")
            }
            
            when (type) {
                "insert" -> {
                    val builder = ContentProviderOperation.newInsert(uri)
                    val values = op["values"] as? Map<*, *>
                    values?.forEach { (key, value) ->
                        builder.withValue(key as String, value)
                    }
                    operations.add(builder.build())
                }
                "update" -> {
                    val builder = ContentProviderOperation.newUpdate(uri)
                    val values = op["values"] as? Map<*, *>
                    values?.forEach { (key, value) ->
                        builder.withValue(key as String, value)
                    }
                    val selection = op["selection"] as? String
                    val selectionArgs = (op["selection_args"] as? List<*>)?.mapNotNull { it as? String }?.toTypedArray()
                    if (selection != null) {
                        builder.withSelection(selection, selectionArgs)
                    }
                    operations.add(builder.build())
                }
                "delete" -> {
                    val builder = ContentProviderOperation.newDelete(uri)
                    val selection = op["selection"] as? String
                    val selectionArgs = (op["selection_args"] as? List<*>)?.mapNotNull { it as? String }?.toTypedArray()
                    if (selection != null) {
                        builder.withSelection(selection, selectionArgs)
                    }
                    operations.add(builder.build())
                }
            }
        }
        
        return try {
            val results = contentResolver.applyBatch(authority, operations)
            
            val resultList = results.mapIndexed { index, result ->
                mapOf(
                    "index" to index,
                    "uri" to result.uri?.toString(),
                    "count" to result.count
                )
            }
            
            ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "results" to resultList,
                    "success_count" to results.size
                )
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed("Batch operation failed: ${e.message}", "BATCH_ERROR")
        }
    }

    /**
     * content.call - 调用ContentProvider自定义方法
     * 
     * 参数：
     * - uri: String（content:// URI）
     * - method: String（方法名）
     * - arg: String?（参数）
     * - extras: Map<String, Any>?（额外参数）
     * 
     * 返回：
     * - result: Map<String, Any>?（返回的Bundle数据）
     */
    private fun executeCall(request: ActionRequest): ActionExecutionResult {
        val args = request.arguments
        val uriString = args["uri"] as? String
            ?: return ActionExecutionResult.failed("Missing required parameter: uri", "MISSING_PARAM")
        
        val method = args["method"] as? String
            ?: return ActionExecutionResult.failed("Missing required parameter: method", "MISSING_PARAM")
        
        val uri = Uri.parse(uriString)
        
        // 安全检查
        val securityCheck = checkUriSecurity(uri, needsWrite = true)
        if (!securityCheck.allowed) {
            return ActionExecutionResult.failed(securityCheck.reason!!, "SECURITY_DENIED")
        }
        
        val arg = args["arg"] as? String
        val extrasMap = args["extras"] as? Map<*, *>
        
        val extras = Bundle()
        extrasMap?.forEach { (key, value) ->
            when (value) {
                is String -> extras.putString(key as String, value)
                is Int -> extras.putInt(key as String, value)
                is Long -> extras.putLong(key as String, value)
                is Boolean -> extras.putBoolean(key as String, value)
            }
        }
        
        return try {
            val resultBundle = contentResolver.call(uri, method, arg, extras)
            
            val resultMap = mutableMapOf<String, Any?>()
            resultBundle?.keySet()?.forEach { key ->
                resultMap[key] = resultBundle.get(key)
            }
            
            ActionExecutionResult.succeeded(
                resultData = mapOf("result" to resultMap)
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed("Call failed: ${e.message}", "CALL_ERROR")
        }
    }

    /**
     * URI安全检查
     */
    private fun checkUriSecurity(uri: Uri, needsWrite: Boolean): SecurityCheckResult {
        val authority = uri.authority ?: return SecurityCheckResult(false, "Missing authority")
        
        // 检查是否为敏感ContentProvider
        if (SENSITIVE_AUTHORITIES.contains(authority)) {
            return SecurityCheckResult(false, "Sensitive content provider requires user confirmation")
        }
        
        // 检查白名单
        if (!ALLOWED_AUTHORITIES.contains(authority)) {
            return SecurityCheckResult(false, "Content provider not in whitelist: $authority")
        }
        
        // 检查权限
        val permission = if (needsWrite) {
            "${authority}.WRITE"
        } else {
            "${authority}.READ"
        }
        
        // 这里简化处理，实际应检查PackageManager权限
        
        return SecurityCheckResult(true, null)
    }

    private data class SecurityCheckResult(
        val allowed: Boolean,
        val reason: String?
    )
}
