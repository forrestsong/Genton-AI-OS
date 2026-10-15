// AI Native OS - Attachment Preview Helper
package com.android.systemui.ainative.session.message;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;

/**
 * 附件预览助手：处理附件的预览与打开（D2.1 增强）
 * 
 * 功能：
 * - 图片/视频：调用系统查看器 ACTION_VIEW
 * - 文档：调用系统文档查看器 ACTION_VIEW（按 MIME 类型）
 * - 链接：调用浏览器打开
 * 
 * 设计原则：
 * - 复用系统能力（Intent.ACTION_VIEW），不引入第三方查看器
 * - MIME 类型映射，确保正确的应用响应
 * - 文件不存在时降级处理
 * 
 * @since A2 (D2.1 增强)
 */
public class AttachmentPreviewHelper {
    private static final String TAG = "AttachmentPreviewHelper";
    
    private final Context mContext;
    
    public AttachmentPreviewHelper(Context context) {
        mContext = context;
    }
    
    /**
     * 预览附件（根据类型分发）
     */
    public void preview(String filePath, AttachmentMessageBubble.AttachmentType type) {
        if (filePath == null || filePath.isEmpty()) {
            Log.w(TAG, "File path is null or empty");
            return;
        }
        
        switch (type) {
            case IMAGE:
                openWithViewer(filePath, "image/*");
                break;
            case VIDEO:
                openWithViewer(filePath, "video/*");
                break;
            case AUDIO:
                openWithViewer(filePath, "audio/*");
                break;
            case DOCUMENT:
                openDocument(filePath);
                break;
            case LINK:
                openLink(filePath);
                break;
            case OTHER:
            default:
                openWithViewer(filePath, "*/*");
                break;
        }
    }
    
    /**
     * 使用系统查看器打开（图片/视频/音频）
     */
    private void openWithViewer(String filePath, String mimeType) {
        try {
            Uri uri = getUriForFile(filePath);
            if (uri == null) return;
            
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
            
            Log.d(TAG, "Opened viewer for: " + filePath + " (" + mimeType + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open viewer: " + filePath, e);
        }
    }
    
    /**
     * 打开文档（按扩展名推断 MIME）
     */
    private void openDocument(String filePath) {
        String mimeType = getMimeTypeFromPath(filePath);
        openWithViewer(filePath, mimeType);
    }
    
    /**
     * 打开链接（浏览器）
     */
    private void openLink(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
            
            Log.d(TAG, "Opened link: " + url);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open link: " + url, e);
        }
    }
    
    /**
     * 获取文件 Uri（file:// 方式，系统组件内部使用）
     */
    private Uri getUriForFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            Log.w(TAG, "File does not exist: " + filePath);
            return null;
        }
        return Uri.fromFile(file);
    }
    
    /**
     * 根据文件路径推断 MIME 类型
     */
    private String getMimeTypeFromPath(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "application/msword";
        } else if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return "application/vnd.ms-excel";
        } else if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) {
            return "application/vnd.ms-powerpoint";
        } else if (lower.endsWith(".txt")) {
            return "text/plain";
        } else {
            return "*/*";
        }
    }
}
