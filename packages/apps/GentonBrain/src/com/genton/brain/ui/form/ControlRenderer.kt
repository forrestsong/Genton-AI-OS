package com.genton.brain.ui.form

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.genton.brain.schema.ControlRenderParams
import com.genton.brain.schema.ControlType
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText

/**
 * D5.1 L0.5 内联表单渲染 - 控件渲染器
 * 
 * 职责：
 * 1. 将单个表单字段渲染为对应的 Android 控件
 * 2. 应用字段配置（必填、禁用、只读等）
 * 3. 绑定值变化监听器
 * 4. 显示/清除错误信息
 * 
 * 支持的控件类型：
 * - 文本输入类：TextField、TextArea、Password、Email、Phone 等
 * - 数字输入类：Number、Slider
 * - 选择类：Dropdown、Radio、Switch、Checkbox
 * - 日期时间类：DatePicker、TimePicker
 * - 特殊类：Hidden、StaticText
 */
class ControlRenderer(private val context: Context) {
    
    companion object {
        private const val TAG = "ControlRenderer"
    }
    
    // 值变化监听器映射
    private val changeListeners = mutableMapOf<View, (Any?) -> Unit>()
    
    /**
     * 渲染控件
     */
    fun render(params: ControlRenderParams): View {
        Log.d(TAG, "Rendering control: ${params.controlType} for field ${params.field.title}")
        
        val view = when (params.controlType) {
            // 文本输入类
            ControlType.TEXT_FIELD -> renderTextField(params)
            ControlType.TEXT_AREA -> renderTextArea(params)
            ControlType.PASSWORD -> renderPasswordField(params)
            ControlType.EMAIL -> renderEmailField(params)
            ControlType.PHONE -> renderPhoneField(params)
            ControlType.URL -> renderUrlField(params)
            ControlType.SEARCH -> renderSearchField(params)
            
            // 数字输入类
            ControlType.NUMBER -> renderNumberField(params)
            ControlType.SLIDER -> renderSlider(params)
            ControlType.STEPPER -> renderStepper(params)
            
            // 选择类
            ControlType.DROPDOWN -> renderDropdown(params)
            ControlType.RADIO -> renderRadio(params)
            ControlType.SWITCH -> renderSwitch(params)
            ControlType.CHECKBOX -> renderCheckbox(params)
            
            // 日期时间类
            ControlType.DATE_PICKER -> renderDatePicker(params)
            ControlType.TIME_PICKER -> renderTimePicker(params)
            ControlType.DATETIME_PICKER -> renderDateTimePicker(params)
            
            // 特殊类
            ControlType.HIDDEN -> renderHidden(params)
            ControlType.STATIC_TEXT -> renderStaticText(params)
            
            else -> {
                Log.w(TAG, "Unsupported control type: ${params.controlType}, using TextField")
                renderTextField(params)
            }
        }
        
        return view
    }
    
    /**
     * D5.3 渲染控件（带预填信息）
     */
    fun renderWithPrefill(params: ControlRenderParams, prefillValue: PrefillValue?): View {
        val view = render(params)
        
        // 如果有预填值，应用高亮
        if (prefillValue != null) {
            applyPrefillHighlight(view, prefillValue)
        }
        
        return view
    }
    
    /**
     * D5.3 应用预填高亮
     */
    fun applyPrefillHighlight(view: View, prefillValue: PrefillValue) {
        prefillHighlighter.highlight(view, prefillValue)
        prefillInfoMap[view] = prefillValue
        
        Log.d(TAG, "Applied prefill highlight: ${prefillValue.fieldName}, source=${prefillValue.source}")
    }
    
    /**
     * D5.3 清除预填高亮
     */
    fun clearPrefillHighlight(view: View) {
        prefillHighlighter.clearHighlight(view)
        prefillInfoMap.remove(view)
    }
    
    /**
     * D5.3 获取预填信息
     */
    fun getPrefillInfo(view: View): PrefillValue? {
        return prefillInfoMap[view]
    }
    
    /**
     * D5.3 检查是否为预填字段
     */
    fun isPrefilled(view: View): Boolean {
        return prefillInfoMap.containsKey(view)
    }
    
    // ============ 文本输入类渲染 ============
    
    /**
     * 渲染文本输入框
     */
    private fun renderTextField(params: ControlRenderParams): View {
        return createTextInputLayout(params, InputType.TYPE_CLASS_TEXT)
    }
    
    /**
     * 渲染多行文本框
     */
    private fun renderTextArea(params: ControlRenderParams): View {
        return createTextInputLayout(
            params,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            minLines = 3
        )
    }
    
    /**
     * 渲染密码输入框
     */
    private fun renderPasswordField(params: ControlRenderParams): View {
        return createTextInputLayout(
            params,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
    }
    
    /**
     * 渲染邮箱输入框
     */
    private fun renderEmailField(params: ControlRenderParams): View {
        return createTextInputLayout(
            params,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
    }
    
    /**
     * 渲染手机号输入框
     */
    private fun renderPhoneField(params: ControlRenderParams): View {
        return createTextInputLayout(
            params,
            InputType.TYPE_CLASS_PHONE
        )
    }
    
    /**
     * 渲染URL输入框
     */
    private fun renderUrlField(params: ControlRenderParams): View {
        return createTextInputLayout(
            params,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
    }
    
    /**
     * 渲染搜索框
     */
    private fun renderSearchField(params: ControlRenderParams): View {
        return createTextInputLayout(
            params,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
    }
    
    /**
     * 创建 TextInputLayout 包装的输入框
     */
    private fun createTextInputLayout(
        params: ControlRenderParams,
        inputType: Int,
        minLines: Int = 1
    ): TextInputLayout {
        val textInputLayout = TextInputLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
            
            hint = buildHint(params)
            isHintEnabled = true
        }
        
        val editText = TextInputEditText(context).apply {
            this.inputType = inputType
            this.minLines = minLines
            
            // 设置占位符
            val placeholder = params.getPlaceholder()
            if (placeholder != null && hint.isNullOrEmpty()) {
                this.hint = placeholder
            }
            
            // 设置初始值
            if (params.value != null) {
                setText(params.value.toString())
            }
            
            // 禁用/只读
            isEnabled = !params.disabled && !params.readonly
            
            // 添加文本变化监听
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    notifyValueChanged(textInputLayout, s?.toString())
                }
            })
        }
        
        textInputLayout.addView(editText)
        
        // 添加帮助文本
        val helpText = params.getHelpText()
        if (helpText != null) {
            textInputLayout.helperText = helpText
        }
        
        return textInputLayout
    }
    
    // ============ 数字输入类渲染 ============
    
    /**
     * 渲染数字输入框
     */
    private fun renderNumberField(params: ControlRenderParams): View {
        return createTextInputLayout(
            params,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        )
    }
    
    /**
     * 渲染滑块
     */
    private fun renderSlider(params: ControlRenderParams): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }
        
        // 标签
        val label = TextView(context).apply {
            text = buildHint(params)
            textSize = 14f
        }
        container.addView(label)
        
        // 滑块
        val seekBar = SeekBar(context).apply {
            val min = params.field.minimum?.toInt() ?: 0
            val max = params.field.maximum?.toInt() ?: 100
            val current = params.value?.toString()?.toIntOrNull() ?: min
            
            this.max = max - min
            progress = current - min
            
            isEnabled = !params.disabled && !params.readonly
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    valueText.text = value.toString()
                    if (fromUser) {
                        notifyValueChanged(container, value)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        
        // 当前值显示
        val valueText = TextView(context).apply {
            text = seekBar.progress.toString()
            textSize = 12f
            gravity = Gravity.CENTER
        }
        
        container.addView(seekBar)
        container.addView(valueText)
        
        return container
    }
    
    /**
     * 渲染步进器
     */
    private fun renderStepper(params: ControlRenderParams): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val min = params.field.minimum?.toInt() ?: 0
        val max = params.field.maximum?.toInt() ?: 100
        var current = params.value?.toString()?.toIntOrNull() ?: min
        
        // 减少按钮
        val decreaseBtn = Button(context).apply {
            text = "-"
            setOnClickListener {
                if (current > min) {
                    current--
                    valueText.text = current.toString()
                    notifyValueChanged(container, current)
                }
            }
        }
        
        // 当前值
        val valueText = TextView(context).apply {
            text = current.toString()
            textSize = 16f
            gravity = Gravity.CENTER
            val padding = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        
        // 增加按钮
        val increaseBtn = Button(context).apply {
            text = "+"
            setOnClickListener {
                if (current < max) {
                    current++
                    valueText.text = current.toString()
                    notifyValueChanged(container, current)
                }
            }
        }
        
        container.addView(decreaseBtn)
        container.addView(valueText)
        container.addView(increaseBtn)
        
        return container
    }
    
    // ============ 选择类渲染 ============
    
    /**
     * 渲染下拉选择
     */
    private fun renderDropdown(params: ControlRenderParams): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }
        
        // 标签
        val label = TextView(context).apply {
            text = buildHint(params)
            textSize = 14f
        }
        container.addView(label)
        
        // 下拉框
        val spinner = Spinner(context).apply {
            val options = params.field.enum ?: emptyList()
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, options)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            
            // 设置初始选中
            if (params.value != null) {
                val index = options.indexOf(params.value)
                if (index >= 0) {
                    setSelection(index)
                }
            }
            
            isEnabled = !params.disabled && !params.readonly
            
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    notifyValueChanged(container, options[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        
        container.addView(spinner)
        
        return container
    }
    
    /**
     * 渲染单选框组
     */
    private fun renderRadio(params: ControlRenderParams): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }
        
        // 标签
        val label = TextView(context).apply {
            text = buildHint(params)
            textSize = 14f
        }
        container.addView(label)
        
        // 单选组
        val radioGroup = RadioGroup(context).apply {
            val options = params.field.enum ?: emptyList()
            
            options.forEach { option ->
                val radioButton = RadioButton(context).apply {
                    text = option.toString()
                    isChecked = option == params.value
                    isEnabled = !params.disabled && !params.readonly
                }
                addView(radioButton)
            }
            
            setOnCheckedChangeListener { group, checkedId ->
                val radioButton = findViewById<RadioButton>(checkedId)
                notifyValueChanged(container, radioButton.text.toString())
            }
        }
        
        container.addView(radioGroup)
        
        return container
    }
    
    /**
     * 渲染开关
     */
    private fun renderSwitch(params: ControlRenderParams): View {
        val switchCompat = androidx.appcompat.widget.SwitchCompat(context).apply {
            text = buildHint(params)
            isChecked = params.value == true || params.value == "true"
            isEnabled = !params.disabled && !params.readonly
            
            setOnCheckedChangeListener { _, isChecked ->
                notifyValueChanged(this, isChecked)
            }
            
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }
        
        return switchCompat
    }
    
    /**
     * 渲染复选框
     */
    private fun renderCheckbox(params: ControlRenderParams): View {
        val checkBox = CheckBox(context).apply {
            text = buildHint(params)
            isChecked = params.value == true || params.value == "true"
            isEnabled = !params.disabled && !params.readonly
            
            setOnCheckedChangeListener { _, isChecked ->
                notifyValueChanged(this, isChecked)
            }
            
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }
        
        return checkBox
    }
    
    // ============ 日期时间类渲染 ============
    
    /**
     * 渲染日期选择器
     */
    private fun renderDatePicker(params: ControlRenderParams): View {
        return createPickerButton(params, "选择日期") {
            // TODO: 显示日期选择对话框
            Log.d(TAG, "Date picker clicked")
        }
    }
    
    /**
     * 渲染时间选择器
     */
    private fun renderTimePicker(params: ControlRenderParams): View {
        return createPickerButton(params, "选择时间") {
            // TODO: 显示时间选择对话框
            Log.d(TAG, "Time picker clicked")
        }
    }
    
    /**
     * 渲染日期时间选择器
     */
    private fun renderDateTimePicker(params: ControlRenderParams): View {
        return createPickerButton(params, "选择日期时间") {
            // TODO: 显示日期时间选择对话框
            Log.d(TAG, "DateTime picker clicked")
        }
    }
    
    /**
     * 创建选择器按钮
     */
    private fun createPickerButton(params: ControlRenderParams, defaultText: String, onClick: () -> Unit): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }
        
        // 标签
        val label = TextView(context).apply {
            text = buildHint(params)
            textSize = 14f
        }
        container.addView(label)
        
        // 按钮
        val button = Button(context).apply {
            text = params.value?.toString() ?: defaultText
            isEnabled = !params.disabled && !params.readonly
            setOnClickListener { onClick() }
        }
        container.addView(button)
        
        return container
    }
    
    // ============ 特殊类渲染 ============
    
    /**
     * 渲染隐藏字段
     */
    private fun renderHidden(params: ControlRenderParams): View {
        return View(context).apply {
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(0, 0)
        }
    }
    
    /**
     * 渲染静态文本
     */
    private fun renderStaticText(params: ControlRenderParams): View {
        return TextView(context).apply {
            text = "${params.field.title}: ${params.value ?: ""}"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }
    }
    
    // ============ 辅助方法 ============
    
    /**
     * 构建提示文本（包含必填标记）
     */
    private fun buildHint(params: ControlRenderParams): String {
        val hint = params.field.title
        return if (params.isRequired) {
            "$hint *"
        } else {
            hint
        }
    }
    
    /**
     * 设置值变化监听器
     */
    fun setValueChangeListener(view: View, listener: (Any?) -> Unit) {
        changeListeners[view] = listener
    }
    
    /**
     * 通知值变化
     */
    private fun notifyValueChanged(view: View, value: Any?) {
        changeListeners[view]?.invoke(value)
    }
    
    /**
     * 更新控件值
     */
    fun updateValue(view: View, value: Any?) {
        when (view) {
            is TextInputLayout -> {
                val editText = view.editText
                if (editText != null && editText.text.toString() != value?.toString()) {
                    editText.setText(value?.toString() ?: "")
                }
            }
            is androidx.appcompat.widget.SwitchCompat -> {
                view.isChecked = value == true || value == "true"
            }
            is CheckBox -> {
                view.isChecked = value == true || value == "true"
            }
            // TODO: 添加其他控件类型的更新逻辑
        }
    }
    
    /**
     * 显示错误信息
     */
    fun showError(view: View, error: String) {
        when (view) {
            is TextInputLayout -> {
                view.error = error
                view.isErrorEnabled = true
            }
            // TODO: 添加其他控件类型的错误显示
        }
    }
    
    /**
     * 清除错误信息
     */
    fun clearError(view: View) {
        when (view) {
            is TextInputLayout -> {
                view.error = null
                view.isErrorEnabled = false
            }
            // TODO: 添加其他控件类型的错误清除
        }
    }
}
