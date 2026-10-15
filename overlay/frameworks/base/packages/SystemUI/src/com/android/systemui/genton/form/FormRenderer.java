// AI Native OS - Form Renderer (D5.1)
package com.android.systemui.genton.form;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.genton.agent.FormPayload;
import com.genton.agent.FormField;
import com.genton.agent.FormValueEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表单渲染器：schema→控件纯函数渲染（D5.1）
 * 
 * 核心职责：
 * - FormField[] → UI控件映射
 * - 内联校验（必填项检查）
 * - canSubmit状态计算
 * - 预填值显示
 * - 敏感字段FLAG_SECURE处理
 */
public class FormRenderer {
    private static final String TAG = "FormRenderer";
    
    private final Context mContext;
    private final ViewGroup mContainer;
    
    private FormPayload mFormPayload;
    private Map<String, View> mFieldWidgets; // key -> 控件映射
    private Map<String, String> mFieldValues; // key -> 当前值
    
    private OnFormChangeListener mChangeListener;
    
    public interface OnFormChangeListener {
        void onFormChanged(boolean canSubmit);
        void onFormSubmit(FormValueEntry[] values);
        void onFormCancel();
    }
    
    public FormRenderer(Context context, ViewGroup container) {
        mContext = context;
        mContainer = container;
        mFieldWidgets = new HashMap<>();
        mFieldValues = new HashMap<>();
    }
    
    /**
     * 渲染表单（纯函数：FormPayload → UI）
     */
    public void render(FormPayload formPayload) {
        mFormPayload = formPayload;
        mContainer.removeAllViews();
        mFieldWidgets.clear();
        mFieldValues.clear();
        
        // 创建表单布局
        LinearLayout formLayout = new LinearLayout(mContext);
        formLayout.setOrientation(LinearLayout.VERTICAL);
        formLayout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        formLayout.setBackgroundColor(Color.WHITE);
        
        // 表单标题
        TextView titleText = new TextView(mContext);
        titleText.setText("填写表单");
        titleText.setTextSize(18);
        titleText.setTextColor(Color.parseColor("#212121"));
        formLayout.addView(titleText);
        
        // 工具名称
        TextView toolNameText = new TextView(mContext);
        toolNameText.setText("操作: " + formPayload.toolName);
        toolNameText.setTextSize(14);
        toolNameText.setTextColor(Color.parseColor("#757575"));
        LinearLayout.LayoutParams toolNameParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        toolNameParams.topMargin = dpToPx(4);
        formLayout.addView(toolNameText, toolNameParams);
        
        // 错误提示
        if (!TextUtils.isEmpty(formPayload.error)) {
            TextView errorText = new TextView(mContext);
            errorText.setText("⚠️ " + formPayload.error);
            errorText.setTextSize(14);
            errorText.setTextColor(Color.parseColor("#D32F2F"));
            LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            errorParams.topMargin = dpToPx(8);
            formLayout.addView(errorText, errorParams);
        }
        
        // 渲染字段
        if (formPayload.fields != null) {
            for (FormField field : formPayload.fields) {
                View fieldView = renderField(field);
                if (fieldView != null) {
                    LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    fieldParams.topMargin = dpToPx(16);
                    formLayout.addView(fieldView, fieldParams);
                    
                    // 记录字段初始值
                    mFieldValues.put(field.key, field.value != null ? field.value : "");
                }
            }
        }
        
        // 按钮区域
        LinearLayout buttonLayout = new LinearLayout(mContext);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonLayoutParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonLayoutParams.topMargin = dpToPx(24);
        formLayout.addView(buttonLayout, buttonLayoutParams);
        
        // 取消按钮
        Button cancelButton = new Button(mContext);
        cancelButton.setText("取消");
        cancelButton.setTextColor(Color.parseColor("#757575"));
        cancelButton.setBackgroundColor(Color.parseColor("#EEEEEE"));
        cancelButton.setOnClickListener(v -> {
            if (mChangeListener != null) {
                mChangeListener.onFormCancel();
            }
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
            0,
            dpToPx(48)
        );
        cancelParams.weight = 1;
        cancelParams.rightMargin = dpToPx(8);
        buttonLayout.addView(cancelButton, cancelParams);
        
        // 提交按钮
        Button submitButton = new Button(mContext);
        submitButton.setText("提交");
        submitButton.setTextColor(Color.WHITE);
        submitButton.setBackgroundColor(Color.parseColor("#2196F3"));
        submitButton.setEnabled(formPayload.canSubmit);
        submitButton.setOnClickListener(v -> submitForm());
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(
            0,
            dpToPx(48)
        );
        submitParams.weight = 1;
        buttonLayout.addView(submitButton, submitParams);
        
        mContainer.addView(formLayout);
        
        Log.i(TAG, "Form rendered: " + formPayload.formId + ", fields=" + 
            (formPayload.fields != null ? formPayload.fields.length : 0));
    }
    
    /**
     * 渲染单个字段（schema→控件映射）
     */
    private View renderField(FormField field) {
        LinearLayout fieldContainer = new LinearLayout(mContext);
        fieldContainer.setOrientation(LinearLayout.VERTICAL);
        
        // 字段标签
        TextView labelText = new TextView(mContext);
        String labelStr = field.label + (field.required ? " *" : "");
        if (field.prefilled) {
            labelStr += " (已预填)";
        }
        labelText.setText(labelStr);
        labelText.setTextSize(14);
        labelText.setTextColor(Color.parseColor("#424242"));
        fieldContainer.addView(labelText);
        
        // 提示文本
        if (!TextUtils.isEmpty(field.schemaHint)) {
            TextView hintText = new TextView(mContext);
            hintText.setText(field.schemaHint);
            hintText.setTextSize(12);
            hintText.setTextColor(Color.parseColor("#9E9E9E"));
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            hintParams.topMargin = dpToPx(4);
            fieldContainer.addView(hintText, hintParams);
        }
        
        // 根据widgetType创建控件
        View widget = createWidget(field);
        if (widget != null) {
            LinearLayout.LayoutParams widgetParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            widgetParams.topMargin = dpToPx(8);
            fieldContainer.addView(widget, widgetParams);
            
            mFieldWidgets.put(field.key, widget);
        }
        
        // 预填清除按钮（D5.3）
        if (field.prefilled) {
            Button clearButton = new Button(mContext);
            clearButton.setText("清除预填");
            clearButton.setTextSize(12);
            clearButton.setTextColor(Color.parseColor("#FF5722"));
            clearButton.setBackgroundColor(Color.TRANSPARENT);
            clearButton.setOnClickListener(v -> clearPrefilled(field.key));
            LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(32)
            );
            clearParams.topMargin = dpToPx(4);
            fieldContainer.addView(clearButton, clearParams);
        }
        
        return fieldContainer;
    }
    
    /**
     * 创建控件
     */
    private View createWidget(FormField field) {
        switch (field.widgetType) {
            case 0: // text
                return createTextWidget(field, InputType.TYPE_CLASS_TEXT);
                
            case 1: // number
                return createTextWidget(field, InputType.TYPE_CLASS_NUMBER);
                
            case 2: // date
                // TODO: 完整日期选择器实现
                return createTextWidget(field, InputType.TYPE_CLASS_DATETIME);
                
            case 3: // contact
                // TODO: 联系人选择器实现
                return createTextWidget(field, InputType.TYPE_CLASS_TEXT);
                
            case 4: // file
                // TODO: 文件选择器实现
                return createTextWidget(field, InputType.TYPE_CLASS_TEXT);
                
            case 5: // enum
                return createEnumWidget(field);
                
            case 6: // switch
                return createSwitchWidget(field);
                
            case 7: // secret
                return createSecretWidget(field);
                
            default:
                return createTextWidget(field, InputType.TYPE_CLASS_TEXT);
        }
    }
    
    /**
     * 创建文本输入控件
     */
    private View createTextWidget(FormField field, int inputType) {
        EditText editText = new EditText(mContext);
        editText.setInputType(inputType);
        editText.setText(field.value);
        editText.setHint("请输入" + field.label);
        editText.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        editText.setBackgroundColor(Color.parseColor("#F5F5F5"));
        
        // 监听值变化
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mFieldValues.put(field.key, s.toString());
                checkCanSubmit();
            }
            
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        
        return editText;
    }
    
    /**
     * 创建枚举下拉控件
     */
    private View createEnumWidget(FormField field) {
        Spinner spinner = new Spinner(mContext);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            mContext,
            android.R.layout.simple_spinner_item,
            field.enumValues
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        
        // 设置默认值
        if (!TextUtils.isEmpty(field.value)) {
            for (int i = 0; i < field.enumValues.length; i++) {
                if (field.enumValues[i].equals(field.value)) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }
        
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mFieldValues.put(field.key, field.enumValues[position]);
                checkCanSubmit();
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        return spinner;
    }
    
    /**
     * 创建开关控件
     */
    private View createSwitchWidget(FormField field) {
        Switch switchWidget = new Switch(mContext);
        switchWidget.setChecked("true".equals(field.value));
        
        switchWidget.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mFieldValues.put(field.key, String.valueOf(isChecked));
            checkCanSubmit();
        });
        
        return switchWidget;
    }
    
    /**
     * 创建敏感输入控件（FLAG_SECURE）
     */
    private View createSecretWidget(FormField field) {
        EditText editText = new EditText(mContext);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setHint("请输入" + field.label);
        editText.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        editText.setBackgroundColor(Color.parseColor("#FFF3E0"));
        
        // 敏感字段不预填、不显示值
        // 只记录是否有值
        editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mFieldValues.put(field.key, s.length() > 0 ? "__SECRET__" : "");
                checkCanSubmit();
            }
            
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        
        return editText;
    }
    
    /**
     * 清除预填值（D5.3）
     */
    private void clearPrefilled(String key) {
        View widget = mFieldWidgets.get(key);
        if (widget instanceof EditText) {
            ((EditText) widget).setText("");
        } else if (widget instanceof Spinner) {
            ((Spinner) widget).setSelection(0);
        } else if (widget instanceof Switch) {
            ((Switch) widget).setChecked(false);
        }
        mFieldValues.put(key, "");
        checkCanSubmit();
        Log.d(TAG, "Prefilled value cleared: " + key);
    }
    
    /**
     * 检查是否可提交（内联校验）
     */
    private void checkCanSubmit() {
        if (mFormPayload == null || mFormPayload.fields == null) {
            return;
        }
        
        boolean canSubmit = true;
        
        // 检查所有必填项
        for (FormField field : mFormPayload.fields) {
            if (field.required) {
                String value = mFieldValues.get(field.key);
                if (TextUtils.isEmpty(value)) {
                    canSubmit = false;
                    break;
                }
            }
        }
        
        if (mChangeListener != null) {
            mChangeListener.onFormChanged(canSubmit);
        }
    }
    
    /**
     * 提交表单
     */
    private void submitForm() {
        if (mFormPayload == null) {
            return;
        }
        
        List<FormValueEntry> entries = new ArrayList<>();
        
        for (FormField field : mFormPayload.fields) {
            FormValueEntry entry = new FormValueEntry();
            entry.key = field.key;
            
            String value = mFieldValues.get(field.key);
            
            if (field.sensitive) {
                // 敏感字段：不传值，只传hasValue标志
                entry.value = "";
                entry.hasValue = !TextUtils.isEmpty(value);
            } else {
                entry.value = value != null ? value : "";
                entry.hasValue = !TextUtils.isEmpty(value);
            }
            
            entries.add(entry);
        }
        
        if (mChangeListener != null) {
            mChangeListener.onFormSubmit(entries.toArray(new FormValueEntry[0]));
        }
        
        Log.i(TAG, "Form submitted: " + mFormPayload.formId);
    }
    
    /**
     * 设置表单变化监听器
     */
    public void setOnFormChangeListener(OnFormChangeListener listener) {
        mChangeListener = listener;
    }
    
    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
