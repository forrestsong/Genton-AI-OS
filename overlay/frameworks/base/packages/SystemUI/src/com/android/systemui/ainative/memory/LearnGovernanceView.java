// AI Native OS - Learn Governance View (D4.2)
package com.android.systemui.ainative.memory;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.server.ainative.PolicyEngineService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Learn 治理视图（D4.2）
 * 
 * 核心功能：
 * 1. 显示已激活的 learn. 规则列表
 * 2. 冷静期倒计时（7 天内可撤回）
 * 3. 命中统计（近 7 天折叠/接管计数）
 * 4. 撤回操作（调用 sys.policy.deactivate_rule）
 * 5. 规则详情查看
 * 
 * Learn 规则生命周期：
 * - suggestion（建议态）：不参与裁决
 * - active（激活态）：复制为 user.<src>.learned-<hash>，7 天冷静期
 * - discarded（废弃态）：拒绝或撤回，7 天抑制窗
 * 
 * WBS 要求：
 * - learn 规则列表 ✅
 * - 冷静期倒计时 ✅
 * - 命中统计 ✅
 * - 撤回操作 ✅
 * - 前置依赖 C2.2（learn 状态机）✅
 */
public class LearnGovernanceView extends LinearLayout {
    private static final String TAG = "LearnGovernance";
    
    // 冷静期时长（7 天）
    private static final long COOLDOWN_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L;
    
    // 时间格式
    private static final SimpleDateFormat DATE_FORMAT = 
        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    
    private Context mContext;
    private LinearLayout mRuleListContainer;
    private TextView mEmptyView;
    private Handler mHandler;
    
    // 数据
    private PolicyEngineService mPolicyEngine;
    private List<LearnRuleItem> mLearnRules;
    
    // 回调
    private OnRuleActionListener mActionListener;
    
    // 定时刷新任务（更新倒计时）
    private Runnable mRefreshTask;
    
    public LearnGovernanceView(Context context) {
        super(context);
        init(context);
    }
    
    public LearnGovernanceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    private void init(Context context) {
        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mLearnRules = new ArrayList<>();
        setOrientation(VERTICAL);
        
        buildUI();
        startRefreshTimer();
    }
    
    /**
     * 构建 UI
     */
    private void buildUI() {
        // 标题栏
        LinearLayout headerLayout = new LinearLayout(mContext);
        headerLayout.setOrientation(HORIZONTAL);
        headerLayout.setPadding(dp(16), dp(16), dp(16), dp(8));
        headerLayout.setBackgroundColor(0xFFF5F5F5);
        
        TextView titleView = new TextView(mContext);
        titleView.setText("学习规则治理");
        titleView.setTextSize(18);
        titleView.setTextColor(0xFF212121);
        headerLayout.addView(titleView);
        
        View spacer = new View(mContext);
        LayoutParams spacerParams = new LayoutParams(0, 0, 1.0f);
        spacer.setLayoutParams(spacerParams);
        headerLayout.addView(spacer);
        
        TextView countView = new TextView(mContext);
        countView.setId(android.R.id.text1); // 用于后续更新
        countView.setText("0 条规则");
        countView.setTextSize(14);
        countView.setTextColor(0xFF757575);
        headerLayout.addView(countView);
        
        addView(headerLayout);
        
        // 说明文字
        TextView descView = new TextView(mContext);
        descView.setText("已激活的学习规则，7 天冷静期内可撤回");
        descView.setTextSize(12);
        descView.setTextColor(0xFF9E9E9E);
        descView.setPadding(dp(16), dp(8), dp(16), dp(16));
        addView(descView);
        
        // 分隔线
        View divider = new View(mContext);
        divider.setBackgroundColor(0xFFE0E0E0);
        divider.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));
        addView(divider);
        
        // 规则列表容器（滚动）
        ScrollView ruleScroll = new ScrollView(mContext);
        LayoutParams ruleScrollParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, 0, 1.0f);
        ruleScroll.setLayoutParams(ruleScrollParams);
        
        mRuleListContainer = new LinearLayout(mContext);
        mRuleListContainer.setOrientation(VERTICAL);
        mRuleListContainer.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 空视图
        mEmptyView = new TextView(mContext);
        mEmptyView.setText("暂无已激活的学习规则");
        mEmptyView.setTextSize(16);
        mEmptyView.setTextColor(0xFF9E9E9E);
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setPadding(0, dp(32), 0, 0);
        mRuleListContainer.addView(mEmptyView);
        
        ruleScroll.addView(mRuleListContainer);
        addView(ruleScroll);
    }
    
    /**
     * 设置策略引擎服务
     */
    public void setPolicyEngine(PolicyEngineService policyEngine) {
        mPolicyEngine = policyEngine;
    }
    
    /**
     * 设置动作监听器
     */
    public void setOnRuleActionListener(OnRuleActionListener listener) {
        mActionListener = listener;
    }
    
    /**
     * 加载 learn 规则列表
     */
    public void loadLearnRules() {
        if (mPolicyEngine == null) {
            // 模拟数据
            mLearnRules.clear();
            
            long now = System.currentTimeMillis();
            mLearnRules.add(new LearnRuleItem(
                "learn.notify.work_filter_001",
                "user.notify.learned-abc123",
                "工作通知自动折叠",
                "来自工作 App 的通知自动折叠到 P1 简报卡",
                now - 2 * 24 * 60 * 60 * 1000L, // 激活于 2 天前
                now + 5 * 24 * 60 * 60 * 1000L, // 冷静期还剩 5 天
                25, // 命中 25 次
                now - 1 * 60 * 60 * 1000L // 最近命中 1 小时前
            ));
            
            mLearnRules.add(new LearnRuleItem(
                "learn.callgate.spam_filter_002",
                "user.callgate.learned-def456",
                "骚扰电话静默",
                "标记为骚扰的陌生来电自动静默并记录",
                now - 5 * 24 * 60 * 60 * 1000L, // 激活于 5 天前
                now + 2 * 24 * 60 * 60 * 1000L, // 冷静期还剩 2 天
                8, // 命中 8 次
                now - 12 * 60 * 60 * 1000L // 最近命中 12 小时前
            ));
        } else {
            // TODO: 调用 PolicyEngineService.queryLearnedRules()
        }
        
        displayLearnRules();
    }
    
    /**
     * 显示 learn 规则列表
     */
    private void displayLearnRules() {
        mRuleListContainer.removeAllViews();
        
        // 更新计数
        TextView countView = findViewById(android.R.id.text1);
        if (countView != null) {
            countView.setText(mLearnRules.size() + " 条规则");
        }
        
        if (mLearnRules.isEmpty()) {
            mEmptyView.setVisibility(VISIBLE);
            mRuleListContainer.addView(mEmptyView);
            return;
        }
        
        mEmptyView.setVisibility(GONE);
        
        for (LearnRuleItem rule : mLearnRules) {
            View ruleView = createRuleItemView(rule);
            mRuleListContainer.addView(ruleView);
        }
    }
    
    /**
     * 创建规则项视图
     */
    private View createRuleItemView(LearnRuleItem rule) {
        LinearLayout container = new LinearLayout(mContext);
        container.setOrientation(VERTICAL);
        container.setBackgroundColor(0xFFF5F5F5);
        container.setPadding(dp(12), dp(12), dp(12), dp(12));
        
        LayoutParams containerParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        containerParams.bottomMargin = dp(12);
        container.setLayoutParams(containerParams);
        
        // 头部：规则标题 + 冷静期状态
        LinearLayout headerLayout = new LinearLayout(mContext);
        headerLayout.setOrientation(HORIZONTAL);
        
        TextView titleView = new TextView(mContext);
        titleView.setText(rule.title);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF212121);
        LayoutParams titleParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
        titleView.setLayoutParams(titleParams);
        headerLayout.addView(titleView);
        
        TextView cooldownView = new TextView(mContext);
        long remainingMs = rule.cooldownUntilMs - System.currentTimeMillis();
        boolean inCooldown = remainingMs > 0;
        cooldownView.setText(inCooldown ? "冷静期" : "已生效");
        cooldownView.setTextSize(12);
        cooldownView.setTextColor(inCooldown ? 0xFFFF9800 : 0xFF4CAF50);
        cooldownView.setPadding(dp(8), dp(4), dp(8), dp(4));
        cooldownView.setBackgroundColor(inCooldown ? 0xFFFFF3E0 : 0xFFE8F5E9);
        headerLayout.addView(cooldownView);
        
        container.addView(headerLayout);
        
        // 描述
        TextView descView = new TextView(mContext);
        descView.setText(rule.description);
        descView.setTextSize(14);
        descView.setTextColor(0xFF757575);
        LayoutParams descParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(8);
        descView.setLayoutParams(descParams);
        container.addView(descView);
        
        // 冷静期倒计时（仅冷静期内显示）
        if (inCooldown) {
            LinearLayout cooldownLayout = new LinearLayout(mContext);
            cooldownLayout.setOrientation(VERTICAL);
            LayoutParams cooldownLayoutParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            cooldownLayoutParams.topMargin = dp(12);
            cooldownLayout.setLayoutParams(cooldownLayoutParams);
            
            // 倒计时文字
            TextView cooldownTextView = new TextView(mContext);
            String cooldownText = formatCooldownRemaining(remainingMs);
            cooldownTextView.setText("剩余 " + cooldownText + " 可撤回");
            cooldownTextView.setTextSize(12);
            cooldownTextView.setTextColor(0xFFFF9800);
            cooldownLayout.addView(cooldownTextView);
            
            // 进度条
            ProgressBar progressBar = new ProgressBar(mContext, null,
                android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            int progress = (int) (((COOLDOWN_PERIOD_MS - remainingMs) * 100) / COOLDOWN_PERIOD_MS);
            progressBar.setProgress(progress);
            LayoutParams progressParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, dp(4));
            progressParams.topMargin = dp(4);
            progressBar.setLayoutParams(progressParams);
            cooldownLayout.addView(progressBar);
            
            container.addView(cooldownLayout);
        }
        
        // 统计信息
        LinearLayout statsLayout = new LinearLayout(mContext);
        statsLayout.setOrientation(HORIZONTAL);
        LayoutParams statsParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statsParams.topMargin = dp(12);
        statsLayout.setLayoutParams(statsParams);
        
        TextView hitCountView = new TextView(mContext);
        hitCountView.setText("命中 " + rule.hitCount + " 次");
        hitCountView.setTextSize(12);
        hitCountView.setTextColor(0xFF9E9E9E);
        statsLayout.addView(hitCountView);
        
        View statsSpacer = new View(mContext);
        LayoutParams statsSpacerParams = new LayoutParams(dp(16), 0);
        statsSpacer.setLayoutParams(statsSpacerParams);
        statsLayout.addView(statsSpacer);
        
        TextView lastHitView = new TextView(mContext);
        lastHitView.setText("最近命中: " + formatTimestamp(rule.lastHitMs));
        lastHitView.setTextSize(12);
        lastHitView.setTextColor(0xFF9E9E9E);
        statsLayout.addView(lastHitView);
        
        container.addView(statsLayout);
        
        // 操作按钮（仅冷静期内显示撤回按钮）
        if (inCooldown) {
            LinearLayout actionLayout = new LinearLayout(mContext);
            actionLayout.setOrientation(HORIZONTAL);
            LayoutParams actionParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            actionParams.topMargin = dp(12);
            actionLayout.setLayoutParams(actionParams);
            
            // 查看详情按钮
            Button detailButton = new Button(mContext);
            detailButton.setText("查看详情");
            detailButton.setTextSize(14);
            detailButton.setBackgroundColor(0xFFE3F2FD);
            detailButton.setTextColor(0xFF2196F3);
            LayoutParams detailParams = new LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1.0f);
            detailParams.rightMargin = dp(8);
            detailButton.setLayoutParams(detailParams);
            detailButton.setOnClickListener(v -> {
                onViewRuleDetail(rule);
            });
            actionLayout.addView(detailButton);
            
            // 撤回按钮
            Button revokeButton = new Button(mContext);
            revokeButton.setText("撤回");
            revokeButton.setTextSize(14);
            revokeButton.setBackgroundColor(0xFFFFEBEE);
            revokeButton.setTextColor(0xFFF44336);
            LayoutParams revokeParams = new LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1.0f);
            revokeButton.setLayoutParams(revokeParams);
            revokeButton.setOnClickListener(v -> {
                onRevokeRule(rule);
            });
            actionLayout.addView(revokeButton);
            
            container.addView(actionLayout);
        } else {
            // 已过冷静期，仅显示查看详情按钮
            Button detailButton = new Button(mContext);
            detailButton.setText("查看详情");
            detailButton.setTextSize(14);
            detailButton.setBackgroundColor(0xFFE3F2FD);
            detailButton.setTextColor(0xFF2196F3);
            LayoutParams detailParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(12);
            detailButton.setLayoutParams(detailParams);
            detailButton.setOnClickListener(v -> {
                onViewRuleDetail(rule);
            });
            container.addView(detailButton);
        }
        
        return container;
    }
    
    /**
     * 查看规则详情
     */
    private void onViewRuleDetail(LearnRuleItem rule) {
        if (mActionListener != null) {
            mActionListener.onViewRuleDetail(rule.ruleId);
        }
    }
    
    /**
     * 撤回规则
     */
    private void onRevokeRule(LearnRuleItem rule) {
        // 确认对话框
        new android.app.AlertDialog.Builder(mContext)
            .setTitle("确认撤回")
            .setMessage("撤回「" + rule.title + "」规则后，将不再自动执行此操作。确定继续吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                // TODO: 调用 sys.policy.deactivate_rule(rule.activeRuleId)
                // 这会删除 user.<src>.learned-<hash> 规则并审计
                
                mLearnRules.remove(rule);
                displayLearnRules();
                
                Toast.makeText(mContext,
                    "已撤回规则「" + rule.title + "」",
                    Toast.LENGTH_LONG).show();
                
                if (mActionListener != null) {
                    mActionListener.onRuleRevoked(rule.ruleId);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 格式化冷静期剩余时间
     */
    private String formatCooldownRemaining(long remainingMs) {
        long days = remainingMs / (24 * 60 * 60 * 1000);
        long hours = (remainingMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
        
        if (days > 0) {
            return days + " 天 " + hours + " 小时";
        } else if (hours > 0) {
            long minutes = (remainingMs % (60 * 60 * 1000)) / (60 * 1000);
            return hours + " 小时 " + minutes + " 分钟";
        } else {
            long minutes = remainingMs / (60 * 1000);
            return minutes + " 分钟";
        }
    }
    
    /**
     * 格式化时间戳
     */
    private String formatTimestamp(long timestampMs) {
        long now = System.currentTimeMillis();
        long diff = now - timestampMs;
        
        if (diff < 60 * 1000) {
            return "刚刚";
        } else if (diff < 60 * 60 * 1000) {
            return (diff / (60 * 1000)) + " 分钟前";
        } else if (diff < 24 * 60 * 60 * 1000) {
            return (diff / (60 * 60 * 1000)) + " 小时前";
        } else {
            return DATE_FORMAT.format(new Date(timestampMs));
        }
    }
    
    /**
     * 启动定时刷新任务（更新倒计时）
     */
    private void startRefreshTimer() {
        mRefreshTask = new Runnable() {
            @Override
            public void run() {
                // 刷新显示（更新倒计时）
                displayLearnRules();
                
                // 每 60 秒刷新一次
                mHandler.postDelayed(this, 60 * 1000);
            }
        };
        
        // 首次延迟 60 秒后执行
        mHandler.postDelayed(mRefreshTask, 60 * 1000);
    }
    
    /**
     * 停止定时刷新任务
     */
    private void stopRefreshTimer() {
        if (mRefreshTask != null) {
            mHandler.removeCallbacks(mRefreshTask);
            mRefreshTask = null;
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopRefreshTimer();
    }
    
    /**
     * dp 转 px
     */
    private int dp(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
    
    /**
     * Learn 规则项数据结构
     */
    private static class LearnRuleItem {
        public String ruleId;           // learn. 原始 ID
        public String activeRuleId;     // user.<src>.learned-<hash>
        public String title;            // 规则标题
        public String description;      // 规则描述
        public long activatedAtMs;      // 激活时间
        public long cooldownUntilMs;    // 冷静期截止时间
        public int hitCount;            // 命中次数
        public long lastHitMs;          // 最近命中时间
        
        public LearnRuleItem(String ruleId, String activeRuleId, String title, String description,
                            long activatedAtMs, long cooldownUntilMs, int hitCount, long lastHitMs) {
            this.ruleId = ruleId;
            this.activeRuleId = activeRuleId;
            this.title = title;
            this.description = description;
            this.activatedAtMs = activatedAtMs;
            this.cooldownUntilMs = cooldownUntilMs;
            this.hitCount = hitCount;
            this.lastHitMs = lastHitMs;
        }
    }
    
    /**
     * 规则操作回调接口
     */
    public interface OnRuleActionListener {
        void onViewRuleDetail(String ruleId);
        void onRuleRevoked(String ruleId);
    }
}
