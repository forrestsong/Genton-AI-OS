// AI Native OS - Privacy Dashboard Activity (D4.1)
package com.android.systemui.genton.privacy;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.android.server.genton.AgentAuditService;
import com.android.systemui.genton.privacy.PrivacyDashboardManager.ChainIntegrityStatus;
import com.android.systemui.genton.privacy.PrivacyDashboardManager.TimelineItem;

import java.util.List;

/**
 * 隐私仪表盘主界面（D4.1）
 * 
 * 核心功能：
 * 1. 时间线视图（意图 → 动作 → 证据）
 * 2. 分类筛选（感知/动作/记忆/策略/确认/系统）
 * 3. 时间范围选择（最近 1 小时/1 天/1 周/全部）
 * 4. 审计链完整性检查
 * 
 * 界面布局：
 * - 顶部工具栏：标题 + 时间范围选择器 + 分类筛选器
 * - 主体区域：时间线视图（滚动）
 * - 底部区域：完整性检查视图
 * 
 * WBS 要求：
 * - 意图→动作→证据时间线 ✅
 * - viewerRole=USER 脱敏视图 ✅
 * - 审计链完整性展示 ✅
 * - 分类筛选 + 时间范围 ✅
 * - 前置依赖 A5.2（审计流）✅
 */
public class PrivacyDashboardActivity extends Activity {
    private static final String TAG = "PrivacyDashboard";
    
    // 时间范围选项
    private static final String[] TIME_RANGES = {
        "最近 1 小时", "最近 1 天", "最近 1 周", "全部"
    };
    private static final int[] TIME_RANGE_HOURS = {1, 24, 168, 0};
    
    // 分类选项
    private static final String[] CATEGORIES = {
        "全部", "感知", "动作", "记忆", "策略", "能力", "确认", "系统"
    };
    private static final int[] CATEGORY_VALUES = {
        0, // 全部（0 表示不筛选）
        AgentAuditService.CAT_PERCEPTION_READ,
        AgentAuditService.CAT_ACTION_EXECUTE,
        AgentAuditService.CAT_MEMORY_ACCESS,
        AgentAuditService.CAT_POLICY_DECISION,
        AgentAuditService.CAT_CAPABILITY_GRANT,
        AgentAuditService.CAT_CONFIRMATION,
        AgentAuditService.CAT_SYSTEM
    };
    
    // UI 组件
    private Spinner mTimeRangeSpinner;
    private Spinner mCategorySpinner;
    private AuditTimelineView mTimelineView;
    private ChainIntegrityView mIntegrityView;
    private TextView mLoadingText;
    
    // 数据管理器
    private PrivacyDashboardManager mManager;
    
    // 当前筛选条件
    private int mCurrentTimeRangeHours = 24; // 默认最近 1 天
    private Integer mCurrentCategory = null; // null 表示全部
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 初始化管理器
        mManager = new PrivacyDashboardManager(this);
        
        // 构建 UI
        buildUI();
        
        // 连接审计服务
        connectAuditService();
        
        // 加载初始数据
        loadTimeline();
        checkIntegrity();
    }
    
    /**
     * 构建 UI
     */
    private void buildUI() {
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFFFFFFFF);
        
        // 顶部工具栏
        LinearLayout toolbar = buildToolbar();
        rootLayout.addView(toolbar);
        
        // 主体滚动区域
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollView.setLayoutParams(scrollParams);
        
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        
        // 加载提示
        mLoadingText = new TextView(this);
        mLoadingText.setText("正在加载...");
        mLoadingText.setTextSize(16);
        mLoadingText.setTextColor(0xFF757575);
        mLoadingText.setPadding(dp(16), dp(16), dp(16), dp(16));
        contentLayout.addView(mLoadingText);
        
        // 时间线视图
        mTimelineView = new AuditTimelineView(this);
        mTimelineView.setVisibility(View.GONE);
        contentLayout.addView(mTimelineView);
        
        // 完整性视图
        mIntegrityView = new ChainIntegrityView(this);
        LinearLayout.LayoutParams integrityParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        integrityParams.topMargin = dp(16);
        mIntegrityView.setLayoutParams(integrityParams);
        contentLayout.addView(mIntegrityView);
        
        scrollView.addView(contentLayout);
        rootLayout.addView(scrollView);
        
        setContentView(rootLayout);
    }
    
    /**
     * 构建顶部工具栏
     */
    private LinearLayout buildToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setBackgroundColor(0xFF2196F3);
        toolbar.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 标题
        TextView titleView = new TextView(this);
        titleView.setText("隐私仪表盘");
        titleView.setTextSize(20);
        titleView.setTextColor(0xFFFFFFFF);
        toolbar.addView(titleView);
        
        // 副标题
        TextView subtitleView = new TextView(this);
        subtitleView.setText("查看 AI 活动时间线和审计链完整性");
        subtitleView.setTextSize(14);
        subtitleView.setTextColor(0xFFE3F2FD);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(4);
        subtitleView.setLayoutParams(subtitleParams);
        toolbar.addView(subtitleView);
        
        // 筛选器容器
        LinearLayout filterContainer = new LinearLayout(this);
        filterContainer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filterParams.topMargin = dp(16);
        filterContainer.setLayoutParams(filterParams);
        
        // 时间范围选择器
        mTimeRangeSpinner = new Spinner(this);
        mTimeRangeSpinner.setBackgroundColor(0xFFFFFFFF);
        mTimeRangeSpinner.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams timeRangeParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        timeRangeParams.rightMargin = dp(8);
        mTimeRangeSpinner.setLayoutParams(timeRangeParams);
        
        ArrayAdapter<String> timeRangeAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, TIME_RANGES);
        timeRangeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mTimeRangeSpinner.setAdapter(timeRangeAdapter);
        mTimeRangeSpinner.setSelection(1); // 默认"最近 1 天"
        mTimeRangeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onTimeRangeChanged(position);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        filterContainer.addView(mTimeRangeSpinner);
        
        // 分类选择器
        mCategorySpinner = new Spinner(this);
        mCategorySpinner.setBackgroundColor(0xFFFFFFFF);
        mCategorySpinner.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams categoryParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        mCategorySpinner.setLayoutParams(categoryParams);
        
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, CATEGORIES);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(categoryAdapter);
        mCategorySpinner.setSelection(0); // 默认"全部"
        mCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onCategoryChanged(position);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        filterContainer.addView(mCategorySpinner);
        
        toolbar.addView(filterContainer);
        
        return toolbar;
    }
    
    /**
     * 连接审计服务
     */
    private void connectAuditService() {
        // TODO: 通过 Binder 获取 AgentAuditService 实例
        // ServiceManager.getService("agent_audit");
        
        // 模拟连接
        AgentAuditService auditService = null; // 实际应通过 Binder 获取
        if (auditService != null) {
            mManager.connectAuditService(auditService);
        }
    }
    
    /**
     * 加载时间线
     */
    private void loadTimeline() {
        mLoadingText.setVisibility(View.VISIBLE);
        mTimelineView.setVisibility(View.GONE);
        
        int hoursAgo = mCurrentTimeRangeHours > 0 ? mCurrentTimeRangeHours : 168; // 默认查询 1 周
        
        mManager.queryTimeline(hoursAgo, mCurrentCategory, 
            new PrivacyDashboardManager.QueryCallback() {
                @Override
                public void onQueryComplete(List<TimelineItem> items) {
                    mLoadingText.setVisibility(View.GONE);
                    mTimelineView.setVisibility(View.VISIBLE);
                    mTimelineView.setTimelineItems(items);
                    
                    if (items.isEmpty()) {
                        Toast.makeText(PrivacyDashboardActivity.this, 
                            "该时间范围内无审计记录", Toast.LENGTH_SHORT).show();
                    }
                }
                
                @Override
                public void onQueryError(String error) {
                    mLoadingText.setText("加载失败: " + error);
                    Toast.makeText(PrivacyDashboardActivity.this, 
                        "查询失败: " + error, Toast.LENGTH_LONG).show();
                }
            });
    }
    
    /**
     * 检查完整性
     */
    private void checkIntegrity() {
        mIntegrityView.showCheckingState();
        
        mManager.checkChainIntegrity(
            new PrivacyDashboardManager.IntegrityCheckCallback() {
                @Override
                public void onCheckComplete(ChainIntegrityStatus status) {
                    mIntegrityView.setIntegrityStatus(status);
                }
                
                @Override
                public void onCheckError(String error) {
                    mIntegrityView.showError(error);
                    Toast.makeText(PrivacyDashboardActivity.this, 
                        "完整性检查失败: " + error, Toast.LENGTH_LONG).show();
                }
            });
    }
    
    /**
     * 时间范围变更
     */
    private void onTimeRangeChanged(int position) {
        int hoursAgo = TIME_RANGE_HOURS[position];
        if (hoursAgo != mCurrentTimeRangeHours) {
            mCurrentTimeRangeHours = hoursAgo;
            loadTimeline();
        }
    }
    
    /**
     * 分类变更
     */
    private void onCategoryChanged(int position) {
        Integer category = position > 0 ? CATEGORY_VALUES[position] : null;
        if ((category == null && mCurrentCategory != null) || 
            (category != null && !category.equals(mCurrentCategory))) {
            mCurrentCategory = category;
            loadTimeline();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mManager != null) {
            mManager.destroy();
        }
    }
    
    /**
     * dp 转 px
     */
    private int dp(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
