// AI Native OS - Audit Timeline View (D4.1)
package com.android.systemui.ainative.privacy;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.ainative.privacy.PrivacyDashboardManager.TimelineItem;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 审计时间线视图（D4.1）
 * 
 * 核心职责：
 * 1. 显示意图 → 动作 → 证据时间线
 * 2. 展示脱敏后的审计事件
 * 3. 支持分类筛选和时间范围切换
 * 
 * 视觉设计：
 * - 垂直时间线（Material Timeline）
 * - 每个事件显示：时间、图标、脱敏摘要、证据指示
 * - 分类颜色编码（感知=蓝、动作=绿、记忆=紫、策略=橙、确认=青、系统=灰）
 * 
 * WBS 要求：
 * - 意图→动作→证据时间线 ✅
 * - viewerRole=USER 脱敏视图 ✅
 * - 时间戳可读化 ✅
 */
public class AuditTimelineView extends LinearLayout {
    private static final String TAG = "AuditTimelineView";
    
    // 时间格式
    private static final SimpleDateFormat TIME_FORMAT = 
        new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static final SimpleDateFormat DATE_FORMAT = 
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    
    // 分类颜色（Material Design）
    private static final int COLOR_PERCEPTION = 0xFF2196F3;  // 蓝色
    private static final int COLOR_ACTION = 0xFF4CAF50;      // 绿色
    private static final int COLOR_MEMORY = 0xFF9C27B0;      // 紫色
    private static final int COLOR_POLICY = 0xFFFF9800;      // 橙色
    private static final int COLOR_CONFIRMATION = 0xFF00BCD4; // 青色
    private static final int COLOR_CAPABILITY = 0xFF795548;  // 棕（能力授予）
    private static final int COLOR_SYSTEM = 0xFF9E9E9E;      // 灰色
    
    private Context mContext;
    private List<TimelineItem> mTimelineItems;
    
    public AuditTimelineView(Context context) {
        super(context);
        init(context);
    }
    
    public AuditTimelineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    private void init(Context context) {
        mContext = context;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(8), dp(16), dp(8));
    }
    
    /**
     * 设置时间线数据
     * 
     * @param items 时间线项列表（已脱敏）
     */
    public void setTimelineItems(List<TimelineItem> items) {
        mTimelineItems = items;
        rebuildTimeline();
    }
    
    /**
     * 重建时间线视图
     */
    private void rebuildTimeline() {
        removeAllViews();
        
        if (mTimelineItems == null || mTimelineItems.isEmpty()) {
            addEmptyView();
            return;
        }
        
        // 按时间倒序显示（最新在上）
        for (int i = mTimelineItems.size() - 1; i >= 0; i--) {
            TimelineItem item = mTimelineItems.get(i);
            View itemView = createTimelineItemView(item, i == 0);
            addView(itemView);
        }
    }
    
    /**
     * 创建时间线项视图
     * 
     * @param item 时间线项
     * @param isLast 是否是最后一个（底部）
     * @return 时间线项视图
     */
    private View createTimelineItemView(TimelineItem item, boolean isLast) {
        LinearLayout container = new LinearLayout(mContext);
        container.setOrientation(HORIZONTAL);
        container.setLayoutParams(new LayoutParams(
            LayoutParams.MATCH_PARENT, 
            LayoutParams.WRAP_CONTENT));
        container.setPadding(0, dp(8), 0, dp(8));
        
        // 左侧：时间线指示器
        LinearLayout timelineIndicator = createTimelineIndicator(item, isLast);
        container.addView(timelineIndicator);
        
        // 右侧：内容卡片
        LinearLayout contentCard = createContentCard(item);
        container.addView(contentCard);
        
        return container;
    }
    
    /**
     * 创建时间线指示器（左侧）
     */
    private LinearLayout createTimelineIndicator(TimelineItem item, boolean isLast) {
        LinearLayout indicator = new LinearLayout(mContext);
        indicator.setOrientation(VERTICAL);
        indicator.setGravity(Gravity.CENTER_HORIZONTAL);
        indicator.setLayoutParams(new LayoutParams(dp(40), LayoutParams.MATCH_PARENT));
        
        // 图标圆点
        ImageView icon = new ImageView(mContext);
        LayoutParams iconParams = new LayoutParams(dp(24), dp(24));
        icon.setLayoutParams(iconParams);
        icon.setBackgroundColor(getCategoryColor(item.category));
        // TODO: 设置实际图标（item.icon）
        indicator.addView(icon);
        
        // 连接线（如果不是最后一个）
        if (!isLast) {
            View line = new View(mContext);
            LayoutParams lineParams = new LayoutParams(dp(2), LayoutParams.MATCH_PARENT);
            lineParams.topMargin = dp(4);
            line.setLayoutParams(lineParams);
            line.setBackgroundColor(0xFFE0E0E0);
            indicator.addView(line);
        }
        
        return indicator;
    }
    
    /**
     * 创建内容卡片（右侧）
     */
    private LinearLayout createContentCard(TimelineItem item) {
        LinearLayout card = new LinearLayout(mContext);
        card.setOrientation(VERTICAL);
        LayoutParams cardParams = new LayoutParams(
            0, LayoutParams.WRAP_CONTENT, 1.0f);
        cardParams.leftMargin = dp(12);
        card.setLayoutParams(cardParams);
        card.setBackgroundColor(0xFFF5F5F5);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        
        // 时间戳
        TextView timeView = new TextView(mContext);
        timeView.setTextSize(12);
        timeView.setTextColor(0xFF757575);
        timeView.setText(formatTimestamp(item.timestampMs));
        card.addView(timeView);
        
        // 脱敏摘要
        TextView summaryView = new TextView(mContext);
        summaryView.setTextSize(14);
        summaryView.setTextColor(0xFF212121);
        summaryView.setText(item.summaryMasked);
        LayoutParams summaryParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(4);
        summaryView.setLayoutParams(summaryParams);
        card.addView(summaryView);
        
        // Agent ID
        TextView agentView = new TextView(mContext);
        agentView.setTextSize(12);
        agentView.setTextColor(0xFF9E9E9E);
        agentView.setText("Agent: " + item.agentId);
        LayoutParams agentParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        agentParams.topMargin = dp(4);
        agentView.setLayoutParams(agentParams);
        card.addView(agentView);
        
        // 证据指示
        if (item.hasEvidence) {
            TextView evidenceView = new TextView(mContext);
            evidenceView.setTextSize(12);
            evidenceView.setTextColor(0xFF4CAF50);
            evidenceView.setText("✓ 有证据");
            LayoutParams evidenceParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            evidenceParams.topMargin = dp(4);
            evidenceView.setLayoutParams(evidenceParams);
            card.addView(evidenceView);
        }
        
        return card;
    }
    
    /**
     * 添加空视图
     */
    private void addEmptyView() {
        TextView emptyView = new TextView(mContext);
        emptyView.setText("暂无审计记录");
        emptyView.setTextSize(16);
        emptyView.setTextColor(0xFF9E9E9E);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setLayoutParams(new LayoutParams(
            LayoutParams.MATCH_PARENT, dp(200)));
        addView(emptyView);
    }
    
    /**
     * 获取分类颜色
     */
    private int getCategoryColor(int category) {
        switch (category) {
            case 1: // CAT_PERCEPTION_READ
                return COLOR_PERCEPTION;
            case 2: // CAT_ACTION_EXECUTE
                return COLOR_ACTION;
            case 3: // CAT_MEMORY_ACCESS
                return COLOR_MEMORY;
            case 4: // CAT_POLICY_DECISION
                return COLOR_POLICY;
            case 5: // CAT_CAPABILITY_GRANT
                return COLOR_CAPABILITY;
            case 6: // CAT_CONFIRMATION
                return COLOR_CONFIRMATION;
            case 7: // CAT_SYSTEM
                return COLOR_SYSTEM;
            default:
                return 0xFF9E9E9E;
        }
    }
    
    /**
     * 格式化时间戳
     */
    private String formatTimestamp(long timestampMs) {
        long now = System.currentTimeMillis();
        long diff = now - timestampMs;
        
        // 最近 1 小时：显示相对时间
        if (diff < 60 * 60 * 1000) {
            long minutes = diff / (60 * 1000);
            if (minutes < 1) {
                return "刚刚";
            } else {
                return minutes + " 分钟前";
            }
        }
        
        // 今天：显示时间
        Date date = new Date(timestampMs);
        Date today = new Date(now);
        if (date.getDay() == today.getDay() && 
            date.getMonth() == today.getMonth() &&
            date.getYear() == today.getYear()) {
            return "今天 " + TIME_FORMAT.format(date);
        }
        
        // 其他：显示完整日期时间
        return DATE_FORMAT.format(date);
    }
    
    /**
     * dp 转 px
     */
    private int dp(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
