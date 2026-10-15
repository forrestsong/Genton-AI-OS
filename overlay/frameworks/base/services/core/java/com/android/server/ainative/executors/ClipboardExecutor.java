package com.android.server.ainative.executors;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Slog;

import com.ainative.action.ActionRequest;

import org.json.JSONObject;

/**
 * 剪贴板执行器
 * 
 * 核心职责：
 * 1. 复制文本到剪贴板
 * 2. 读取剪贴板内容
 * 3. 清空剪贴板
 * 4. 监听剪贴板变化（可选）
 * 
 * 安全约束：
 * - 复制内容需要 Taint 校验（防止敏感数据泄露）
 * - 读取剪贴板需要用户授权（隐私敏感）
 * - 敏感内容（密码/token）自动清空
 * - 记录剪贴板访问审计日志
 * 
 * 性能要求：
 * - 复制延迟 P50 < 5ms
 * - 读取延迟 P50 < 5ms
 * 
 * @see ClipboardManager
 */
public class ClipboardExecutor implements ActionExecutor {
    private static final String TAG = "ClipboardExecutor";
    
    // 敏感关键词（自动清空）
    private static final String[] SENSITIVE_KEYWORDS = {
        "password", "passwd", "token", "secret", "api_key", "private_key",
        "密码", "口令", "令牌"
    };
    
    // 剪贴板自动清空延迟（60秒）
    private static final long AUTO_CLEAR_DELAY_MS = 60_000;
    
    private final Context mContext;
    private final ClipboardManager mClipboardManager;
    private final android.os.Handler mHandler;
    
    public ClipboardExecutor(Context context) {
        mContext = context;
        mClipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        Slog.i(TAG, "ClipboardExecutor initialized");
    }
    
    @Override
    public String getExecutorType() {
        return "clipboard";
    }
    
    /**
     * 执行剪贴板操作
     * 
     * 支持动作：
     * - clipboard.copy：复制文本
     * - clipboard.paste：读取剪贴板
     * - clipboard.clear：清空剪贴板
     */
    @Override
    public ExecutionResult execute(ActionRequest request, ExecutionContext context) {
        long startNanos = System.nanoTime();
        
        try {
            JSONObject args = new JSONObject(request.argumentsJson);
            String actionId = request.actionId;
            
            ExecutionResult result;
            
            switch (actionId) {
                case "clipboard.copy":
                    result = executeCopy(args, request);
                    break;
                case "clipboard.paste":
                    result = executePaste(args);
                    break;
                case "clipboard.clear":
                    result = executeClear(args);
                    break;
                default:
                    return ExecutionResult.failure("Unsupported action: " + actionId);
            }
            
            if (result.success) {
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                JSONObject resultJson = new JSONObject(result.resultJson);
                resultJson.put("elapsed_us", elapsedMicros);
                result.resultJson = resultJson.toString();
            }
            
            return result;
            
        } catch (Exception e) {
            Slog.e(TAG, "Clipboard execution failed", e);
            return ExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }
    
    /**
     * 复制文本到剪贴板
     * 
     * 参数：
     * {
     *   "text": "要复制的内容",
     *   "label": "描述标签",  // 可选
     *   "sensitive": false,  // 可选，是否敏感内容（自动清空）
     *   "autoClear": false   // 可选，是否自动清空
     * }
     */
    private ExecutionResult executeCopy(JSONObject args, ActionRequest request) throws Exception {
        if (!args.has("text")) {
            return ExecutionResult.failure("Missing parameter: text");
        }
        
        String text = args.getString("text");
        String label = args.optString("label", "AI Agent");
        boolean sensitive = args.optBoolean("sensitive", false);
        boolean autoClear = args.optBoolean("autoClear", false);
        
        // Taint 校验：敏感内容不可复制 EXTERNAL 来源
        if (request.argTaints != null) {
            for (com.ainative.policy.TaintMapEntry taint : request.argTaints) {
                if ("text".equals(taint.argumentKey) && taint.sourceLevel > 2) {  // > AGENT
                    // 检测是否为敏感内容
                    if (isSensitiveContent(text)) {
                        Slog.w(TAG, "Sensitive content from untrusted source: level=" + taint.sourceLevel);
                        return ExecutionResult.failure("Cannot copy sensitive content from untrusted source");
                    }
                }
            }
        }
        
        // 自动检测敏感内容
        if (!sensitive) {
            sensitive = isSensitiveContent(text);
        }
        
        // 创建 ClipData
        ClipData clipData = ClipData.newPlainText(label, text);
        
        // 标记敏感内容
        if (sensitive) {
            clipData.getDescription().getExtras().putBoolean("android.content.extra.IS_SENSITIVE", true);
        }
        
        // 复制到剪贴板
        mClipboardManager.setPrimaryClip(clipData);
        
        // 自动清空（敏感内容或用户指定）
        if (sensitive || autoClear) {
            scheduleAutoClear();
        }
        
        JSONObject result = new JSONObject();
        result.put("text_length", text.length());
        result.put("sensitive", sensitive);
        result.put("auto_clear", sensitive || autoClear);
        
        Slog.i(TAG, String.format("Text copied: length=%d, sensitive=%b", text.length(), sensitive));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 读取剪贴板内容
     * 
     * 参数：
     * {
     *   "maxLength": 10000  // 可选，最大读取长度
     * }
     */
    private ExecutionResult executePaste(JSONObject args) throws Exception {
        if (!mClipboardManager.hasPrimaryClip()) {
            return ExecutionResult.failure("Clipboard is empty");
        }
        
        ClipData clipData = mClipboardManager.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            return ExecutionResult.failure("Clipboard is empty");
        }
        
        // 获取第一个 item
        ClipData.Item item = clipData.getItemAt(0);
        CharSequence text = item.getText();
        
        if (text == null) {
            return ExecutionResult.failure("Clipboard does not contain text");
        }
        
        String content = text.toString();
        
        // 长度限制
        int maxLength = args.optInt("maxLength", 10000);
        if (content.length() > maxLength) {
            content = content.substring(0, maxLength);
        }
        
        // 检测敏感内容
        boolean sensitive = isSensitiveContent(content);
        
        JSONObject result = new JSONObject();
        result.put("text", content);
        result.put("text_length", content.length());
        result.put("sensitive", sensitive);
        result.put("label", clipData.getDescription().getLabel());
        
        Slog.i(TAG, String.format("Clipboard read: length=%d, sensitive=%b", content.length(), sensitive));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 清空剪贴板
     */
    private ExecutionResult executeClear(JSONObject args) throws Exception {
        // 清空剪贴板（设置空内容）
        ClipData emptyClip = ClipData.newPlainText("", "");
        mClipboardManager.setPrimaryClip(emptyClip);
        
        JSONObject result = new JSONObject();
        result.put("cleared", true);
        
        Slog.i(TAG, "Clipboard cleared");
        return ExecutionResult.success(result.toString());
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 检测是否为敏感内容
     */
    private boolean isSensitiveContent(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        String lowerText = text.toLowerCase();
        
        // 关键词匹配
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lowerText.contains(keyword)) {
                return true;
            }
        }
        
        // 格式匹配（如长字符串可能是 token）
        if (text.length() > 100 && !text.contains(" ")) {
            // 无空格的长字符串（可能是 token/key）
            return true;
        }
        
        return false;
    }
    
    /**
     * 调度自动清空
     */
    private void scheduleAutoClear() {
        mHandler.postDelayed(() -> {
            try {
                ClipData emptyClip = ClipData.newPlainText("", "");
                mClipboardManager.setPrimaryClip(emptyClip);
                Slog.i(TAG, "Clipboard auto-cleared after " + (AUTO_CLEAR_DELAY_MS / 1000) + "s");
            } catch (Exception e) {
                Slog.e(TAG, "Failed to auto-clear clipboard", e);
            }
        }, AUTO_CLEAR_DELAY_MS);
    }
    
    @Override
    public boolean supportsAction(String actionId) {
        return actionId.startsWith("clipboard.");
    }
}
