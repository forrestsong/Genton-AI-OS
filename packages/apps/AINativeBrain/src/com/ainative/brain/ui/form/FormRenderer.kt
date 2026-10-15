package com.ainative.brain.ui.form

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.ainative.brain.schema.*

/**
 * D5.1 L0.5 内联表单渲染 - 表单渲染器
 * 
 * 职责：
 * 1. 将 FormSchema 渲染为 Android View 层次结构
 * 2. 应用预填数据
 * 3. 绑定数据与视图的双向关联
 * 4. 处理表单交互事件
 * 
 * 设计要点：
 * - 基于 C6.1 schema 体系
 * - 支持动态渲染（schema → view）
 * - 数据驱动（预填、更新、校验）
 * - 轻量级实现（L0.5 内联卡片场景）
 */
class FormRenderer(private val context: Context) {
    
    companion object {
        private const val TAG = "FormRenderer"
    }
    
    // 控件渲染器
    private val controlRenderer = ControlRenderer(context)
    
    // Schema 校验器
    private val validator = SchemaValidator()
    
    /**
     * 渲染表单实例
     * 
     * @param formInstance 表单实例（包含 schema 和预填数据）
     * @param container 容器视图（可选，如果为 null 则创建新容器）
     * @param config 渲染配置（可选）
     * @return 渲染后的表单视图
     */
    fun render(
        formInstance: FormInstance, 
        container: ViewGroup? = null,
        config: FormRenderConfig = FormRenderConfig.cardInline()
    ): FormView {
        Log.i(TAG, "Rendering form: ${formInstance.formId}")
        
        // 1. 校验 schema
        val schemaValidation = validator.validateSchema(formInstance.schema)
        if (!schemaValidation.isValid) {
            Log.e(TAG, "Invalid schema: ${schemaValidation.errors.joinToString(", ")}")
            throw IllegalArgumentException("Invalid form schema")
        }
        
        // 2. 创建或使用容器
        val formContainer = container ?: createDefaultContainer()
        
        // 3. 清空容器
        formContainer.removeAllViews()
        
        // 4. 渲染表单字段
        val fieldViews = mutableMapOf<String, View>()
        
        // D5.3 创建预填值映射
        val prefillMap = formInstance.prefillValues.associateBy { it.fieldName }
        
        formInstance.schema.properties.forEach { (fieldName, field) ->
            // 获取当前值
            val value = formInstance.getFieldValue(fieldName)
            
            // 检查是否必填
            val isRequired = formInstance.schema.isRequired(fieldName)
            
            // 映射控件类型
            val controlType = ControlMapper.mapFieldToControl(field)
            
            // 创建渲染参数
            val renderParams = ControlRenderParams(
                controlType = controlType,
                field = field,
                value = value,
                isRequired = isRequired,
                disabled = field.xAinative?.disabled ?: false,
                readonly = field.xAinative?.readonly ?: false
            )
            
            // D5.3 获取预填信息
            val prefillValue = prefillMap[fieldName]
            
            // 渲染控件（带预填高亮）
            val fieldView = if (prefillValue != null && config.showPrefillHighlight) {
                controlRenderer.renderWithPrefill(renderParams, prefillValue)
            } else {
                controlRenderer.render(renderParams)
            }
            
            // 绑定数据更新回调
            controlRenderer.setValueChangeListener(fieldView) { newValue ->
                formInstance.updateField(fieldName, newValue)
                
                // D5.3 用户修改后清除预填高亮
                if (prefillValue != null && config.clearHighlightOnEdit) {
                    controlRenderer.clearPrefillHighlight(fieldView)
                }
                
                Log.d(TAG, "Field $fieldName updated: $newValue")
            }
            
            fieldViews[fieldName] = fieldView
            formContainer.addView(fieldView)
        }
        
        // 5. 应用布局
        applyLayout(formContainer, formInstance.schema.xAinative.layout)
        
        // 6. 创建表单视图包装
        val formView = FormView(
            context = context,
            formInstance = formInstance,
            container = formContainer,
            fieldViews = fieldViews,
            renderer = this
        )
        
        Log.i(TAG, "Form rendered: ${formInstance.formId}, ${fieldViews.size} fields")
        
        return formView
    }
    
    /**
     * 创建默认容器
     */
    private fun createDefaultContainer(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            // 设置内边距
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
    }
    
    /**
     * 应用布局样式
     */
    private fun applyLayout(container: ViewGroup, layout: String) {
        when (layout) {
            "vertical" -> {
                if (container is LinearLayout) {
                    container.orientation = LinearLayout.VERTICAL
                }
            }
            
            "horizontal" -> {
                if (container is LinearLayout) {
                    container.orientation = LinearLayout.HORIZONTAL
                }
            }
            
            "grid" -> {
                // TODO: 使用 GridLayout 或 RecyclerView 实现网格布局
                Log.w(TAG, "Grid layout not implemented yet, using vertical")
                if (container is LinearLayout) {
                    container.orientation = LinearLayout.VERTICAL
                }
            }
        }
    }
    
    /**
     * 更新表单数据（重新渲染）
     */
    fun updateFormData(formView: FormView, updates: Map<String, Any?>) {
        Log.d(TAG, "Updating form data: ${updates.keys.joinToString(", ")}")
        
        formView.formInstance.updateFields(updates)
        
        // 逐个更新字段视图
        updates.forEach { (fieldName, value) ->
            val fieldView = formView.fieldViews[fieldName]
            if (fieldView != null) {
                controlRenderer.updateValue(fieldView, value)
            }
        }
    }
    
    /**
     * 显示校验错误
     */
    fun showValidationErrors(formView: FormView, validationResult: FormValidationResult) {
        Log.d(TAG, "Showing validation errors: ${validationResult.errors.size} errors")
        
        // 清除所有错误
        formView.fieldViews.forEach { (_, view) ->
            controlRenderer.clearError(view)
        }
        
        // 显示新错误
        validationResult.errors.forEach { error ->
            val fieldView = formView.fieldViews[error.fieldName]
            if (fieldView != null) {
                controlRenderer.showError(fieldView, error.message)
            }
        }
    }
    
    /**
     * 清除校验错误
     */
    fun clearValidationErrors(formView: FormView) {
        formView.fieldViews.forEach { (_, view) ->
            controlRenderer.clearError(view)
        }
    }
    
    /**
     * 聚焦到第一个错误字段
     */
    fun focusFirstError(formView: FormView, validationResult: FormValidationResult) {
        if (validationResult.errors.isEmpty()) {
            return
        }
        
        val firstError = validationResult.errors.first()
        val fieldView = formView.fieldViews[firstError.fieldName]
        fieldView?.requestFocus()
    }
    
    /**
     * 启用/禁用表单
     */
    fun setFormEnabled(formView: FormView, enabled: Boolean) {
        formView.fieldViews.forEach { (_, view) ->
            view.isEnabled = enabled
        }
    }
}

/**
 * 表单视图包装类
 * 
 * 包含表单实例、容器视图、字段视图映射
 */
data class FormView(
    val context: Context,
    val formInstance: FormInstance,
    val container: ViewGroup,
    val fieldViews: Map<String, View>,
    val renderer: FormRenderer
) {
    /**
     * 获取根视图
     */
    fun getRootView(): View {
        return container
    }
    
    /**
     * 获取字段视图
     */
    fun getFieldView(fieldName: String): View? {
        return fieldViews[fieldName]
    }
    
    /**
     * 校验表单
     */
    fun validate(): FormValidationResult {
        val result = formInstance.validate()
        
        // 显示错误
        if (!result.isValid) {
            renderer.showValidationErrors(this, result)
        }
        
        return result
    }
    
    /**
     * 清除错误
     */
    fun clearErrors() {
        renderer.clearValidationErrors(this)
    }
    
    /**
     * 获取表单数据
     */
    fun getData(): Map<String, Any?> {
        return formInstance.data
    }
    
    /**
     * 获取提交数据（仅包含有值的字段）
     */
    fun getSubmitData(): Map<String, Any> {
        return formInstance.getSubmitData()
    }
    
    /**
     * 更新字段值
     */
    fun updateField(fieldName: String, value: Any?) {
        renderer.updateFormData(this, mapOf(fieldName to value))
    }
    
    /**
     * 批量更新字段
     */
    fun updateFields(updates: Map<String, Any?>) {
        renderer.updateFormData(this, updates)
    }
    
    /**
     * 重置表单
     */
    fun reset() {
        formInstance.reset()
        
        // 重新渲染所有字段
        val updates = formInstance.data
        renderer.updateFormData(this, updates)
    }
    
    /**
     * 启用/禁用表单
     */
    fun setEnabled(enabled: Boolean) {
        renderer.setFormEnabled(this, enabled)
    }
    
    /**
     * 是否可以提交
     */
    fun canSubmit(): Boolean {
        return formInstance.canSubmit()
    }
    
    /**
     * 是否有修改
     */
    fun isDirty(): Boolean {
        return formInstance.isDirty
    }
}

/**
 * 表单渲染配置
 */
data class FormRenderConfig(
    val showLabels: Boolean = true,  // 是否显示标签
    val showRequired: Boolean = true,  // 是否显示必填标记
    val showHelpText: Boolean = true,  // 是否显示帮助文本
    val compactMode: Boolean = false,  // 是否紧凑模式
    val readonlyMode: Boolean = false,  // 是否只读模式
    val validationMode: String = "onSubmit"  // 校验模式
) {
    companion object {
        /**
         * 卡片内联模式（紧凑）
         */
        fun cardInline(): FormRenderConfig {
            return FormRenderConfig(
                showLabels = true,
                showRequired = true,
                showHelpText = false,
                compactMode = true,
                readonlyMode = false,
                validationMode = "onSubmit"
            )
        }
        
        /**
         * 全页面模式（完整）
         */
        fun fullPage(): FormRenderConfig {
            return FormRenderConfig(
                showLabels = true,
                showRequired = true,
                showHelpText = true,
                compactMode = false,
                readonlyMode = false,
                validationMode = "onChange"
            )
        }
        
        /**
         * 预览模式（只读）
         */
        fun preview(): FormRenderConfig {
            return FormRenderConfig(
                showLabels = true,
                showRequired = false,
                showHelpText = false,
                compactMode = true,
                readonlyMode = true,
                validationMode = "onSubmit"
            )
        }
    }
}
