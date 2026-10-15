package com.genton.brain.ui.form

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.genton.brain.schema.PrefillSource
import com.genton.brain.schema.PrefillValue

/**
 * D5.3 表单预填展示 - 预填说明视图
 * 
 * 职责：
 * 1. 显示预填数据的总体说明
 * 2. 分组展示不同来源的预填值
 * 3. 提供详细的预填信息（字段、来源、置信度）
 * 4. 支持展开/折叠详情
 * 
 * 布局结构：
 * - 标题（"已为您自动填写以下内容"）
 * - 来源分组列表
 *   - 来源图标 + 来源标签
 *   - 字段列表
 * - 提示文字（"您可以修改任何字段"）
 */
class PrefillExplanationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "PrefillExplanationView"
    }
    
    // 预填高亮器（复用颜色和图标）
    private val highlighter = PrefillHighlighter(context)
    
    // 子视图
    private lateinit var titleText: TextView
    private lateinit var sourceGroupsContainer: LinearLayout
    private lateinit var hintText: TextView
    
    init {
        orientation = VERTICAL
        setupViews()
    }
    
    /**
     * 初始化视图
     */
    private fun setupViews() {
        val padding = (12 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        
        // 设置背景（浅色背景）
        setBackgroundColor(0xFFF9F9F9.toInt())
        
        // 1. 标题
        titleText = TextView(context).apply {
            text = "✨ 已为您自动填写以下内容"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        addView(titleText)
        
        // 2. 来源分组容器
        sourceGroupsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        addView(sourceGroupsContainer)
        
        // 3. 提示文字
        hintText = TextView(context).apply {
            text = "💡 您可以修改任何字段"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        addView(hintText)
    }
    
    /**
     * 设置预填值列表
     */
    fun setPrefillValues(prefillValues: List<PrefillValue>) {
        // 清空容器
        sourceGroupsContainer.removeAllViews()
        
        if (prefillValues.isEmpty()) {
            visibility = View.GONE
            return
        }
        
        visibility = View.VISIBLE
        
        // 按来源分组
        val groupedBySource = prefillValues.groupBy { it.source }
        
        // 按优先级排序来源（USER_TYPED > USER_MEMORY > ENTITY > APP > WEB > DEFAULT）
        val sortedSources = groupedBySource.keys.sortedByDescending { source ->
            PrefillSource.getDefaultPriority(source)
        }
        
        // 为每个来源创建分组视图
        sortedSources.forEach { source ->
            val values = groupedBySource[source] ?: emptyList()
            val sourceGroupView = createSourceGroupView(source, values)
            sourceGroupsContainer.addView(sourceGroupView)
        }
    }
    
    /**
     * 创建来源分组视图
     */
    private fun createSourceGroupView(source: String, values: List<PrefillValue>): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (4 * resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
            
            val padding = (8 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            
            // 设置背景（白色）
            setBackgroundColor(0xFFFFFFFF.toInt())
            
            // 设置圆角
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.setColor(0xFFFFFFFF.toInt())
            drawable.cornerRadius = 8f * resources.displayMetrics.density
            background = drawable
        }
        
        // 1. 来源标题行
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * resources.displayMetrics.density).toInt()
            }
        }
        
        // 来源图标
        val iconText = TextView(context).apply {
            text = highlighter.getSourceIcon(source)
            textSize = 16f
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (4 * resources.displayMetrics.density).toInt()
            }
        }
        headerRow.addView(iconText)
        
        // 来源标签
        val sourceLabel = TextView(context).apply {
            text = getSourceLabel(source)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF555555.toInt())
        }
        headerRow.addView(sourceLabel)
        
        // 数量标签
        val countLabel = TextView(context).apply {
            text = " (${values.size})"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
        }
        headerRow.addView(countLabel)
        
        container.addView(headerRow)
        
        // 2. 字段列表
        values.forEach { prefillValue ->
            val fieldRow = createFieldRow(prefillValue)
            container.addView(fieldRow)
        }
        
        return container
    }
    
    /**
     * 创建字段行
     */
    private fun createFieldRow(prefillValue: PrefillValue): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (2 * resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
            
            val padding = (4 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        
        // 字段名
        val fieldNameText = TextView(context).apply {
            text = "${prefillValue.fieldName}: "
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(fieldNameText)
        
        // 字段值
        val fieldValueText = TextView(context).apply {
            text = prefillValue.value.toString()
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            layoutParams = LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(fieldValueText)
        
        // 置信度（如果<1.0）
        if (prefillValue.confidence < 1.0f) {
            val confidenceText = TextView(context).apply {
                val percent = (prefillValue.confidence * 100).toInt()
                text = " $percent%"
                textSize = 11f
                setTextColor(0xFF999999.toInt())
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(confidenceText)
        }
        
        return row
    }
    
    /**
     * 获取来源标签（中文）
     */
    private fun getSourceLabel(source: String): String {
        return when (source) {
            PrefillSource.USER_TYPED -> "您的输入"
            PrefillSource.USER_MEMORY -> "您的偏好"
            PrefillSource.ENTITY -> "对话内容"
            PrefillSource.APP -> "应用状态"
            PrefillSource.WEB -> "网络查询"
            PrefillSource.DEFAULT -> "默认值"
            else -> "自动填充"
        }
    }
    
    /**
     * 设置标题文本
     */
    fun setTitle(title: String) {
        titleText.text = title
    }
    
    /**
     * 设置提示文本
     */
    fun setHint(hint: String) {
        hintText.text = hint
    }
    
    /**
     * 显示/隐藏提示
     */
    fun setHintVisible(visible: Boolean) {
        hintText.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

/**
 * 预填说明卡片（可折叠版本）
 */
class CollapsiblePrefillExplanationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    
    private lateinit var headerRow: LinearLayout
    private lateinit var toggleButton: TextView
    private lateinit var summaryText: TextView
    private lateinit var detailsView: PrefillExplanationView
    
    private var isExpanded = false
    
    init {
        orientation = VERTICAL
        setupViews()
    }
    
    /**
     * 初始化视图
     */
    private fun setupViews() {
        // 1. 标题行（可点击展开/折叠）
        headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            
            isClickable = true
            isFocusable = true
            setBackgroundColor(0xFFF0F0F0.toInt())
            
            setOnClickListener {
                toggle()
            }
        }
        
        // 折叠/展开图标
        toggleButton = TextView(context).apply {
            text = "▶"
            textSize = 12f
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        headerRow.addView(toggleButton)
        
        // 摘要文本
        summaryText = TextView(context).apply {
            text = "已为您自动填写部分内容"
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            layoutParams = LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerRow.addView(summaryText)
        
        addView(headerRow)
        
        // 2. 详情视图（默认隐藏）
        detailsView = PrefillExplanationView(context).apply {
            visibility = View.GONE
        }
        addView(detailsView)
    }
    
    /**
     * 设置预填值
     */
    fun setPrefillValues(prefillValues: List<PrefillValue>) {
        detailsView.setPrefillValues(prefillValues)
        
        // 更新摘要文本
        if (prefillValues.isEmpty()) {
            visibility = View.GONE
        } else {
            visibility = View.VISIBLE
            val count = prefillValues.size
            summaryText.text = "✨ 已为您自动填写 $count 个字段（点击查看详情）"
        }
    }
    
    /**
     * 切换展开/折叠
     */
    fun toggle() {
        isExpanded = !isExpanded
        
        if (isExpanded) {
            detailsView.visibility = View.VISIBLE
            toggleButton.text = "▼"
        } else {
            detailsView.visibility = View.GONE
            toggleButton.text = "▶"
        }
    }
    
    /**
     * 展开
     */
    fun expand() {
        if (!isExpanded) {
            toggle()
        }
    }
    
    /**
     * 折叠
     */
    fun collapse() {
        if (isExpanded) {
            toggle()
        }
    }
}
