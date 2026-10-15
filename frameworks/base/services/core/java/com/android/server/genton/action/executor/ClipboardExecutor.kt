package com.android.server.genton.action.executor

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.UserHandle
import com.android.server.genton.action.ActionRequest
import com.android.server.genton.action.ActionExecutionResult
import com.android.server.genton.audit.AuditLogger
import java.util.concurrent.TimeUnit

/**
 * A3.5 系统侧执行器 - 粘贴板操作
 * 
 * 支持的动作：
 * - clipboard.read：读取当前粘贴板内容
 * - clipboard.write：写入文本到粘贴板
 * - clipboard.write_uri：写入URI到粘贴板（用于跨应用共享）
 * - clipboard.clear：清空粘贴板
 * - clipboard.has_data：检查粘贴板是否有数据
 * 
 * 安全机制：
 * 1. PII脱敏：读取粘贴板时自动脱敏敏感信息（默认启用）
 * 2. 来源标记：写入粘贴板时标记Agent来源
 * 3. TTL机制：写入的剪切板数据可设置过期时间
 * 4. URI权限：写入URI时校验读取权限
 * 5. 审计：所有粘贴板操作均审计
 */
class ClipboardExecutor(
    private val context: Context,
    private val auditLogger: AuditLogger
) : ActionExecutor {

    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    companion object {
        private const val TAG = "ClipboardExecutor"
        private const val AGENT_CLIP_LABEL = "AI_Native_Agent_Clip"
        
        // PII脱敏规则（与通知服务保持一致）
        private val PII_PATTERNS = mapOf(
            "VERIFICATION_CODE" to Regex("""\b\d{4,8}\b"""),
            "PHONE_NUMBER" to Regex("""\b1[3-9]\d{9}\b"""),
            "EMAIL" to Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b"""),
            "ID_CARD" to Regex("""\b\d{17}[\dXx]\b"""),
            "BANK_CARD" to Regex("""\b\d{16,19}\b"""),
            "AMOUNT" to Regex("""[¥$€£]\s*\d+(?:,\d{3})*(?:\.\d{2})?""")
        )
    }

    override fun canExecute(actionName: String): Boolean {
        return actionName.startsWith("clipboard.")
    }

    override fun execute(request: ActionRequest): ActionExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = when (request.actionName) {
                "clipboard.read" -> executeRead(request)
                "clipboard.write" -> executeWrite(request)
                "clipboard.write_uri" -> executeWriteUri(request)
                "clipboard.clear" -> executeClear(request)
                "clipboard.has_data" -> executeHasData(request)
                else -> ActionExecutionResult.failed(
                    "Unknown clipboard action: ${request.actionName}",
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
                    "data_type" to (result.resultData["data_type"] ?: "none")
                )
            )
            
            result
        } catch (e: SecurityException) {
            ActionExecutionResult.failed(
                "Security violation: ${e.message}",
                errorCode = "SECURITY_DENIED"
            )
        } catch (e: Exception) {
            ActionExecutionResult.failed(
                "Clipboard operation failed: ${e.message}",
                errorCode = "EXECUTION_ERROR"
            )
        }
    }

    /**
     * clipboard.read - 读取粘贴板内容
     * 
     * 参数：
     * - apply_pii_masking: Boolean = true（是否应用PII脱敏）
     * 
     * 返回：
     * - data_type: "text" | "uri" | "intent" | "none"
     * - text: String?（脱敏后的文本）
     * - uri: String?（URI字符串）
     * - mime_type: String?（MIME类型）
     */
    private fun executeRead(request: ActionRequest): ActionExecutionResult {
        val applyMasking = request.arguments["apply_pii_masking"] as? Boolean ?: true
        
        if (!clipboardManager.hasPrimaryClip()) {
            return ActionExecutionResult.succeeded(
                resultData = mapOf(
                    "data_type" to "none",
                    "text" to null,
                    "uri" to null,
                    "mime_type" to null
                )
            )
        }
        
        val clip = clipboardManager.primaryClip ?: return ActionExecutionResult.succeeded(
            resultData = mapOf("data_type" to "none")
        )
        
        val description = clip.description
        val item = clip.getItemAt(0)
        
        return when {
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) -> {
                var text = item.text?.toString() ?: ""
                
                // PII脱敏
                if (applyMasking) {
                    text = applyPiiMasking(text)
                }
                
                ActionExecutionResult.succeeded(
                    resultData = mapOf(
                        "data_type" to "text",
                        "text" to text,
                        "mime_type" to description.getMimeType(0),
                        "pii_masked" to applyMasking
                    )
                )
            }
            item.uri != null -> {
                ActionExecutionResult.succeeded(
                    resultData = mapOf(
                        "data_type" to "uri",
                        "uri" to item.uri.toString(),
                        "mime_type" to description.getMimeType(0)
                    )
                )
            }
            item.intent != null -> {
                ActionExecutionResult.succeeded(
                    resultData = mapOf(
                        "data_type" to "intent",
                        "mime_type" to description.getMimeType(0),
                        "intent_action" to item.intent.action
                    )
                )
            }
            else -> {
                ActionExecutionResult.succeeded(
                    resultData = mapOf("data_type" to "none")
                )
            }
        }
    }

    /**
     * clipboard.write - 写入文本到粘贴板
     * 
     * 参数：
     * - text: String（要写入的文本，必须USER源）
     * - sensitive: Boolean = false（是否为敏感数据，影响TTL）
     * - ttl_seconds: Int? = null（过期时间，秒）
     * 
     * 返回：
     * - written: Boolean
     */
    private fun executeWrite(request: ActionRequest): ActionExecutionResult {
        val args = request.arguments
        val text = args["text"] as? String
            ?: return ActionExecutionResult.failed(
                "Missing required parameter: text",
                errorCode = "MISSING_PARAM"
            )
        
        // Taint规则：写入文本必须USER源（防止污染数据劫持）
        val textSource = request.argumentSources["text"]
        if (textSource != "USER") {
            return ActionExecutionResult.failed(
                "Clipboard write must use USER-sourced text (taint violation)",
                errorCode = "TAINT_DENIED"
            )
        }
        
        val sensitive = args["sensitive"] as? Boolean ?: false
        val ttlSeconds = args["ttl_seconds"] as? Int
        
        // 创建ClipData
        val clip = ClipData.newPlainText(AGENT_CLIP_LABEL, text)
        
        // 设置过期时间（如果支持）
        if (ttlSeconds != null) {
            val expiryTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ttlSeconds.toLong())
            // Android S+支持ClipData时间戳，这里预留扩展
            clip.description.extras.putLong("expiry_time", expiryTime)
        }
        
        // 标记敏感数据
        if (sensitive) {
            clip.description.extras.putBoolean("sensitive", true)
        }
        
        clipboardManager.setPrimaryClip(clip)
        
        return ActionExecutionResult.succeeded(
            resultData = mapOf(
                "written" to true,
                "text_length" to text.length,
                "sensitive" to sensitive,
                "ttl_seconds" to ttlSeconds
            )
        )
    }

    /**
     * clipboard.write_uri - 写入URI到粘贴板（用于跨应用共享）
     * 
     * 参数：
     * - uri: String（content:// URI）
     * - mime_type: String = "application/octet-stream"
     * 
     * 返回：
     * - written: Boolean
     * - uri: String
     */
    private fun executeWriteUri(request: ActionRequest): ActionExecutionResult {
        val args = request.arguments
        val uriString = args["uri"] as? String
            ?: return ActionExecutionResult.failed(
                "Missing required parameter: uri",
                errorCode = "MISSING_PARAM"
            )
        
        val mimeType = args["mime_type"] as? String ?: "application/octet-stream"
        
        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            return ActionExecutionResult.failed(
                "Invalid URI format: ${e.message}",
                errorCode = "INVALID_URI"
            )
        }
        
        // 校验URI scheme
        if (uri.scheme != "content" && uri.scheme != "file") {
            return ActionExecutionResult.failed(
                "Only content:// and file:// URIs are allowed",
                errorCode = "INVALID_SCHEME"
            )
        }
        
        // 校验读取权限（对于content:// URI）
        if (uri.scheme == "content") {
            try {
                context.contentResolver.openInputStream(uri)?.close()
            } catch (e: SecurityException) {
                return ActionExecutionResult.failed(
                    "No read permission for URI: ${e.message}",
                    errorCode = "PERMISSION_DENIED"
                )
            } catch (e: Exception) {
                return ActionExecutionResult.failed(
                    "Cannot access URI: ${e.message}",
                    errorCode = "ACCESS_ERROR"
                )
            }
        }
        
        // 创建ClipData
        val clip = ClipData.newUri(context.contentResolver, AGENT_CLIP_LABEL, uri)
        clip.description.extras.putString("mime_type", mimeType)
        
        clipboardManager.setPrimaryClip(clip)
        
        return ActionExecutionResult.succeeded(
            resultData = mapOf(
                "written" to true,
                "uri" to uriString,
                "mime_type" to mimeType
            )
        )
    }

    /**
     * clipboard.clear - 清空粘贴板
     * 
     * 返回：
     * - cleared: Boolean
     */
    private fun executeClear(request: ActionRequest): ActionExecutionResult {
        clipboardManager.clearPrimaryClip()
        
        return ActionExecutionResult.succeeded(
            resultData = mapOf("cleared" to true)
        )
    }

    /**
     * clipboard.has_data - 检查粘贴板是否有数据
     * 
     * 返回：
     * - has_data: Boolean
     * - mime_type: String?
     */
    private fun executeHasData(request: ActionRequest): ActionExecutionResult {
        val hasData = clipboardManager.hasPrimaryClip()
        val mimeType = if (hasData) {
            clipboardManager.primaryClipDescription?.getMimeType(0)
        } else {
            null
        }
        
        return ActionExecutionResult.succeeded(
            resultData = mapOf(
                "has_data" to hasData,
                "mime_type" to mimeType
            )
        )
    }

    /**
     * PII脱敏处理
     */
    private fun applyPiiMasking(text: String): String {
        var masked = text
        
        PII_PATTERNS.forEach { (type, pattern) ->
            masked = pattern.replace(masked) { matchResult ->
                val original = matchResult.value
                when (type) {
                    "VERIFICATION_CODE" -> "****"
                    "PHONE_NUMBER" -> "${original.take(3)}****${original.takeLast(4)}"
                    "EMAIL" -> {
                        val parts = original.split("@")
                        "${parts[0].take(2)}***@${parts[1]}"
                    }
                    "ID_CARD" -> "${original.take(6)}********${original.takeLast(4)}"
                    "BANK_CARD" -> "${original.take(4)} **** **** ${original.takeLast(4)}"
                    "AMOUNT" -> "[AMOUNT_MASKED]"
                    else -> original
                }
            }
        }
        
        return masked
    }
}
