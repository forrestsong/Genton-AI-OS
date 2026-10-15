// AI Native OS - Message Context Menu
package com.android.systemui.genton.session.message;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * 消息上下文菜单：长按消息气泡弹出操作菜单（D2.1 增强）
 * 
 * 功能：
 * - 复制消息文本
 * - 删除消息
 * - 引用回复（TODO: A3 期实现引用逻辑）
 * 
 * 设计原则：
 * - PopupWindow 实现，轻量级
 * - 支持任意 View 锚点（气泡上方弹出）
 * - 操作回调接口解耦
 * 
 * @since A2 (D2.1 增强)
 */
public class MessageContextMenu {
    private static final String TAG = "MessageContextMenu";
    
    private final Context mContext;
    private PopupWindow mPopupWindow;
    
    private OnMenuActionListener mListener;
    
    /**
     * 菜单操作回调接口
     */
    public interface OnMenuActionListener {
        void onCopy(String messageId, String text);
        void onDelete(String messageId);
        void onQuote(String messageId, String text);
    }
    
    public MessageContextMenu(Context context) {
        mContext = context;
    }
    
    /**
     * 显示菜单（锚定在 anchorView 上方）
     */
    public void show(View anchorView, String messageId, String text) {
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
        }
        
        // 创建菜单布局
        LinearLayout menuLayout = new LinearLayout(mContext);
        menuLayout.setOrientation(LinearLayout.VERTICAL);
        menuLayout.setBackgroundColor(Color.WHITE);
        menuLayout.setPadding(0, dpToPx(4), 0, dpToPx(4));
        menuLayout.setElevation(dpToPx(8));
        
        // 复制按钮
        TextView copyItem = createMenuItem("复制");
        copyItem.setOnClickListener(v -> {
            copyToClipboard(text);
            if (mListener != null) {
                mListener.onCopy(messageId, text);
            }
            dismiss();
        });
        menuLayout.addView(copyItem);
        
        // 删除按钮
        TextView deleteItem = createMenuItem("删除");
        deleteItem.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onDelete(messageId);
            }
            dismiss();
        });
        menuLayout.addView(deleteItem);
        
        // 引用按钮
        TextView quoteItem = createMenuItem("引用回复");
        quoteItem.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onQuote(messageId, text);
            }
            dismiss();
        });
        menuLayout.addView(quoteItem);
        
        // 创建 PopupWindow
        mPopupWindow = new PopupWindow(
            menuLayout,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        );
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.setElevation(dpToPx(8));
        
        // 显示在 anchorView 上方
        int[] location = new int[2];
        anchorView.getLocationOnScreen(location);
        
        int xOffset = location[0] + anchorView.getWidth() / 2;
        int yOffset = location[1] - dpToPx(150);  // 上方偏移
        
        mPopupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, xOffset, yOffset);
        
        Log.d(TAG, "Context menu shown for message: " + messageId);
    }
    
    /**
     * 创建菜单项
     */
    private TextView createMenuItem(String text) {
        TextView item = new TextView(mContext);
        item.setText(text);
        item.setTextSize(16);
        item.setTextColor(Color.parseColor("#212121"));
        item.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12));
        item.setBackgroundColor(Color.WHITE);
        
        // 点击效果（简化版）
        item.setOnClickListener(v -> {
            item.setBackgroundColor(Color.parseColor("#F5F5F5"));
            item.postDelayed(() -> item.setBackgroundColor(Color.WHITE), 100);
        });
        
        return item;
    }
    
    /**
     * 复制文本到剪贴板
     */
    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("message", text);
            clipboard.setPrimaryClip(clip);
            Log.d(TAG, "Text copied to clipboard");
        }
    }
    
    /**
     * 关闭菜单
     */
    public void dismiss() {
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
        }
    }
    
    /**
     * 设置菜单操作监听器
     */
    public void setOnMenuActionListener(OnMenuActionListener listener) {
        mListener = listener;
    }
    
    private int dpToPx(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
