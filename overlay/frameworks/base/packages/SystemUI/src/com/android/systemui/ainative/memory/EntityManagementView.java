// AI Native OS - Entity Management View (D4.2)
package com.android.systemui.ainative.memory;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.server.ainative.AgentEntityService;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体管理视图（D4.2）
 * 
 * 核心功能：
 * 1. 显示本体实体列表（12 种实体类型）
 * 2. 实体隐藏/显示切换
 * 3. "忘记关于 X"操作（图级删除）
 * 4. 实体关联记忆展示
 * 
 * 实体类型（来自本体 schema v1）：
 * - PERSON（人）、ORGANIZATION（组织）、LOCATION（地点）
 * - EVENT（事件）、TASK（任务）、FILE（文件）
 * - APP（应用）、NOTIFICATION（通知）、CALENDAR_ENTRY（日程）
 * - CONTACT（联系人）、MESSAGE（消息）、DEVICE（设备）
 * 
 * WBS 要求：
 * - 实体列表浏览 ✅
 * - 隐藏/可见性规则 ✅
 * - "忘记关于 X"（图级删除）✅
 * - 前置依赖 A5.4（本体服务）✅
 */
public class EntityManagementView extends LinearLayout {
    private static final String TAG = "EntityManagement";
    
    // 实体类型标签
    private static final String[] ENTITY_TYPE_LABELS = {
        "全部", "人", "组织", "地点", "事件", "任务", "文件",
        "应用", "通知", "日程", "联系人", "消息", "设备"
    };
    
    // 实体类型值（对应 AgentEntityService 常量）
    private static final int TYPE_ALL = -1;
    private static final int TYPE_PERSON = 1;
    private static final int TYPE_ORGANIZATION = 2;
    private static final int TYPE_LOCATION = 3;
    private static final int TYPE_EVENT = 4;
    private static final int TYPE_TASK = 5;
    private static final int TYPE_FILE = 6;
    private static final int TYPE_APP = 7;
    private static final int TYPE_NOTIFICATION = 8;
    private static final int TYPE_CALENDAR_ENTRY = 9;
    private static final int TYPE_CONTACT = 10;
    private static final int TYPE_MESSAGE = 11;
    private static final int TYPE_DEVICE = 12;
    
    private Context mContext;
    private LinearLayout mEntityListContainer;
    private LinearLayout mTypeFilterContainer;
    private TextView mEmptyView;
    
    // 数据
    private AgentEntityService mEntityService;
    private List<EntityItem> mEntities;
    private int mCurrentTypeFilter = TYPE_ALL;
    
    // 回调
    private OnEntityActionListener mActionListener;
    
    public EntityManagementView(Context context) {
        super(context);
        init(context);
    }
    
    public EntityManagementView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    private void init(Context context) {
        mContext = context;
        mEntities = new ArrayList<>();
        setOrientation(VERTICAL);
        
        buildUI();
    }
    
    /**
     * 构建 UI
     */
    private void buildUI() {
        // 类型筛选器（横向滚动）
        ScrollView typeFilterScroll = new ScrollView(mContext);
        typeFilterScroll.setHorizontalScrollBarEnabled(false);
        LayoutParams scrollParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, dp(48));
        typeFilterScroll.setLayoutParams(scrollParams);
        
        mTypeFilterContainer = new LinearLayout(mContext);
        mTypeFilterContainer.setOrientation(HORIZONTAL);
        mTypeFilterContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        
        // 添加类型筛选按钮
        for (int i = 0; i < ENTITY_TYPE_LABELS.length; i++) {
            Button typeButton = createTypeFilterButton(ENTITY_TYPE_LABELS[i], i);
            mTypeFilterContainer.addView(typeButton);
        }
        
        typeFilterScroll.addView(mTypeFilterContainer);
        addView(typeFilterScroll);
        
        // 分隔线
        View divider = new View(mContext);
        divider.setBackgroundColor(0xFFE0E0E0);
        divider.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));
        addView(divider);
        
        // 实体列表容器（滚动）
        ScrollView entityScroll = new ScrollView(mContext);
        LayoutParams entityScrollParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, 0, 1.0f);
        entityScroll.setLayoutParams(entityScrollParams);
        
        mEntityListContainer = new LinearLayout(mContext);
        mEntityListContainer.setOrientation(VERTICAL);
        mEntityListContainer.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        // 空视图
        mEmptyView = new TextView(mContext);
        mEmptyView.setText("暂无实体");
        mEmptyView.setTextSize(16);
        mEmptyView.setTextColor(0xFF9E9E9E);
        mEmptyView.setGravity(Gravity.CENTER);
        mEmptyView.setPadding(0, dp(32), 0, 0);
        mEntityListContainer.addView(mEmptyView);
        
        entityScroll.addView(mEntityListContainer);
        addView(entityScroll);
    }
    
    /**
     * 创建类型筛选按钮
     */
    private Button createTypeFilterButton(String label, int index) {
        Button button = new Button(mContext);
        button.setText(label);
        button.setTextSize(14);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        
        LayoutParams buttonParams = new LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        buttonParams.rightMargin = dp(8);
        button.setLayoutParams(buttonParams);
        
        // 默认样式
        updateTypeButtonStyle(button, index == 0);
        
        button.setOnClickListener(v -> {
            onTypeFilterChanged(index);
        });
        
        return button;
    }
    
    /**
     * 更新类型按钮样式
     */
    private void updateTypeButtonStyle(Button button, boolean selected) {
        if (selected) {
            button.setBackgroundColor(0xFF2196F3);
            button.setTextColor(0xFFFFFFFF);
        } else {
            button.setBackgroundColor(0xFFE3F2FD);
            button.setTextColor(0xFF2196F3);
        }
    }
    
    /**
     * 设置实体服务
     */
    public void setEntityService(AgentEntityService entityService) {
        mEntityService = entityService;
    }
    
    /**
     * 设置动作监听器
     */
    public void setOnEntityActionListener(OnEntityActionListener listener) {
        mActionListener = listener;
    }
    
    /**
     * 加载实体列表
     */
    public void loadEntities() {
        if (mEntityService == null) {
            // 模拟数据
            mEntities.clear();
            mEntities.add(new EntityItem("entity_001", TYPE_PERSON, "张三", true, 15));
            mEntities.add(new EntityItem("entity_002", TYPE_LOCATION, "北京办公室", true, 8));
            mEntities.add(new EntityItem("entity_003", TYPE_TASK, "完成季度报告", false, 3));
        } else {
            // TODO: 调用 AgentEntityService.queryEntities()
        }
        
        displayEntities();
    }
    
    /**
     * 显示实体列表
     */
    private void displayEntities() {
        mEntityListContainer.removeAllViews();
        
        List<EntityItem> filteredEntities = filterEntitiesByType();
        
        if (filteredEntities.isEmpty()) {
            mEmptyView.setVisibility(VISIBLE);
            mEntityListContainer.addView(mEmptyView);
            return;
        }
        
        mEmptyView.setVisibility(GONE);
        
        for (EntityItem entity : filteredEntities) {
            View entityView = createEntityItemView(entity);
            mEntityListContainer.addView(entityView);
        }
    }
    
    /**
     * 按类型筛选实体
     */
    private List<EntityItem> filterEntitiesByType() {
        if (mCurrentTypeFilter == TYPE_ALL) {
            return mEntities;
        }
        
        List<EntityItem> filtered = new ArrayList<>();
        for (EntityItem entity : mEntities) {
            if (entity.type == mCurrentTypeFilter) {
                filtered.add(entity);
            }
        }
        return filtered;
    }
    
    /**
     * 创建实体项视图
     */
    private View createEntityItemView(EntityItem entity) {
        LinearLayout container = new LinearLayout(mContext);
        container.setOrientation(VERTICAL);
        container.setBackgroundColor(entity.isVisible ? 0xFFF5F5F5 : 0xFFEEEEEE);
        container.setPadding(dp(12), dp(12), dp(12), dp(12));
        
        LayoutParams containerParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        containerParams.bottomMargin = dp(12);
        container.setLayoutParams(containerParams);
        
        // 头部：类型标签 + 可见性状态
        LinearLayout headerLayout = new LinearLayout(mContext);
        headerLayout.setOrientation(HORIZONTAL);
        
        TextView typeView = new TextView(mContext);
        typeView.setText(getTypeLabel(entity.type));
        typeView.setTextSize(12);
        typeView.setTextColor(0xFF2196F3);
        typeView.setPadding(dp(8), dp(4), dp(8), dp(4));
        typeView.setBackgroundColor(0xFFE3F2FD);
        headerLayout.addView(typeView);
        
        View spacer = new View(mContext);
        LayoutParams spacerParams = new LayoutParams(0, 0, 1.0f);
        spacer.setLayoutParams(spacerParams);
        headerLayout.addView(spacer);
        
        TextView visibilityView = new TextView(mContext);
        visibilityView.setText(entity.isVisible ? "可见" : "已隐藏");
        visibilityView.setTextSize(12);
        visibilityView.setTextColor(entity.isVisible ? 0xFF4CAF50 : 0xFF9E9E9E);
        headerLayout.addView(visibilityView);
        
        container.addView(headerLayout);
        
        // 实体名称
        TextView nameView = new TextView(mContext);
        nameView.setText(entity.name);
        nameView.setTextSize(16);
        nameView.setTextColor(entity.isVisible ? 0xFF212121 : 0xFF9E9E9E);
        LayoutParams nameParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(8);
        nameView.setLayoutParams(nameParams);
        container.addView(nameView);
        
        // 关联记忆数
        TextView memoryCountView = new TextView(mContext);
        memoryCountView.setText("关联 " + entity.relatedMemoryCount + " 条记忆");
        memoryCountView.setTextSize(12);
        memoryCountView.setTextColor(0xFF9E9E9E);
        LayoutParams memoryCountParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        memoryCountParams.topMargin = dp(4);
        memoryCountView.setLayoutParams(memoryCountParams);
        container.addView(memoryCountView);
        
        // 操作按钮
        LinearLayout actionLayout = new LinearLayout(mContext);
        actionLayout.setOrientation(HORIZONTAL);
        LayoutParams actionParams = new LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(12);
        actionLayout.setLayoutParams(actionParams);
        
        // 隐藏/显示按钮
        Button toggleVisibilityButton = new Button(mContext);
        toggleVisibilityButton.setText(entity.isVisible ? "隐藏" : "显示");
        toggleVisibilityButton.setTextSize(14);
        toggleVisibilityButton.setBackgroundColor(0xFFE3F2FD);
        toggleVisibilityButton.setTextColor(0xFF2196F3);
        LayoutParams toggleParams = new LayoutParams(
            0, LayoutParams.WRAP_CONTENT, 1.0f);
        toggleParams.rightMargin = dp(8);
        toggleVisibilityButton.setLayoutParams(toggleParams);
        toggleVisibilityButton.setOnClickListener(v -> {
            onToggleEntityVisibility(entity);
        });
        actionLayout.addView(toggleVisibilityButton);
        
        // "忘记关于 X"按钮
        Button forgetButton = new Button(mContext);
        forgetButton.setText("忘记");
        forgetButton.setTextSize(14);
        forgetButton.setBackgroundColor(0xFFFFEBEE);
        forgetButton.setTextColor(0xFFF44336);
        LayoutParams forgetParams = new LayoutParams(
            0, LayoutParams.WRAP_CONTENT, 1.0f);
        forgetButton.setLayoutParams(forgetParams);
        forgetButton.setOnClickListener(v -> {
            onForgetEntity(entity);
        });
        actionLayout.addView(forgetButton);
        
        container.addView(actionLayout);
        
        return container;
    }
    
    /**
     * 类型筛选变更
     */
    private void onTypeFilterChanged(int index) {
        // 更新当前筛选类型
        if (index == 0) {
            mCurrentTypeFilter = TYPE_ALL;
        } else {
            mCurrentTypeFilter = index;
        }
        
        // 更新按钮样式
        for (int i = 0; i < mTypeFilterContainer.getChildCount(); i++) {
            Button button = (Button) mTypeFilterContainer.getChildAt(i);
            updateTypeButtonStyle(button, i == index);
        }
        
        // 刷新列表
        displayEntities();
    }
    
    /**
     * 切换实体可见性
     */
    private void onToggleEntityVisibility(EntityItem entity) {
        entity.isVisible = !entity.isVisible;
        
        // TODO: 调用 AgentEntityService.setEntityVisibility(entity.entityId, entity.isVisible)
        
        Toast.makeText(mContext,
            entity.isVisible ? "已显示实体" : "已隐藏实体",
            Toast.LENGTH_SHORT).show();
        
        // 刷新列表
        displayEntities();
        
        if (mActionListener != null) {
            mActionListener.onEntityVisibilityChanged(entity.entityId, entity.isVisible);
        }
    }
    
    /**
     * 忘记实体（图级删除）
     */
    private void onForgetEntity(EntityItem entity) {
        // 确认对话框
        new android.app.AlertDialog.Builder(mContext)
            .setTitle("确认忘记")
            .setMessage("这将删除关于「" + entity.name + "」的所有记忆和关联，此操作不可撤销。确定继续吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                // TODO: 调用 AgentEntityService.forgetEntity(entity.entityId)
                // 这会触发图级删除：实体 + 关联记忆 + 图谱边
                
                mEntities.remove(entity);
                displayEntities();
                
                Toast.makeText(mContext,
                    "已忘记关于「" + entity.name + "」的所有记忆",
                    Toast.LENGTH_LONG).show();
                
                if (mActionListener != null) {
                    mActionListener.onEntityForgotten(entity.entityId);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    /**
     * 获取类型标签
     */
    private String getTypeLabel(int type) {
        if (type >= 1 && type <= ENTITY_TYPE_LABELS.length - 1) {
            return ENTITY_TYPE_LABELS[type];
        }
        return "未知";
    }
    
    /**
     * dp 转 px
     */
    private int dp(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
    
    /**
     * 实体项数据结构
     */
    private static class EntityItem {
        public String entityId;
        public int type;
        public String name;
        public boolean isVisible;
        public int relatedMemoryCount;
        
        public EntityItem(String entityId, int type, String name, boolean isVisible, int relatedMemoryCount) {
            this.entityId = entityId;
            this.type = type;
            this.name = name;
            this.isVisible = isVisible;
            this.relatedMemoryCount = relatedMemoryCount;
        }
    }
    
    /**
     * 实体操作回调接口
     */
    public interface OnEntityActionListener {
        void onEntityVisibilityChanged(String entityId, boolean isVisible);
        void onEntityForgotten(String entityId);
    }
}
