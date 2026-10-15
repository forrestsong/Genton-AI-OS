package com.ainative.brain.ui.form

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ainative.brain.schema.FormInstance
import com.ainative.brain.schema.FormValidationResult

/**
 * D5.1 L0.5 内联表单渲染 - 卡片内嵌表单视图
 * 
 * 职责：
 * 1. 在卡片中嵌入表单（轻量级、紧凑布局）
 * 2. 显示预填说明
 * 3. 提供提交/取消按钮
 * 4. 处理表单交互和提交逻辑
 * 
 * 布局结构：
 * - 预填说明（可选）
 * - 表单字段区域
 * - 提交/取消按钮
 */
class FormCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "FormCardView"
    }
    
    // 表单渲染器
    private val formRenderer = FormRenderer(context)
    
    // 子视图
    private lateinit var explanationText: TextView
    private lateinit var formContainer: LinearLayout
    private lateinit var buttonContainer: LinearLayout
    private lateinit var submitButton: Button
    private lateinit var cancelButton: Button
    
    // 表单视图
    private var formView: FormView? = null
    
    // 回调
    private var onSubmitListener: ((Map<String, Any>) -> Unit)? = null
    private var onCancelListener: (() -> Unit)? = null
    private var onValidationErrorListener: ((FormValidationResult) -> Unit)? = null
    
    init {
        orientation = VERTICAL
        setupViews()
    }
    
    /**
     * 初始化视图
     */
    private fun setupViews() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        
        // 1. 预填说明文本
        explanationText = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            visibility = View.GONE
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        addView(explanationText)
        
        // 2. 表单容器
        formContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        addView(formContainer)
        
        // 3. 按钮容器
        buttonContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * resources.displayMetrics.density).toInt()
            }
        }
        
        // 取消按钮
        cancelButton = Button(context).apply {
            text = "取消"
            setOnClickListener {
                handleCancel()
            }
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        buttonContainer.addView(cancelButton)
        
        // 提交按钮
        submitButton = Button(context).apply {
            text = "提交"
            setOnClickListener {
                handleSubmit()
            }
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        buttonContainer.addView(submitButton)
        
        addView(buttonContainer)
    }
    
    /**
     * 设置表单实例
     */
    fun setFormInstance(formInstance: FormInstance) {
        Log.i(TAG, "Setting form instance: ${formInstance.formId}")
        
        // 1. D5.3 显示预填说明视图
        explanationView.setPrefillValues(formInstance.prefillValues)
        
        // 2. 渲染表单（带预填高亮）
        formView = formRenderer.render(formInstance, formContainer)
        
        // 3. 更新按钮文本
        val metadata = formInstance.schema.xAinative
        submitButton.text = metadata.submitLabel
        cancelButton.text = metadata.cancelLabel
        
        // 4. 控制取消按钮可见性
        cancelButton.visibility = if (metadata.cancelable) View.VISIBLE else View.GONE
        
        Log.i(TAG, "Form instance set successfully")
    }
    
    /**
     * 处理提交
     */
    private fun handleSubmit() {
        val currentFormView = formView
        if (currentFormView == null) {
            Log.w(TAG, "No form instance set")
            return
        }
        
        val currentCardId = cardId
        val currentAgentId = agentId
        val currentTurnId = turnId
        
        Log.d(TAG, "Submitting form: ${currentFormView.formInstance.formId}")
        
        // D5.2 使用提交处理器和状态机
        if (submitHandler != null && stateMachine != null && 
            currentCardId != null && currentAgentId != null && currentTurnId != null) {
            
            // 检查是否可以提交
            if (!stateMachine!!.canSubmit(currentCardId)) {
                Log.w(TAG, "Cannot submit in current state")
                return
            }
            
            // 状态转换：EDITING → VALIDATING
            stateMachine!!.transition(currentCardId, FormCardStateMachine.STATE_VALIDATING, "User submit")
            
            // 提交表单
            submitHandler!!.submit(
                cardId = currentCardId,
                formInstance = currentFormView.formInstance,
                agentId = currentAgentId,
                turnId = currentTurnId
            )
            
        } else {
            // 降级：使用原有的简单提交逻辑
            legacySubmit(currentFormView)
        }
    }
    
    /**
     * 降级提交逻辑（不使用状态机）
     */
    private fun legacySubmit(currentFormView: FormView) {
        // 1. 校验表单
        val validationResult = currentFormView.validate()
        
        if (!validationResult.isValid) {
            Log.w(TAG, "Form validation failed: ${validationResult.errors.size} errors")
            
            // 触发校验错误回调
            onValidationErrorListener?.invoke(validationResult)
            
            // 聚焦到第一个错误字段
            formRenderer.focusFirstError(currentFormView, validationResult)
            
            return
        }
        
        // 2. 获取提交数据
        val submitData = currentFormView.getSubmitData()
        
        Log.i(TAG, "Form submitted successfully: ${submitData.keys.joinToString(", ")}")
        
        // 3. 禁用表单（防止重复提交）
        setFormEnabled(false)
        
        // 4. 触发提交回调
        onSubmitListener?.invoke(submitData)
    }
    
    /**
     * D5.2 实现状态监听器接口
     */
    override fun onStateChanged(cardId: String, newState: Int, oldState: Int?) {
        if (cardId != this.cardId) return
        
        Log.d(TAG, "Form card state changed: $cardId, state=$newState")
        
        // 根据状态更新UI
        when (newState) {
            FormCardStateMachine.STATE_EDITING -> {
                setFormEnabled(true)
                submitButton.text = formView?.formInstance?.schema?.xAinative?.submitLabel ?: "提交"
            }
            
            FormCardStateMachine.STATE_VALIDATING -> {
                setFormEnabled(false)
                submitButton.text = "校验中..."
            }
            
            FormCardStateMachine.STATE_SUBMITTING -> {
                setFormEnabled(false)
                submitButton.text = "提交中..."
            }
            
            FormCardStateMachine.STATE_SUBMITTED -> {
                setFormEnabled(false)
                submitButton.text = "已提交"
                // 可选：显示成功提示
            }
            
            FormCardStateMachine.STATE_ERROR -> {
                setFormEnabled(true)
                submitButton.text = "重试"
            }
            
            FormCardStateMachine.STATE_CANCELLED -> {
                setFormEnabled(false)
            }
        }
    }
    
    /**
     * 处理取消
     */
    private fun handleCancel() {
        val currentFormView = formView
        if (currentFormView == null) {
            Log.w(TAG, "No form instance set")
            return
        }
        
        Log.d(TAG, "Canceling form: ${currentFormView.formInstance.formId}")
        
        // 检查是否需要确认
        val metadata = currentFormView.formInstance.schema.xAinative
        if (metadata.confirmOnCancel && currentFormView.isDirty()) {
            // TODO: 显示确认对话框
            Log.d(TAG, "Form has changes, should confirm cancel")
        }
        
        // 触发取消回调
        onCancelListener?.invoke()
    }
    
    /**
     * 启用/禁用表单
     */
    fun setFormEnabled(enabled: Boolean) {
        formView?.setEnabled(enabled)
        submitButton.isEnabled = enabled
        cancelButton.isEnabled = enabled
    }
    
    /**
     * 显示加载状态
     */
    fun setLoading(loading: Boolean) {
        if (loading) {
            submitButton.text = "提交中..."
            setFormEnabled(false)
        } else {
            val metadata = formView?.formInstance?.schema?.xAinative
            submitButton.text = metadata?.submitLabel ?: "提交"
            setFormEnabled(true)
        }
    }
    
    /**
     * 设置提交监听器
     */
    fun setOnSubmitListener(listener: (Map<String, Any>) -> Unit) {
        onSubmitListener = listener
    }
    
    /**
     * 设置取消监听器
     */
    fun setOnCancelListener(listener: () -> Unit) {
        onCancelListener = listener
    }
    
    /**
     * 设置校验错误监听器
     */
    fun setOnValidationErrorListener(listener: (FormValidationResult) -> Unit) {
        onValidationErrorListener = listener
    }
    
    /**
     * 获取表单视图
     */
    fun getFormView(): FormView? {
        return formView
    }
    
    /**
     * 重置表单
     */
    fun reset() {
        formView?.reset()
        setFormEnabled(true)
    }
    
    /**
     * 清除错误
     */
    fun clearErrors() {
        formView?.clearErrors()
    }
    
    /**
     * 更新字段值
     */
    fun updateField(fieldName: String, value: Any?) {
        formView?.updateField(fieldName, value)
    }
    
    /**
     * 批量更新字段
     */
    fun updateFields(updates: Map<String, Any?>) {
        formView?.updateFields(updates)
    }
    
    /**
     * 获取表单数据
     */
    fun getData(): Map<String, Any?>? {
        return formView?.getData()
    }
    
    /**
     * 是否可以提交
     */
    fun canSubmit(): Boolean {
        return formView?.canSubmit() ?: false
    }
    
    /**
     * 是否有修改
     */
    fun isDirty(): Boolean {
        return formView?.isDirty() ?: false
    }
}

/**
 * 表单卡片构建器
 */
class FormCardBuilder(private val context: Context) {
    
    private var formInstance: FormInstance? = null
    private var onSubmit: ((Map<String, Any>) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var onValidationError: ((FormValidationResult) -> Unit)? = null
    
    /**
     * 设置表单实例
     */
    fun setFormInstance(formInstance: FormInstance): FormCardBuilder {
        this.formInstance = formInstance
        return this
    }
    
    /**
     * 设置提交回调
     */
    fun onSubmit(listener: (Map<String, Any>) -> Unit): FormCardBuilder {
        this.onSubmit = listener
        return this
    }
    
    /**
     * 设置取消回调
     */
    fun onCancel(listener: () -> Unit): FormCardBuilder {
        this.onCancel = listener
        return this
    }
    
    /**
     * 设置校验错误回调
     */
    fun onValidationError(listener: (FormValidationResult) -> Unit): FormCardBuilder {
        this.onValidationError = listener
        return this
    }
    
    /**
     * 构建表单卡片
     */
    fun build(): FormCardView {
        val formCard = FormCardView(context)
        
        // 设置表单实例
        if (formInstance != null) {
            formCard.setFormInstance(formInstance!!)
        } else {
            throw IllegalStateException("FormInstance must be set")
        }
        
        // 设置回调
        if (onSubmit != null) {
            formCard.setOnSubmitListener(onSubmit!!)
        }
        
        if (onCancel != null) {
            formCard.setOnCancelListener(onCancel!!)
        }
        
        if (onValidationError != null) {
            formCard.setOnValidationErrorListener(onValidationError!!)
        }
        
        return formCard
    }
}
