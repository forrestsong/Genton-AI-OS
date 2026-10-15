package com.ainative.brain.ui.form

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.ainative.brain.schema.PrefillSource
import com.ainative.brain.schema.PrefillValue
import com.google.android.material.textfield.TextInputLayout

/**
 * D5.3 表单预填展示 - 预填值高亮器
 * 
 * 职责：
 * 1. 高亮显示预填的字段（视觉区分）
 * 2. 根据预填来源使用不同颜色
 * 3. 添加预填来源标识
 * 4. 支持预填值的清除和编辑
 * 
 * 高亮策略：
 * - USER_TYPED: 蓝色高亮（用户明确提供）
 * - USER_MEMORY: 紫色高亮（用户偏好）
 * - ENTITY: 绿色高亮（对话提取）
 * - APP: 橙色高亮（应用状态）
 * - WEB: 黄色高亮（网络查询）
 * - DEFAULT: 灰色高亮（默认值）
 */
class PrefillHighlighter(private val context: Context) {
    
    companion object {
        // 预填来源颜色映射（ARGB格式）
        private const val COLOR_USER_TYPED = 0xFFE3F2FD.toInt()      // 浅蓝色
        private const val COLOR_USER_MEMORY = 0xFFF3E5F5.toInt()     // 浅紫色
        private const val COLOR_ENTITY = 0xFFE8F5E9.toInt()          // 浅绿色
        private const val COLOR_APP = 0xFFFFF3E0.toInt()             // 浅橙色
        private const val COLOR_WEB = 0xFFFFFDE7.toInt()             // 浅黄色
        private const val COLOR_DEFAULT = 0xFFF5F5F5.toInt()         // 浅灰色
        
        // 边框颜色（深色版本）
        private const val BORDER_USER_TYPED = 0xFF2196F3.toInt()
        private const val BORDER_USER_MEMORY = 0xFF9C27B0.toInt()
        private const val BORDER_ENTITY = 0xFF4CAF50.toInt()
        private const val BORDER_APP = 0xFFFF9800.toInt()
        private const val BORDER_WEB = 0xFFFFEB3B.toInt()
        private const val BORDER_DEFAULT = 0xFF9E9E9E.toInt()
    }
    
    /**
     * 高亮预填字段
     * 
     * @param view 字段视图
     * @param prefillValue 预填值信息
     */
    fun highlight(view: View, prefillValue: PrefillValue) {
        val colors = getSourceColors(prefillValue.source)
        
        when (view) {
            is TextInputLayout -> {
                highlightTextInputLayout(view, colors, prefillValue)
            }
            
            is TextView -> {
                highlightTextView(view, colors, prefillValue)
            }
            
            else -> {
                // 通用视图高亮
                highlightGenericView(view, colors)
            }
        }
    }
    
    /**
     * 高亮 TextInputLayout
     */
    private fun highlightTextInputLayout(
        layout: TextInputLayout,
        colors: PrefillColors,
        prefillValue: PrefillValue
    ) {
        // 1. 设置背景色
        val editText = layout.editText
        if (editText != null) {
            val background = GradientDrawable().apply {
                setColor(colors.backgroundColor)
                setStroke(2.dpToPx(), colors.borderColor)
                cornerRadius = 8f.dpToPx()
            }
            editText.background = background
            
            // 添加内边距
            val padding = 8.dpToPx()
            editText.setPadding(padding, padding, padding, padding)
        }
        
        // 2. 设置帮助文本（显示来源）
        val sourceLabel = getSourceLabel(prefillValue.source)
        val helperText = if (prefillValue.reasoning != null) {
            "$sourceLabel: ${prefillValue.reasoning}"
        } else {
            "来自$sourceLabel"
        }
        layout.helperText = helperText
        
        // 3. 添加标签（可选）
        if (prefillValue.confidence < 1.0f) {
            val confidencePercent = (prefillValue.confidence * 100).toInt()
            layout.suffixText = "($confidencePercent%)"
        }
    }
    
    /**
     * 高亮 TextView
     */
    private fun highlightTextView(
        textView: TextView,
        colors: PrefillColors,
        prefillValue: PrefillValue
    ) {
        val background = GradientDrawable().apply {
            setColor(colors.backgroundColor)
            setStroke(2.dpToPx(), colors.borderColor)
            cornerRadius = 8f.dpToPx()
        }
        textView.background = background
        
        val padding = 8.dpToPx()
        textView.setPadding(padding, padding, padding, padding)
    }
    
    /**
     * 高亮通用视图
     */
    private fun highlightGenericView(view: View, colors: PrefillColors) {
        val background = GradientDrawable().apply {
            setColor(colors.backgroundColor)
            setStroke(2.dpToPx(), colors.borderColor)
            cornerRadius = 8f.dpToPx()
        }
        view.background = background
    }
    
    /**
     * 清除高亮
     */
    fun clearHighlight(view: View) {
        when (view) {
            is TextInputLayout -> {
                val editText = view.editText
                if (editText != null) {
                    editText.background = null
                    editText.setPadding(0, 0, 0, 0)
                }
                view.helperText = null
                view.suffixText = null
            }
            
            is TextView -> {
                view.background = null
                view.setPadding(0, 0, 0, 0)
            }
            
            else -> {
                view.background = null
            }
        }
    }
    
    /**
     * 获取来源颜色
     */
    private fun getSourceColors(source: String): PrefillColors {
        return when (source) {
            PrefillSource.USER_TYPED -> PrefillColors(COLOR_USER_TYPED, BORDER_USER_TYPED)
            PrefillSource.USER_MEMORY -> PrefillColors(COLOR_USER_MEMORY, BORDER_USER_MEMORY)
            PrefillSource.ENTITY -> PrefillColors(COLOR_ENTITY, BORDER_ENTITY)
            PrefillSource.APP -> PrefillColors(COLOR_APP, BORDER_APP)
            PrefillSource.WEB -> PrefillColors(COLOR_WEB, BORDER_WEB)
            PrefillSource.DEFAULT -> PrefillColors(COLOR_DEFAULT, BORDER_DEFAULT)
            else -> PrefillColors(COLOR_DEFAULT, BORDER_DEFAULT)
        }
    }
    
    /**
     * 获取来源标签
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
     * 获取来源图标（Unicode emoji）
     */
    fun getSourceIcon(source: String): String {
        return when (source) {
            PrefillSource.USER_TYPED -> "💬"
            PrefillSource.USER_MEMORY -> "⭐"
            PrefillSource.ENTITY -> "🔍"
            PrefillSource.APP -> "📱"
            PrefillSource.WEB -> "🌐"
            PrefillSource.DEFAULT -> "⚙️"
            else -> "✨"
        }
    }
    
    /**
     * DP 转 PX
     */
    private fun Float.dpToPx(): Float {
        return this * context.resources.displayMetrics.density
    }
    
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}

/**
 * 预填颜色配置
 */
data class PrefillColors(
    val backgroundColor: Int,
    val borderColor: Int
)

/**
 * 预填高亮信息
 */
data class PrefillHighlightInfo(
    val fieldName: String,
    val prefillValue: PrefillValue,
    val isHighlighted: Boolean = false
)

/**
 * 预填高亮配置
 */
data class PrefillHighlightConfig(
    val enabled: Boolean = true,              // 是否启用高亮
    val showSourceLabel: Boolean = true,      // 是否显示来源标签
    val showConfidence: Boolean = true,       // 是否显示置信度
    val showReasoning: Boolean = true,        // 是否显示推理说明
    val highlightDuration: Long = 3000L,      // 高亮持续时间（毫秒，0=永久）
    val animateHighlight: Boolean = false     // 是否使用动画
) {
    companion object {
        /**
         * 完整模式（显示所有信息）
         */
        fun full(): PrefillHighlightConfig {
            return PrefillHighlightConfig(
                enabled = true,
                showSourceLabel = true,
                showConfidence = true,
                showReasoning = true,
                highlightDuration = 0L,
                animateHighlight = false
            )
        }
        
        /**
         * 简洁模式（仅显示基础高亮）
         */
        fun minimal(): PrefillHighlightConfig {
            return PrefillHighlightConfig(
                enabled = true,
                showSourceLabel = false,
                showConfidence = false,
                showReasoning = false,
                highlightDuration = 3000L,
                animateHighlight = true
            )
        }
        
        /**
         * 禁用模式
         */
        fun disabled(): PrefillHighlightConfig {
            return PrefillHighlightConfig(
                enabled = false,
                showSourceLabel = false,
                showConfidence = false,
                showReasoning = false,
                highlightDuration = 0L,
                animateHighlight = false
            )
        }
    }
}
