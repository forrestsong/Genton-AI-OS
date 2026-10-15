package com.genton.systemui.forms

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.isVisible
import com.genton.forms.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * D5.1 L0.5 内联表单渲染器
 * 
 * 职责：
 * 1. schema → 控件纯函数渲染（调用 C6.1 WidgetMapper）
 * 2. 内联校验（实时错误提示）
 * 3. canSubmit 状态计算（所有必填字段已填写 + 校验通过）
 * 4. secret 控件（值不出进程，FLAG_SECURE）
 * 
 * 设计原则（D15 §4/§5）：
 * - 纯函数渲染：相同 schema 永远产生相同 View 树
 * - 内联校验：validateOnChange=true 时实时校验，错误即时反馈
 * - 安全控件：secret 字段使用 PasswordEditText，禁止截图/录屏
 * - 无状态：表单数据存储在 FormState，渲染器本身不缓存
 * 
 * 依赖关系：
 * - 前置：C6.1 表单 schema 体系（FormSchema/WidgetMapper）
 * - 消费：D5.2 表单提交流（FormSubmitManager）、B3.2 FormSubmitRouter
 * 
 * 版本：v1（A2 期）
 * 
 * @since A2
 */
class FormRenderer(private val context: Context) {
    
    companion object {
        private const val TAG = "FormRenderer"
        
        // View ID 基数（避免与系统 View ID 冲突）
        private const val VIEW_ID_BASE = 0x7F100000
    }
    
    /**
     * 表单状态（持有表单数据和校验状态）
     */
    data class FormState(
        val schema: FormSchema,
        val values: MutableMap<String, String> = mutableMapOf(),
        val errors: MutableMap<String, String> = mutableMapOf(),
        val prefilledFields: MutableSet<String> = mutableSetOf(),
        var isDirty: Boolean = false
    ) {
        /**
         * 是否可提交
         */
        fun canSubmit(): Boolean {
            // 1. 所有必填字段已填写
            val requiredFilled = schema.fields
                .filter { it.required }
                .all { !values[it.name].isNullOrBlank() }
            
            // 2. 无校验错误
            val noErrors = errors.isEmpty()
            
            return requiredFilled && noErrors
        }
        
        /**
         * 设置字段值
         */
        fun setValue(fieldName: String, value: String) {
            values[fieldName] = value
            isDirty = true
        }
        
        /**
         * 获取字段值
         */
        fun getValue(fieldName: String): String? {
            return values[fieldName]
        }
        
        /**
         * 设置错误
         */
        fun setError(fieldName: String, error: String?) {
            if (error.isNullOrBlank()) {
                errors.remove(fieldName)
            } else {
                errors[fieldName] = error
            }
        }
        
        /**
         * 标记预填字段
         */
        fun markPrefilled(fieldName: String) {
            prefilledFields.add(fieldName)
        }
        
        /**
         * 清除预填字段
         */
        fun clearPrefilled(fieldName: String) {
            prefilledFields.remove(fieldName)
            values.remove(fieldName)
        }
    }
    
    /**
     * 渲染回调
     */
    interface RenderCallback {
        fun onFormStateChanged(state: FormState)
        fun onSubmitClicked(state: FormState)
        fun onCancelClicked()
    }
    
    /**
     * 核心渲染函数：schema → ViewGroup
     * 
     * @param schema 表单 schema
     * @param prefillData 预填数据（来源由 C6.3 预填规则决定）
     * @param callback 渲染回调
     * @return 渲染后的 ViewGroup
     */
    fun render(
        schema: FormSchema,
        prefillData: Map<String, String> = emptyMap(),
        callback: RenderCallback
    ): ViewGroup {
        // 1. 创建表单状态
        val state = FormState(schema)
        
        // 2. 应用预填数据
        applyPrefillData(state, prefillData)
        
        // 3. 创建根容器
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        
        // 4. 添加标题
        if (schema.meta.formTitle.isNotBlank()) {
            container.addView(createTitleView(schema.meta.formTitle))
        }
        
        // 5. 添加描述
        if (!schema.meta.formDescription.isNullOrBlank()) {
            container.addView(createDescriptionView(schema.meta.formDescription))
        }
        
        // 6. 渲染字段
        for (field in schema.fields) {
            val fieldView = renderField(field, state, callback)
            container.addView(fieldView)
        }
        
        // 7. 添加按钮组
        container.addView(createButtonGroup(schema.meta, state, callback))
        
        return container
    }
    
    /**
     * 应用预填数据
     */
    private fun applyPrefillData(state: FormState, prefillData: Map<String, String>) {
        for ((fieldName, value) in prefillData) {
            // 检查字段是否存在
            val field = state.schema.fields.find { it.name == fieldName } ?: continue
            
            // secret 字段禁止预填（C6.1 规则）
            if (field.xSecret || field.format == "password") {
                continue
            }
            
            state.setValue(fieldName, value)
            state.markPrefilled(fieldName)
        }
    }
    
    /**
     * 渲染单个字段
     */
    private fun renderField(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): View {
        val fieldContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }
        
        // 1. 字段标签
        val label = createLabelView(field)
        fieldContainer.addView(label)
        
        // 2. 控件（根据 WidgetMapper 映射）
        val widgetType = WidgetMapper.map(field)
        val widget = createWidget(widgetType, field, state, callback)
        fieldContainer.addView(widget)
        
        // 3. 错误提示（初始隐藏）
        val errorView = createErrorView(field.name)
        fieldContainer.addView(errorView)
        
        // 4. 预填标记（如果是预填字段）
        if (state.prefilledFields.contains(field.name)) {
            val prefilledHint = createPrefilledHintView(field.name, state, callback)
            fieldContainer.addView(prefilledHint)
        }
        
        return fieldContainer
    }
    
    /**
     * 创建控件
     */
    private fun createWidget(
        widgetType: WidgetType,
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): View {
        return when (widgetType) {
            WidgetType.TEXT_SINGLE_LINE -> createTextInput(field, state, callback, false)
            WidgetType.TEXT_MULTI_LINE -> createTextInput(field, state, callback, true)
            WidgetType.EMAIL -> createEmailInput(field, state, callback)
            WidgetType.PHONE -> createPhoneInput(field, state, callback)
            WidgetType.URL -> createUrlInput(field, state, callback)
            WidgetType.PASSWORD -> createPasswordInput(field, state, callback)
            WidgetType.SECRET_TEXT -> createSecretTextInput(field, state, callback)
            WidgetType.NUMBER -> createNumberInput(field, state, callback, false)
            WidgetType.SLIDER -> createSlider(field, state, callback)
            WidgetType.STEPPER -> createNumberInput(field, state, callback, true)
            WidgetType.CHECKBOX -> createCheckbox(field, state, callback)
            WidgetType.RADIO -> createRadioGroup(field, state, callback)
            WidgetType.SELECT -> createSpinner(field, state, callback)
            WidgetType.MULTI_SELECT -> createMultiSelect(field, state, callback)
            WidgetType.DATE -> createDatePicker(field, state, callback)
            WidgetType.TIME -> createTimePicker(field, state, callback)
            WidgetType.DATE_TIME -> createDateTimePicker(field, state, callback)
            else -> createTextInput(field, state, callback, false) // 兜底
        }
    }
    
    /**
     * 创建文本输入控件
     */
    private fun createTextInput(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback,
        multiline: Boolean
    ): EditText {
        return EditText(context).apply {
            id = VIEW_ID_BASE + field.name.hashCode()
            hint = field.placeholder ?: field.title
            
            // 设置输入类型
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                maxLines = 6
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
            }
            
            // 设置最大长度
            field.maxLength?.let { maxLength ->
                filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            }
            
            // 设置初始值
            setText(state.getValue(field.name) ?: "")
            
            // 监听变化
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString() ?: ""
                    state.setValue(field.name, value)
                    
                    // 内联校验
                    if (state.schema.meta.validateOnChange) {
                        validateField(field, value, state)
                        updateErrorView(field.name, state.errors[field.name])
                    }
                    
                    callback.onFormStateChanged(state)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
    }
    
    /**
     * 创建邮箱输入控件
     */
    private fun createEmailInput(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): EditText {
        return EditText(context).apply {
            id = VIEW_ID_BASE + field.name.hashCode()
            hint = field.placeholder ?: "example@domain.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            
            setText(state.getValue(field.name) ?: "")
            
            addTextChangedListener(createTextWatcher(field, state, callback))
        }
    }
    
    /**
     * 创建电话输入控件
     */
    private fun createPhoneInput(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): EditText {
        return EditText(context).apply {
            id = VIEW_ID_BASE + field.name.hashCode()
            hint = field.placeholder ?: "138****1234"
            inputType = InputType.TYPE_CLASS_PHONE
            
            setText(state.getValue(field.name) ?: "")
            
            addTextChangedListener(createTextWatcher(field, state, callback))
        }
    }
    
    /**
     * 创建 URL 输入控件
     */
    private fun createUrlInput(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): EditText {
        return EditText(context).apply {
            id = VIEW_ID_BASE + field.name.hashCode()
            hint = field.placeholder ?: "https://example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            
            setText(state.getValue(field.name) ?: "")
            
            addTextChangedListener(createTextWatcher(field, state, callback))
        }
    }
    
    /**
     * 创建密码输入控件
     */
    private fun createPasswordInput(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): EditText {
        return EditText(context).apply {
            id = VIEW_ID_BASE + field.name.hashCode()
            hint = field.placeholder ?: "••••••••"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            
            // TODO: 设置 FLAG_SECURE（需 Window 层支持）
            
            addTextChangedListener(createTextWatcher(field, state, callback))
        }
    }
    
    /**
     * 创建安全文本输入控件（secret 字段）
     */
    private fun createSecretTextInput(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): EditText {
        return createPasswordInput(field, state, callback) // 复用密码控件
    }
    
    /**
     * 创建数字输入控件
     */
    private fun createNumberInput(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback,
        isStepper: Boolean
    ): View {
        if (isStepper) {
            // Stepper 控件：EditText + +/- 按钮
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                
                val decrementBtn = Button(context).apply {
                    text = "-"
                    setOnClickListener {
                        val current = state.getValue(field.name)?.toIntOrNull() ?: 0
                        val min = field.minimum?.toInt() ?: Int.MIN_VALUE
                        val newValue = maxOf(current - 1, min)
                        state.setValue(field.name, newValue.toString())
                        (getChildAt(1) as EditText).setText(newValue.toString())
                        callback.onFormStateChanged(state)
                    }
                }
                addView(decrementBtn)
                
                val editText = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(state.getValue(field.name) ?: field.defaultValue ?: "0")
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    
                    addTextChangedListener(createTextWatcher(field, state, callback))
                }
                addView(editText)
                
                val incrementBtn = Button(context).apply {
                    text = "+"
                    setOnClickListener {
                        val current = state.getValue(field.name)?.toIntOrNull() ?: 0
                        val max = field.maximum?.toInt() ?: Int.MAX_VALUE
                        val newValue = minOf(current + 1, max)
                        state.setValue(field.name, newValue.toString())
                        (getChildAt(1) as EditText).setText(newValue.toString())
                        callback.onFormStateChanged(state)
                    }
                }
                addView(incrementBtn)
            }
        } else {
            // 普通数字输入
            return EditText(context).apply {
                id = VIEW_ID_BASE + field.name.hashCode()
                hint = field.placeholder ?: "0"
                inputType = if (field.type == "integer") {
                    InputType.TYPE_CLASS_NUMBER
                } else {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
                
                setText(state.getValue(field.name) ?: field.defaultValue ?: "")
                
                addTextChangedListener(createTextWatcher(field, state, callback))
            }
        }
    }
    
    /**
     * 创建滑块控件
     */
    private fun createSlider(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): SeekBar {
        val min = field.minimum?.toInt() ?: 0
        val max = field.maximum?.toInt() ?: 100
        
        return SeekBar(context).apply {
            this.max = max - min
            progress = (state.getValue(field.name)?.toIntOrNull() ?: min) - min
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    state.setValue(field.name, value.toString())
                    callback.onFormStateChanged(state)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }
    
    /**
     * 创建复选框
     */
    private fun createCheckbox(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): CheckBox {
        return CheckBox(context).apply {
            id = VIEW_ID_BASE + field.name.hashCode()
            text = field.title
            isChecked = state.getValue(field.name)?.toBoolean() ?: false
            
            setOnCheckedChangeListener { _, isChecked ->
                state.setValue(field.name, isChecked.toString())
                
                if (state.schema.meta.validateOnChange) {
                    validateField(field, isChecked.toString(), state)
                }
                
                callback.onFormStateChanged(state)
            }
        }
    }
    
    /**
     * 创建单选组
     */
    private fun createRadioGroup(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): RadioGroup {
        return RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            
            field.enum?.forEach { option ->
                val radioButton = RadioButton(context).apply {
                    id = VIEW_ID_BASE + (field.name + option).hashCode()
                    text = option
                    isChecked = state.getValue(field.name) == option
                }
                addView(radioButton)
            }
            
            setOnCheckedChangeListener { _, checkedId ->
                val selectedOption = (findViewById<RadioButton>(checkedId))?.text?.toString()
                selectedOption?.let {
                    state.setValue(field.name, it)
                    callback.onFormStateChanged(state)
                }
            }
        }
    }
    
    /**
     * 创建下拉选择
     */
    private fun createSpinner(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): Spinner {
        return Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                field.enum ?: emptyList()
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            
            // 设置初始选中
            val currentValue = state.getValue(field.name)
            field.enum?.indexOf(currentValue)?.let { index ->
                if (index >= 0) setSelection(index)
            }
            
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedValue = field.enum?.get(position) ?: return
                    state.setValue(field.name, selectedValue)
                    callback.onFormStateChanged(state)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }
    
    /**
     * 创建多选列表
     */
    private fun createMultiSelect(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            
            val currentValues = state.getValue(field.name)?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
            
            field.enum?.forEach { option ->
                val checkBox = CheckBox(context).apply {
                    text = option
                    isChecked = currentValues.contains(option)
                    
                    setOnCheckedChangeListener { _, _ ->
                        // 收集所有选中的值
                        val selected = mutableListOf<String>()
                        for (i in 0 until childCount) {
                            val cb = getChildAt(i) as? CheckBox
                            if (cb?.isChecked == true) {
                                selected.add(cb.text.toString())
                            }
                        }
                        state.setValue(field.name, selected.joinToString(","))
                        callback.onFormStateChanged(state)
                    }
                }
                addView(checkBox)
            }
        }
    }
    
    /**
     * 创建日期选择器（简化版）
     */
    private fun createDatePicker(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): EditText {
        return EditText(context).apply {
            hint = "YYYY-MM-DD"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            setText(state.getValue(field.name) ?: "")
            
            // TODO: 集成 DatePickerDialog
            addTextChangedListener(createTextWatcher(field, state, callback))
        }
    }
    
    /**
     * 创建时间选择器（简化版）
     */
    private fun createTimePicker(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): EditText {
        return EditText(context).apply {
            hint = "HH:MM"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            setText(state.getValue(field.name) ?: "")
            
            // TODO: 集成 TimePickerDialog
            addTextChangedListener(createTextWatcher(field, state, callback))
        }
    }
    
    /**
     * 创建日期时间选择器（简化版）
     */
    private fun createDateTimePicker(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): EditText {
        return EditText(context).apply {
            hint = "YYYY-MM-DD HH:MM"
            inputType = InputType.TYPE_CLASS_DATETIME
            setText(state.getValue(field.name) ?: "")
            
            addTextChangedListener(createTextWatcher(field, state, callback))
        }
    }
    
    /**
     * 创建通用 TextWatcher
     */
    private fun createTextWatcher(
        field: FieldSchema,
        state: FormState,
        callback: RenderCallback
    ): TextWatcher {
        return object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString() ?: ""
                state.setValue(field.name, value)
                
                if (state.schema.meta.validateOnChange) {
                    validateField(field, value, state)
                    updateErrorView(field.name, state.errors[field.name])
                }
                
                callback.onFormStateChanged(state)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
    }
    
    /**
     * 校验单个字段
     */
    private fun validateField(field: FieldSchema, value: String, state: FormState) {
        // 必填校验
        if (field.required && value.isBlank()) {
            state.setError(field.name, "${field.title}不能为空")
            return
        }
        
        if (value.isBlank()) {
            state.setError(field.name, null)
            return
        }
        
        // 长度校验
        field.minLength?.let { min ->
            if (value.length < min) {
                state.setError(field.name, "长度不能少于 $min 个字符")
                return
            }
        }
        
        field.maxLength?.let { max ->
            if (value.length > max) {
                state.setError(field.name, "长度不能超过 $max 个字符")
                return
            }
        }
        
        // 正则校验
        field.pattern?.let { pattern ->
            if (!Regex(pattern).matches(value)) {
                state.setError(field.name, "格式不正确")
                return
            }
        }
        
        // 数值范围校验
        if (field.type == "number" || field.type == "integer") {
            val numValue = value.toDoubleOrNull()
            if (numValue == null) {
                state.setError(field.name, "必须是数字")
                return
            }
            
            field.minimum?.let { min ->
                if (numValue < min) {
                    state.setError(field.name, "不能小于 $min")
                    return
                }
            }
            
            field.maximum?.let { max ->
                if (numValue > max) {
                    state.setError(field.name, "不能大于 $max")
                    return
                }
            }
        }
        
        // 通过校验
        state.setError(field.name, null)
    }
    
    /**
     * 创建标题视图
     */
    private fun createTitleView(title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
    }
    
    /**
     * 创建描述视图
     */
    private fun createDescriptionView(description: String): TextView {
        return TextView(context).apply {
            text = description
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 24)
        }
    }
    
    /**
     * 创建标签视图
     */
    private fun createLabelView(field: FieldSchema): TextView {
        return TextView(context).apply {
            text = if (field.required) "${field.title} *" else field.title
            textSize = 16f
            setPadding(0, 0, 0, 8)
            
            if (!field.description.isNullOrBlank()) {
                // TODO: 添加描述提示图标
            }
        }
    }
    
    /**
     * 创建错误视图
     */
    private fun createErrorView(fieldName: String): TextView {
        return TextView(context).apply {
            id = VIEW_ID_BASE + fieldName.hashCode() + 1000 // 错误视图 ID
            textSize = 12f
            setTextColor(0xFFD32F2F.toInt())
            setPadding(0, 4, 0, 0)
            isVisible = false
        }
    }
    
    /**
     * 更新错误视图
     */
    private fun updateErrorView(fieldName: String, error: String?) {
        val errorViewId = VIEW_ID_BASE + fieldName.hashCode() + 1000
        // TODO: 通过父容器查找并更新 errorView
    }
    
    /**
     * 创建预填提示视图
     */
    private fun createPrefilledHintView(
        fieldName: String,
        state: FormState,
        callback: RenderCallback
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
            
            val hintText = TextView(context).apply {
                text = "已自动填充"
                textSize = 12f
                setTextColor(0xFF1976D2.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(hintText)
            
            val clearBtn = Button(context).apply {
                text = "清除"
                textSize = 12f
                setOnClickListener {
                    state.clearPrefilled(fieldName)
                    // TODO: 清除对应控件的值
                    callback.onFormStateChanged(state)
                }
            }
            addView(clearBtn)
        }
    }
    
    /**
     * 创建按钮组
     */
    private fun createButtonGroup(
        meta: FormMeta,
        state: FormState,
        callback: RenderCallback
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
            
            // 取消按钮
            val cancelBtn = Button(context).apply {
                text = meta.cancelLabel ?: "取消"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    callback.onCancelClicked()
                }
            }
            addView(cancelBtn)
            
            // 间隔
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(16, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            
            // 提交按钮
            val submitBtn = Button(context).apply {
                text = meta.submitLabel ?: "提交"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isEnabled = state.canSubmit()
                setOnClickListener {
                    // 提交前全量校验
                    if (state.schema.meta.validateOnSubmit) {
                        val errors = state.schema.validate(state.values)
                        if (errors.isNotEmpty()) {
                            errors.forEach { error ->
                                state.setError(error.fieldName, error.message)
                                updateErrorView(error.fieldName, error.message)
                            }
                            return@setOnClickListener
                        }
                    }
                    
                    callback.onSubmitClicked(state)
                }
            }
            addView(submitBtn)
        }
    }
}
