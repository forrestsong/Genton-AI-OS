package com.android.systemui.ainative.memory;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * D4.2 记忆/实体管理 UI - Learn 规则详情页
 * 
 * 功能范围：
 * - 显示 learn 规则完整定义（原始 learn. ID、激活后 user.<src>.learned-<hash>、规则内容）
 * - 激活历史（从 suggestion → active 的时间、来源会话）
 * - 命中详情（命中次数、最近命中时间、命中历史列表）
 * - 7 天冷静期倒计时（在冷静期内显示撤回按钮）
 * - 撤回操作（调用 sys.policy.deactivate_rule，删除 user.<src>.learned-<hash> 规则并审计）
 * 
 * 依赖接口：
 * - PolicyEngineService.getRuleDetail(ruleId): 获取规则完整定义
 * - PolicyEngineService.getRuleActivationHistory(ruleId): 获取激活历史
 * - PolicyEngineService.getRuleHitHistory(ruleId, limit): 获取命中历史
 * - PolicyEngineService.deactivateRule(ruleId, reason): 撤回规则
 * 
 * 前置任务：
 * - A5.4（本体统一语义层）：提供实体类型和语义推理支持
 * - C2.2（learn 状态机）：定义 learn 规则生命周期（suggestion → active → discarded）
 * 
 * @since D4.2 (随 A5 后端服务于 2027-Q2 生效)
 */
public class LearnRuleDetailView extends ScrollView {

    private static final String TAG = "LearnRuleDetailView";

    // 冷静期时长（7 天）
    private static final long COOLDOWN_PERIOD_MS = 7 * 24 * 60 * 60 * 1000L;

    // 日期格式化
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final SimpleDateFormat RELATIVE_DATE_FORMAT = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    // UI 组件
    private LinearLayout mRootLayout;
    private TextView mRuleIdText;
    private TextView mActiveRuleIdText;
    private TextView mRuleTitleText;
    private TextView mRuleDescriptionText;
    private TextView mActivatedAtText;
    private TextView mCooldownStatusText;
    private Button mRevokeButton;
    private TextView mHitCountText;
    private TextView mLastHitText;
    private LinearLayout mHitHistoryContainer;
    private TextView mActivationHistoryText;

    // 数据
    private LearnRuleDetail mRuleDetail;

    // 回调
    private OnRuleRevokedListener mOnRuleRevokedListener;

    public interface OnRuleRevokedListener {
        void onRuleRevoked(String activeRuleId);
    }

    public LearnRuleDetailView(Context context) {
        super(context);
        init();
    }

    public LearnRuleDetailView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LearnRuleDetailView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundColor(Color.WHITE);
        setFillViewport(true);

        mRootLayout = new LinearLayout(getContext());
        mRootLayout.setOrientation(LinearLayout.VERTICAL);
        mRootLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
        addView(mRootLayout);

        buildUI();
    }

    private void buildUI() {
        // 1. 规则标识区域
        TextView idSectionTitle = createSectionTitle("规则标识");
        mRootLayout.addView(idSectionTitle);

        mRuleIdText = createInfoText("原始 ID: 加载中...");
        mRootLayout.addView(mRuleIdText);

        mActiveRuleIdText = createInfoText("激活 ID: 加载中...");
        mActiveRuleIdText.setPadding(dp(0), dp(4), dp(0), dp(0));
        mRootLayout.addView(mActiveRuleIdText);

        // 2. 规则内容区域
        TextView contentSectionTitle = createSectionTitle("规则内容");
        contentSectionTitle.setPadding(dp(0), dp(16), dp(0), dp(8));
        mRootLayout.addView(contentSectionTitle);

        mRuleTitleText = createInfoText("标题: 加载中...");
        mRuleTitleText.setTextSize(16);
        mRuleTitleText.setTypeface(null, Typeface.BOLD);
        mRootLayout.addView(mRuleTitleText);

        mRuleDescriptionText = createInfoText("描述: 加载中...");
        mRuleDescriptionText.setPadding(dp(0), dp(4), dp(0), dp(0));
        mRuleDescriptionText.setTextColor(Color.parseColor("#666666"));
        mRootLayout.addView(mRuleDescriptionText);

        // 3. 激活状态区域
        TextView statusSectionTitle = createSectionTitle("激活状态");
        statusSectionTitle.setPadding(dp(0), dp(16), dp(0), dp(8));
        mRootLayout.addView(statusSectionTitle);

        mActivatedAtText = createInfoText("激活时间: 加载中...");
        mRootLayout.addView(mActivatedAtText);

        mCooldownStatusText = createInfoText("冷静期: 加载中...");
        mCooldownStatusText.setPadding(dp(0), dp(4), dp(0), dp(0));
        mRootLayout.addView(mCooldownStatusText);

        // 撤回按钮
        mRevokeButton = new Button(getContext());
        mRevokeButton.setText("撤回规则");
        mRevokeButton.setBackgroundColor(Color.parseColor("#FF6B6B"));
        mRevokeButton.setTextColor(Color.WHITE);
        mRevokeButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        mRevokeButton.setOnClickListener(v -> onRevokeButtonClick());
        mRevokeButton.setVisibility(View.GONE); // 默认隐藏，冷静期内才显示
        LinearLayout.LayoutParams revokeButtonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        revokeButtonParams.topMargin = dp(8);
        mRevokeButton.setLayoutParams(revokeButtonParams);
        mRootLayout.addView(mRevokeButton);

        // 4. 激活历史区域
        TextView activationSectionTitle = createSectionTitle("激活历史");
        activationSectionTitle.setPadding(dp(0), dp(16), dp(0), dp(8));
        mRootLayout.addView(activationSectionTitle);

        mActivationHistoryText = createInfoText("加载中...");
        mActivationHistoryText.setTextColor(Color.parseColor("#666666"));
        mRootLayout.addView(mActivationHistoryText);

        // 5. 命中统计区域
        TextView hitSectionTitle = createSectionTitle("命中统计");
        hitSectionTitle.setPadding(dp(0), dp(16), dp(0), dp(8));
        mRootLayout.addView(hitSectionTitle);

        mHitCountText = createInfoText("总命中次数: 加载中...");
        mRootLayout.addView(mHitCountText);

        mLastHitText = createInfoText("最近命中: 加载中...");
        mLastHitText.setPadding(dp(0), dp(4), dp(0), dp(0));
        mRootLayout.addView(mLastHitText);

        // 6. 命中历史列表
        TextView hitHistorySectionTitle = createSectionTitle("命中历史");
        hitHistorySectionTitle.setPadding(dp(0), dp(16), dp(0), dp(8));
        mRootLayout.addView(hitHistorySectionTitle);

        mHitHistoryContainer = new LinearLayout(getContext());
        mHitHistoryContainer.setOrientation(LinearLayout.VERTICAL);
        mRootLayout.addView(mHitHistoryContainer);
    }

    private TextView createSectionTitle(String text) {
        TextView textView = new TextView(getContext());
        textView.setText(text);
        textView.setTextSize(18);
        textView.setTypeface(null, Typeface.BOLD);
        textView.setTextColor(Color.BLACK);
        return textView;
    }

    private TextView createInfoText(String text) {
        TextView textView = new TextView(getContext());
        textView.setText(text);
        textView.setTextSize(14);
        textView.setTextColor(Color.parseColor("#333333"));
        return textView;
    }

    /**
     * 设置规则详情数据并刷新 UI
     */
    public void setRuleDetail(LearnRuleDetail detail) {
        mRuleDetail = detail;
        updateUI();
    }

    /**
     * 设置撤回监听器
     */
    public void setOnRuleRevokedListener(OnRuleRevokedListener listener) {
        mOnRuleRevokedListener = listener;
    }

    /**
     * 刷新 UI
     */
    private void updateUI() {
        if (mRuleDetail == null) {
            return;
        }

        // 1. 规则标识
        mRuleIdText.setText("原始 ID: " + mRuleDetail.ruleId);
        mActiveRuleIdText.setText("激活 ID: " + mRuleDetail.activeRuleId);

        // 2. 规则内容
        mRuleTitleText.setText(mRuleDetail.title);
        mRuleDescriptionText.setText(mRuleDetail.description);

        // 3. 激活状态
        mActivatedAtText.setText("激活时间: " + DATE_FORMAT.format(new Date(mRuleDetail.activatedAtMs)));

        // 冷静期状态
        long now = System.currentTimeMillis();
        long cooldownUntil = mRuleDetail.activatedAtMs + COOLDOWN_PERIOD_MS;
        if (now < cooldownUntil) {
            // 仍在冷静期内
            long remainingMs = cooldownUntil - now;
            long remainingDays = remainingMs / (24 * 60 * 60 * 1000);
            long remainingHours = (remainingMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
            mCooldownStatusText.setText(String.format(Locale.getDefault(),
                    "冷静期: 还剩 %d 天 %d 小时（可撤回）",
                    remainingDays, remainingHours));
            mCooldownStatusText.setTextColor(Color.parseColor("#FF9800")); // 橙色
            mRevokeButton.setVisibility(View.VISIBLE);
        } else {
            // 冷静期已过
            mCooldownStatusText.setText("冷静期: 已过期（不可撤回）");
            mCooldownStatusText.setTextColor(Color.parseColor("#999999")); // 灰色
            mRevokeButton.setVisibility(View.GONE);
        }

        // 4. 激活历史
        if (mRuleDetail.activationHistory != null && !mRuleDetail.activationHistory.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mRuleDetail.activationHistory.size(); i++) {
                ActivationHistoryItem item = mRuleDetail.activationHistory.get(i);
                sb.append(String.format(Locale.getDefault(),
                        "%d. %s\n   来源: %s\n   触发会话: %s\n",
                        i + 1,
                        DATE_FORMAT.format(new Date(item.timestampMs)),
                        item.source,
                        item.sessionId));
            }
            mActivationHistoryText.setText(sb.toString().trim());
        } else {
            mActivationHistoryText.setText("暂无激活历史");
        }

        // 5. 命中统计
        mHitCountText.setText("总命中次数: " + mRuleDetail.hitCount);
        if (mRuleDetail.lastHitMs > 0) {
            mLastHitText.setText("最近命中: " + DATE_FORMAT.format(new Date(mRuleDetail.lastHitMs)));
        } else {
            mLastHitText.setText("最近命中: 暂无");
        }

        // 6. 命中历史列表
        mHitHistoryContainer.removeAllViews();
        if (mRuleDetail.hitHistory != null && !mRuleDetail.hitHistory.isEmpty()) {
            for (HitHistoryItem item : mRuleDetail.hitHistory) {
                View hitCard = createHitHistoryCard(item);
                mHitHistoryContainer.addView(hitCard);
            }
        } else {
            TextView emptyText = createInfoText("暂无命中记录");
            emptyText.setTextColor(Color.parseColor("#999999"));
            emptyText.setPadding(dp(8), dp(8), dp(8), dp(8));
            mHitHistoryContainer.addView(emptyText);
        }
    }

    /**
     * 创建命中历史卡片
     */
    private View createHitHistoryCard(HitHistoryItem item) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#F5F5F5"));
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);

        // 时间
        TextView timeText = new TextView(getContext());
        timeText.setText(RELATIVE_DATE_FORMAT.format(new Date(item.timestampMs)));
        timeText.setTextSize(12);
        timeText.setTextColor(Color.parseColor("#999999"));
        card.addView(timeText);

        // 触发场景
        TextView scenarioText = new TextView(getContext());
        scenarioText.setText("场景: " + item.scenario);
        scenarioText.setTextSize(14);
        scenarioText.setTextColor(Color.parseColor("#333333"));
        scenarioText.setPadding(dp(0), dp(4), dp(0), dp(0));
        card.addView(scenarioText);

        // 影响动作
        TextView actionText = new TextView(getContext());
        actionText.setText("动作: " + item.action);
        actionText.setTextSize(12);
        actionText.setTextColor(Color.parseColor("#666666"));
        actionText.setPadding(dp(0), dp(2), dp(0), dp(0));
        card.addView(actionText);

        return card;
    }

    /**
     * 撤回按钮点击处理
     */
    private void onRevokeButtonClick() {
        if (mRuleDetail == null) {
            return;
        }

        // 确认对话框
        new AlertDialog.Builder(getContext())
                .setTitle("确认撤回规则")
                .setMessage("撤回后，该 learn 规则将被停用，Agent 将不再按此规则行动。\n\n规则名称: "
                        + mRuleDetail.title + "\n\n此操作将被审计，确定继续吗？")
                .setPositiveButton("确定撤回", (dialog, which) -> {
                    performRevokeRule();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 执行撤回规则
     */
    private void performRevokeRule() {
        if (mRuleDetail == null) {
            return;
        }

        // TODO: 调用 PolicyEngineService.deactivateRule()
        // PolicyEngineService.deactivateRule(
        //     mRuleDetail.activeRuleId,
        //     "user_revoke_within_cooldown"
        // );
        //
        // 预期行为：
        // 1. 删除 user.<src>.learned-<hash> 规则
        // 2. 记录审计日志（type=POLICY_DEACTIVATE, rule_id=..., reason=user_revoke_within_cooldown）
        // 3. 更新 learn 状态机状态为 discarded

        // 模拟成功
        if (mOnRuleRevokedListener != null) {
            mOnRuleRevokedListener.onRuleRevoked(mRuleDetail.activeRuleId);
        }

        // 提示用户
        new AlertDialog.Builder(getContext())
                .setTitle("撤回成功")
                .setMessage("规则已撤回，Agent 将不再执行此规则。")
                .setPositiveButton("知道了", (dialog, which) -> {
                    // 返回上一页或刷新列表
                })
                .show();
    }

    /**
     * 加载规则详情（异步）
     */
    public void loadRuleDetail(String ruleId, String activeRuleId) {
        // TODO: 异步查询
        new Thread(() -> {
            LearnRuleDetail detail = queryRuleDetailInternal(ruleId, activeRuleId);
            post(() -> setRuleDetail(detail));
        }).start();
    }

    /**
     * 查询规则详情（内部实现）
     */
    private LearnRuleDetail queryRuleDetailInternal(String ruleId, String activeRuleId) {
        // TODO: 调用 PolicyEngineService.getRuleDetail(activeRuleId)
        // PolicyEngineService.RuleDetail detail = PolicyEngineService.getRuleDetail(activeRuleId);
        // return new LearnRuleDetail(
        //     ruleId,
        //     activeRuleId,
        //     detail.title,
        //     detail.description,
        //     detail.activatedAtMs,
        //     detail.hitCount,
        //     detail.lastHitMs,
        //     detail.activationHistory,
        //     detail.hitHistory
        // );

        // 模拟数据
        LearnRuleDetail detail = new LearnRuleDetail();
        detail.ruleId = ruleId;
        detail.activeRuleId = activeRuleId;
        detail.title = "工作日早晨提醒查看日程";
        detail.description = "当检测到工作日早晨（7:00-9:00）且用户空闲时，主动提醒查看今日日程安排";
        detail.activatedAtMs = System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L; // 3 天前激活
        detail.hitCount = 12;
        detail.lastHitMs = System.currentTimeMillis() - 6 * 60 * 60 * 1000L; // 6 小时前

        // 激活历史
        detail.activationHistory = new ArrayList<>();
        ActivationHistoryItem activationItem = new ActivationHistoryItem();
        activationItem.timestampMs = detail.activatedAtMs;
        activationItem.source = "form_submission";
        activationItem.sessionId = "session_20270203_083022";
        detail.activationHistory.add(activationItem);

        // 命中历史
        detail.hitHistory = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            HitHistoryItem hitItem = new HitHistoryItem();
            hitItem.timestampMs = System.currentTimeMillis() - (i + 1) * 24 * 60 * 60 * 1000L;
            hitItem.scenario = "工作日早晨空闲";
            hitItem.action = "显示提醒胶囊：今日有 3 场会议";
            detail.hitHistory.add(hitItem);
        }

        // 模拟延迟
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return detail;
    }

    private int dp(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    // ========== 数据结构 ==========

    /**
     * Learn 规则详情
     */
    public static class LearnRuleDetail {
        public String ruleId;                           // learn. 原始 ID
        public String activeRuleId;                     // user.<src>.learned-<hash>
        public String title;                            // 规则标题
        public String description;                      // 规则描述
        public long activatedAtMs;                      // 激活时间
        public int hitCount;                            // 总命中次数
        public long lastHitMs;                          // 最近命中时间
        public List<ActivationHistoryItem> activationHistory; // 激活历史
        public List<HitHistoryItem> hitHistory;        // 命中历史
    }

    /**
     * 激活历史项
     */
    public static class ActivationHistoryItem {
        public long timestampMs;    // 激活时间
        public String source;       // 来源（form_submission/auto_suggest）
        public String sessionId;    // 触发会话 ID
    }

    /**
     * 命中历史项
     */
    public static class HitHistoryItem {
        public long timestampMs;    // 命中时间
        public String scenario;     // 触发场景描述
        public String action;       // 影响的动作（触发的提醒、调整的设置等）
    }
}
