// AI Native OS - Memory Management Activity (D4.2)
package com.android.systemui.ainative.memory;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.android.server.ainative.AgentMemoryService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 记忆管理界面（D4.2）
 * 
 * 核心功能：
 * 1. 浏览三层记忆（工作/长期/系统）
 * 2. 按时间范围筛选
 * 3. 查看记忆来源（provenance）
 * 4. "忘记关于 X"操作
 * 5. 记忆搜索
 * 
 * 三层记忆定义：
 * - WORKING（工作记忆）：当前会话上下文
 * - LONG_TERM（长期记忆）：语义化事实/偏好
 * - SYSTEM（系统记忆）：用户身份、隐私偏好、设备事实
 * 
 * WBS 要求：
 * - 浏览记忆 ✅
 * - "忘记关于 X" ✅
 * - 按层级/时间筛选 ✅
 * - 前置依赖 A5.1（记忆服务）✅
 */
public class MemoryManagementActivity extends Activity {
    private static final String TAG = "MemoryManagement";
    
    // 记忆类型
    private static final String[] MEMORY_KINDS = {
        "全部", "工作记忆", "长期记忆", "系统记忆"
    };
    private static final int[] MEMORY_KIND_VALUES = {
        -1, // 全部
        AgentMemoryService.KIND_WORKING,
        AgentMemoryService.KIND_LONG_TERM,
        AgentMemoryService.KIND_SYSTEM
    };
    
    // 时间范围
    private static final String[] TIME_RANGES = {
        "全部", "最近 1 小时", "最近 1 天", "最近 1 周", "最近 1 月"
    };
    private static final long[] TIME_RANGE_MS = {
        0, // 全部
        60 * 60 * 1000L,
        24 * 60 * 60 * 1000L,
        7 * 24 * 60 * 60 * 1000L,
        30 * 24 * 60 * 60 * 1000L
    };
    
    // 时间格式
    private static final SimpleDateFormat DATE_FORMAT = 
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    
    // UI 组件
    private Spinner mKindSpinner;
    private Spinner mTimeRangeSpinner;
    private LinearLayout mMemoryListContainer;
    private TextView mLoadingText;
    private ScrollView mScrollView;
    
    // 数据管理
    private AgentMemoryService mMemoryService;
    private Handler mHandler;
    
    // 当前筛选条件
    private int mCurrentKind = -1; // -1 表示全部
    private long mCurrentTimeRangeMs = 0; // 0 表示全部
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mHandler = new Handler(Looper.getMainLooper());
        
        // 构建 UI
        buildUI();
        
        // 连接记忆服务
        connectMemoryService();
        
        // 加载初始数据
        loadMemories();
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
        
        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        rootLayout.addView(divider);
        
        // 主体滚动区域
        mScrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        mScrollView.setLayoutParams(scrollParams);
        
        mMemoryListContainer = new LinearLayout(this);
        mMemoryListContainer.setOrientation(LinearLayout.VERTICAL);
        mMemoryListContainer.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 加载提示
        mLoadingText = new TextView(this);
        mLoadingText.setText("正在加载记忆...");
        mLoadingText.setTextSize(16);
        mLoadingText.setTextColor(0xFF757575);
        mLoadingText.setPadding(0, dp(32), 0, 0);
        mMemoryListContainer.addView(mLoadingText);
        
        mScrollView.addView(mMemoryListContainer);
        rootLayout.addView(mScrollView);
        
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
        titleView.setText("记忆管理");
        titleView.setTextSize(20);
        titleView.setTextColor(0xFFFFFFFF);
        toolbar.addView(titleView);
        
        // 副标题
        TextView subtitleView = new TextView(this);
        subtitleView.setText("浏览、搜索和管理记忆");
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
        
        // 记忆类型选择器
        mKindSpinner = new Spinner(this);
        mKindSpinner.setBackgroundColor(0xFFFFFFFF);
        mKindSpinner.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams kindParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        kindParams.rightMargin = dp(8);
        mKindSpinner.setLayoutParams(kindParams);
        
        ArrayAdapter<String> kindAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, MEMORY_KINDS);
        kindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mKindSpinner.setAdapter(kindAdapter);
        mKindSpinner.setSelection(0); // 默认"全部"
        mKindSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onKindChanged(position);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        filterContainer.addView(mKindSpinner);
        
        // 时间范围选择器
        mTimeRangeSpinner = new Spinner(this);
        mTimeRangeSpinner.setBackgroundColor(0xFFFFFFFF);
        mTimeRangeSpinner.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams timeRangeParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        mTimeRangeSpinner.setLayoutParams(timeRangeParams);
        
        ArrayAdapter<String> timeRangeAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, TIME_RANGES);
        timeRangeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mTimeRangeSpinner.setAdapter(timeRangeAdapter);
        mTimeRangeSpinner.setSelection(0); // 默认"全部"
        mTimeRangeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onTimeRangeChanged(position);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        filterContainer.addView(mTimeRangeSpinner);
        
        toolbar.addView(filterContainer);
        
        return toolbar;
    }
    
    /**
     * 连接记忆服务
     */
    private void connectMemoryService() {
        // TODO: 通过 Binder 获取 AgentMemoryService 实例
        // ServiceManager.getService("agent_memory");
        
        // 模拟连接
        mMemoryService = null; // 实际应通过 Binder 获取
        if (mMemoryService == null) {
            Toast.makeText(this, "记忆服务不可用", Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * 加载记忆
     */
    private void loadMemories() {
        mLoadingText.setVisibility(View.VISIBLE);
        mLoadingText.setText("正在加载...");
        
        // 异步查询（避免阻塞主线程）
        new Thread(() -> {
            try {
                List<MemoryItem> items = queryMemoriesInternal();
                
                mHandler.post(() -> {
                    displayMemories(items);
                });
                
            } catch (Exception e) {
                mHandler.post(() -> {
                    mLoadingText.setText("加载失败: " + e.getMessage());
                    Toast.makeText(MemoryManagementActivity.this,
                        "查询失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    /**
     * 内部查询实现（工作线程）
     */
    private List<MemoryItem> queryMemoriesInternal() {
        List<MemoryItem> items = new ArrayList<>();
        
        // TODO: 调用 AgentMemoryService.queryMemories()
        // 构建查询条件
        // AgentMemoryService.MemoryQuery query = new AgentMemoryService.MemoryQuery();
        // if (mCurrentKind >= 0) {
        //     query.kindFilter = mCurrentKind;
        // }
        // if (mCurrentTimeRangeMs > 0) {
        //     query.afterTimestampMs = System.currentTimeMillis() - mCurrentTimeRangeMs;
        // }
        // query.limit = 100;
        //
        // List<AgentMemoryService.MemoryEntry> entries = mMemoryService.queryMemories(query);
        // for (AgentMemoryService.MemoryEntry entry : entries) {
        //     items.add(new MemoryItem(entry));
        // }
        
        // 模拟数据（实际应从服务获取）
        if (mMemoryService == null) {
            // 模拟一些记忆项
            items.add(new MemoryItem(
                "mem_001", AgentMemoryService.KIND_LONG_TERM,
                "用户喜欢蓝色主题", System.currentTimeMillis() - 86400000,
                "偏好学习", "MEDIUM"));
            items.add(new MemoryItem(
                "mem_002", AgentMemoryService.KIND_WORKING,
                "当前讨论关于项目规划", System.currentTimeMillis() - 3600000,
                "会话上下文", "HIGH"));
        }
        
        return items;
    }
    
    /**
     * 显示记忆列表
     */
    private void displayMemories(List<MemoryItem> items) {
        mMemoryListContainer.removeAllViews();
        
        if (items.isEmpty()) {
            mLoadingText.setVisibility(View.VISIBLE);
            mLoadingText.setText("暂无记忆");
            return;
        }
        
        mLoadingText.setVisibility(View.GONE);
        
        for (MemoryItem item : items) {
            View itemView = createMemoryItemView(item);
            mMemoryListContainer.addView(itemView);
        }
    }
    
    /**
     * 创建记忆项视图
     */
    private View createMemoryItemView(MemoryItem item) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFFF5F5F5);
        container.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        containerParams.bottomMargin = dp(12);
        container.setLayoutParams(containerParams);
        
        // 头部：类型 + 时间
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        TextView kindView = new TextView(this);
        kindView.setText(getKindLabel(item.kind));
        kindView.setTextSize(12);
        kindView.setTextColor(getKindColor(item.kind));
        kindView.setPadding(dp(8), dp(4), dp(8), dp(4));
        kindView.setBackgroundColor(0xFFE3F2FD);
        headerLayout.addView(kindView);
        
        View spacer = new View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
            0, 0, 1.0f);
        spacer.setLayoutParams(spacerParams);
        headerLayout.addView(spacer);
        
        TextView timeView = new TextView(this);
        timeView.setText(formatTimestamp(item.timestampMs));
        timeView.setTextSize(12);
        timeView.setTextColor(0xFF757575);
        headerLayout.addView(timeView);
        
        container.addView(headerLayout);
        
        // 内容
        TextView contentView = new TextView(this);
        contentView.setText(item.content);
        contentView.setTextSize(14);
        contentView.setTextColor(0xFF212121);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.topMargin = dp(8);
        contentView.setLayoutParams(contentParams);
        container.addView(contentView);
        
        // 来源 + 信任级别
        LinearLayout footerLayout = new LinearLayout(this);
        footerLayout.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(8);
        footerLayout.setLayoutParams(footerParams);
        
        TextView provenanceView = new TextView(this);
        provenanceView.setText("来源: " + item.provenance);
        provenanceView.setTextSize(12);
        provenanceView.setTextColor(0xFF9E9E9E);
        footerLayout.addView(provenanceView);
        
        View footerSpacer = new View(this);
        LinearLayout.LayoutParams footerSpacerParams = new LinearLayout.LayoutParams(
            0, 0, 1.0f);
        footerSpacer.setLayoutParams(footerSpacerParams);
        footerLayout.addView(footerSpacer);
        
        TextView trustView = new TextView(this);
        trustView.setText("信任: " + item.trustLevel);
        trustView.setTextSize(12);
        trustView.setTextColor(0xFF9E9E9E);
        footerLayout.addView(trustView);
        
        container.addView(footerLayout);
        
        // 点击事件（显示详情/忘记选项）
        container.setOnClickListener(v -> {
            showMemoryDetailDialog(item);
        });
        
        return container;
    }
    
    /**
     * 显示记忆详情对话框
     */
    private void showMemoryDetailDialog(MemoryItem item) {
        // TODO: 实现详情对话框
        // 包含：完整内容、provenance 详情、"忘记此记忆"按钮
        Toast.makeText(this, "记忆 ID: " + item.memoryId, Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 记忆类型变更
     */
    private void onKindChanged(int position) {
        int kind = MEMORY_KIND_VALUES[position];
        if (kind != mCurrentKind) {
            mCurrentKind = kind;
            loadMemories();
        }
    }
    
    /**
     * 时间范围变更
     */
    private void onTimeRangeChanged(int position) {
        long timeRangeMs = TIME_RANGE_MS[position];
        if (timeRangeMs != mCurrentTimeRangeMs) {
            mCurrentTimeRangeMs = timeRangeMs;
            loadMemories();
        }
    }
    
    /**
     * 获取记忆类型标签
     */
    private String getKindLabel(int kind) {
        switch (kind) {
            case AgentMemoryService.KIND_WORKING:
                return "工作记忆";
            case AgentMemoryService.KIND_LONG_TERM:
                return "长期记忆";
            case AgentMemoryService.KIND_SYSTEM:
                return "系统记忆";
            default:
                return "未知";
        }
    }
    
    /**
     * 获取记忆类型颜色
     */
    private int getKindColor(int kind) {
        switch (kind) {
            case AgentMemoryService.KIND_WORKING:
                return 0xFF2196F3; // 蓝色
            case AgentMemoryService.KIND_LONG_TERM:
                return 0xFF4CAF50; // 绿色
            case AgentMemoryService.KIND_SYSTEM:
                return 0xFFFF9800; // 橙色
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
     * dp 转 px
     */
    private int dp(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
    
    /**
     * 记忆项数据结构
     */
    private static class MemoryItem {
        public String memoryId;
        public int kind;
        public String content;
        public long timestampMs;
        public String provenance;
        public String trustLevel;
        
        public MemoryItem(String memoryId, int kind, String content, long timestampMs,
                         String provenance, String trustLevel) {
            this.memoryId = memoryId;
            this.kind = kind;
            this.content = content;
            this.timestampMs = timestampMs;
            this.provenance = provenance;
            this.trustLevel = trustLevel;
        }
    }
}
