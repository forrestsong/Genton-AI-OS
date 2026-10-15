// AI Native OS - Chain Integrity View (D4.1)
package com.android.systemui.ainative.privacy;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.ainative.privacy.PrivacyDashboardManager.ChainIntegrityStatus;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 审计链完整性视图（D4.1）
 * 
 * 核心职责：
 * 1. 显示审计链完整性状态（valid/broken）
 * 2. 展示哈希链验证结果
 * 3. 显示 TEE 锚点信息
 * 4. 指示断裂点位置
 * 
 * 视觉设计：
 * - 完整性状态卡片（绿色=完整，红色=断裂）
 * - TEE 锚点信息（序列号、时间戳、哈希）
 * - 链统计信息（总事件数、最近锚点）
 * - 断裂点列表（如有）
 * 
 * WBS 要求：
 * - 审计链完整性展示 ✅
 * - 哈希链验证结果 ✅
 * - TEE 锚点信息 ✅
 * - 前置依赖 A5.2（审计流）✅
 */
public class ChainIntegrityView extends LinearLayout {
    private static final String TAG = "ChainIntegrityView";
    
    // 时间格式
    private static final SimpleDateFormat DATE_FORMAT = 
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    
    // 状态颜色
    private static final int COLOR_VALID = 0xFF4CAF50;   // 绿色（完整）
    private static final int COLOR_BROKEN = 0xFFF44336;  // 红色（断裂）
    private static final int COLOR_CHECKING = 0xFFFF9800; // 橙色（检查中）
    
    private Context mContext;
    private ChainIntegrityStatus mStatus;
    
    // UI 组件
    private LinearLayout mStatusCard;
    private TextView mStatusIcon;
    private TextView mStatusText;
    private LinearLayout mDetailsContainer;
    private ProgressBar mProgressBar;
    
    public ChainIntegrityView(Context context) {
        super(context);
        init(context);
    }
    
    public ChainIntegrityView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    private void init(Context context) {
        mContext = context;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(16), dp(16), dp(16));
        
        buildUI();
    }
    
    /**
     * 构建 UI 结构
     */
    private void buildUI() {
        // 状态卡片
        mStatusCard = new LinearLayout(mContext);
        mStatusCard.setOrientation(HORIZONTAL);
        mStatusCard.setBackgroundColor(0xFFF5F5F5);
        mStatusCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        mStatusCard.setGravity(Gravity.CENTER_VERTICAL);
        
        // 状态图标
        mStatusIcon = new TextView(mContext);
        mStatusIcon.setTextSize(32);
        mStatusIcon.setLayoutParams(new LayoutParams(dp(48), dp(48)));
        mStatusIcon.setGravity(Gravity.CENTER);
        mStatusCard.addView(mStatusIcon);
        
        // 状态文本
        mStatusText = new TextView(mContext);
        mStatusText.setTextSize(16);
        mStatusText.setTextColor(0xFF212121);
        LayoutParams statusTextParams = new LayoutParams(
            0, LayoutParams.WRAP_CONTENT, 1.0f);
        statusTextParams.leftMargin = dp(16);
        mStatusText.setLayoutParams(statusTextParams);
        mStatusCard.addView(mStatusText);
        
        addView(mStatusCard);
        
        // 加载指示器
        mProgressBar = new ProgressBar(mContext);
        LayoutParams progressParams = new LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(16);
        mProgressBar.setLayoutParams(progressParams);
        mProgressBar.setVisibility(View.GONE);
        addView(mProgressBar);
        
        // 详情容器
        mDetailsContainer = new LinearLayout(mContext);
        mDetailsContainer.setOrientation(VERTICAL);
        LayoutParams detailsParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        detailsParams.topMargin = dp(16);
        mDetailsContainer.setLayoutParams(detailsParams);
        addView(mDetailsContainer);
        
        // 初始状态
        showCheckingState();
    }
    
    /**
     * 设置完整性状态
     * 
     * @param status 完整性状态
     */
    public void setIntegrityStatus(ChainIntegrityStatus status) {
        mStatus = status;
        updateUI();
    }
    
    /**
     * 显示检查中状态
     */
    public void showCheckingState() {
        mStatusIcon.setText("⏳");
        mStatusText.setText("正在检查审计链完整性...");
        mStatusCard.setBackgroundColor(COLOR_CHECKING);
        mProgressBar.setVisibility(View.VISIBLE);
        mDetailsContainer.removeAllViews();
    }
    
    /**
     * 显示错误状态
     */
    public void showError(String error) {
        mStatusIcon.setText("⚠️");
        mStatusText.setText("检查失败: " + error);
        mStatusCard.setBackgroundColor(COLOR_BROKEN);
        mProgressBar.setVisibility(View.GONE);
        mDetailsContainer.removeAllViews();
    }
    
    /**
     * 更新 UI
     */
    private void updateUI() {
        if (mStatus == null) {
            showCheckingState();
            return;
        }
        
        mProgressBar.setVisibility(View.GONE);
        mDetailsContainer.removeAllViews();
        
        // 状态卡片
        if (mStatus.isValid) {
            mStatusIcon.setText("✓");
            mStatusText.setText("审计链完整，未发现篡改");
            mStatusCard.setBackgroundColor(COLOR_VALID);
        } else {
            mStatusIcon.setText("✗");
            mStatusText.setText("审计链断裂，发现 " + mStatus.brokenSeqs.size() + " 处异常");
            mStatusCard.setBackgroundColor(COLOR_BROKEN);
        }
        
        // 链统计信息
        addDetailsSection("链统计", buildChainStatsView());
        
        // TEE 锚点信息
        addDetailsSection("TEE 锚点", buildAnchorInfoView());
        
        // 断裂点（如有）
        if (!mStatus.isValid && !mStatus.brokenSeqs.isEmpty()) {
            addDetailsSection("断裂点", buildBrokenPointsView());
        }
    }
    
    /**
     * 添加详情区块
     */
    private void addDetailsSection(String title, View contentView) {
        // 标题
        TextView titleView = new TextView(mContext);
        titleView.setText(title);
        titleView.setTextSize(14);
        titleView.setTextColor(0xFF757575);
        LayoutParams titleParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(16);
        titleParams.bottomMargin = dp(8);
        titleView.setLayoutParams(titleParams);
        mDetailsContainer.addView(titleView);
        
        // 内容
        mDetailsContainer.addView(contentView);
    }
    
    /**
     * 构建链统计视图
     */
    private View buildChainStatsView() {
        LinearLayout container = new LinearLayout(mContext);
        container.setOrientation(VERTICAL);
        container.setBackgroundColor(0xFFF5F5F5);
        container.setPadding(dp(12), dp(8), dp(12), dp(8));
        
        addInfoRow(container, "总事件数", String.valueOf(mStatus.totalEvents));
        addInfoRow(container, "最近锚点序列号", String.valueOf(mStatus.lastAnchorSeq));
        addInfoRow(container, "最近锚点时间", formatTimestamp(mStatus.lastAnchorTimestampMs));
        
        return container;
    }
    
    /**
     * 构建锚点信息视图
     */
    private View buildAnchorInfoView() {
        LinearLayout container = new LinearLayout(mContext);
        container.setOrientation(VERTICAL);
        container.setBackgroundColor(0xFFF5F5F5);
        container.setPadding(dp(12), dp(8), dp(12), dp(8));
        
        addInfoRow(container, "锚点哈希", 
            mStatus.lastAnchorHash != null ? mStatus.lastAnchorHash : "N/A");
        addInfoRow(container, "锚点类型", "TEE 硬件锚点");
        addInfoRow(container, "验证状态", mStatus.isValid ? "✓ 已验证" : "✗ 验证失败");
        
        return container;
    }
    
    /**
     * 构建断裂点视图
     */
    private View buildBrokenPointsView() {
        LinearLayout container = new LinearLayout(mContext);
        container.setOrientation(VERTICAL);
        container.setBackgroundColor(0xFFFFEBEE); // 浅红色背景
        container.setPadding(dp(12), dp(8), dp(12), dp(8));
        
        for (Long seq : mStatus.brokenSeqs) {
            TextView itemView = new TextView(mContext);
            itemView.setText("⚠ 序列号 " + seq + " 处哈希链断裂");
            itemView.setTextSize(14);
            itemView.setTextColor(COLOR_BROKEN);
            itemView.setPadding(0, dp(4), 0, dp(4));
            container.addView(itemView);
        }
        
        return container;
    }
    
    /**
     * 添加信息行
     */
    private void addInfoRow(LinearLayout container, String label, String value) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        
        TextView labelView = new TextView(mContext);
        labelView.setText(label + ":");
        labelView.setTextSize(14);
        labelView.setTextColor(0xFF757575);
        labelView.setLayoutParams(new LayoutParams(dp(120), LayoutParams.WRAP_CONTENT));
        row.addView(labelView);
        
        TextView valueView = new TextView(mContext);
        valueView.setText(value);
        valueView.setTextSize(14);
        valueView.setTextColor(0xFF212121);
        valueView.setLayoutParams(new LayoutParams(
            0, LayoutParams.WRAP_CONTENT, 1.0f));
        row.addView(valueView);
        
        container.addView(row);
    }
    
    /**
     * 格式化时间戳
     */
    private String formatTimestamp(long timestampMs) {
        if (timestampMs <= 0) {
            return "N/A";
        }
        return DATE_FORMAT.format(new Date(timestampMs));
    }
    
    /**
     * dp 转 px
     */
    private int dp(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
