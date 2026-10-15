package com.genton.systemui.gesture

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * D9.1 姿态手势呈现 - 录音命名预览
 *
 * 录音完成后展示自动生成的备忘命名预览，用户确认或编辑后保存。
 *
 * 安全约束（对齐 E4.3）：
 * - 粗位置命名不出端（仅在设备端生成粗位置标签）
 * - 位置信息为模糊粒度（如"星巴克"而非精确坐标）
 * - 用户可修改/覆盖自动命名
 */
class MemoNamingPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface NamingCallback {
        fun onConfirmName(name: String)
        fun onEditName(currentName: String)
        fun onDiscard()
    }

    private var callback: NamingCallback? = null

    private val autoNameView: TextView
    private val positionTagView: TextView
    private val timeTagView: TextView
    private val confirmBtn: TextView
    private val editBtn: TextView
    private val discardBtn: TextView

    companion object {
        private const val TAG = "MemoNamingPreview"

        private val COLOR_BG = Color.parseColor("#1F2937")
        private val COLOR_TITLE = Color.WHITE
        private val COLOR_AUTO_NAME = Color.parseColor("#2196F3")
        private val COLOR_TAG = Color.parseColor("#9E9E9E")
        private val COLOR_PRIMARY = Color.parseColor("#4CAF50")
        private val COLOR_SECONDARY = Color.parseColor("#FF9800")
        private val COLOR_DANGER = Color.parseColor("#F44336")
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BG)
        val pad16dp = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad16dp, pad16dp, pad16dp, pad16dp)

        val title = TextView(context).apply {
            text = "🎙️ 备忘已录制"
            textSize = 16f
            setTextColor(COLOR_TITLE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val autoLabel = TextView(context).apply {
            text = "自动命名（可修改）："
            textSize = 12f
            setTextColor(COLOR_TAG)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }

        autoNameView = TextView(context).apply {
            textSize = 15f
            setTextColor(COLOR_AUTO_NAME)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * resources.displayMetrics.density).toInt()
            }
        }

        // 标签行：位置 + 时间
        val tagRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

        positionTagView = createTagView()
        timeTagView = createTagView()

        tagRow.addView(positionTagView)
        tagRow.addView(android.widget.Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        tagRow.addView(timeTagView)

        // 按钮行
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * resources.displayMetrics.density).toInt()
            }
        }

        confirmBtn = createButton("✓ 确认保存", COLOR_PRIMARY)
        editBtn = createButton("✎ 修改", COLOR_SECONDARY)
        discardBtn = createButton("🗑 丢弃", COLOR_DANGER)

        btnRow.addView(confirmBtn)
        btnRow.addView(android.widget.Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (8 * resources.displayMetrics.density).toInt(),
                0
            )
        })
        btnRow.addView(editBtn)
        btnRow.addView(android.widget.Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (8 * resources.displayMetrics.density).toInt(),
                0
            )
        })
        btnRow.addView(discardBtn)

        // 组装
        addView(title)
        addView(autoLabel)
        addView(autoNameView)
        addView(tagRow)
        addView(btnRow)

        confirmBtn.setOnClickListener {
            callback?.onConfirmName(autoNameView.text.toString())
            Log.i(TAG, "Memo confirmed: ${autoNameView.text}")
        }
        editBtn.setOnClickListener {
            callback?.onEditName(autoNameView.text.toString())
            Log.i(TAG, "Edit requested: ${autoNameView.text}")
        }
        discardBtn.setOnClickListener {
            callback?.onDiscard()
            Log.i(TAG, "Memo discarded")
        }
    }

    private fun createTagView(): TextView {
        return TextView(context).apply {
            textSize = 11f
            setTextColor(COLOR_TAG)
            gravity = Gravity.CENTER
            val padH = (6 * resources.displayMetrics.density).toInt()
            val padV = (3 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            setBackgroundColor(Color.parseColor("#374151"))
        }
    }

    private fun createButton(text: String, bgColor: Int): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(bgColor)
            val padH = (10 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f
            )
        }
    }

    fun setCallback(cb: NamingCallback) {
        callback = cb
    }

    /**
     * 设置命名预览数据
     *
     * @param autoName 自动生成的名称（已在设备端生成，粗位置+时间）
     * @param locationTag 粗位置标签（如"星巴克"，不出端）
     * @param timeTag 时间标签（如"今天 14:35"）
     */
    fun setNamingData(autoName: String, locationTag: String?, timeTag: String?) {
        autoNameView.text = autoName
        positionTagView.text = locationTag ?: ""
        positionTagView.visibility = if (locationTag != null && locationTag.isNotEmpty()) View.VISIBLE else View.GONE
        timeTagView.text = timeTag ?: ""
        timeTagView.visibility = if (timeTag != null && timeTag.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 更新名称（用户编辑后）
     */
    fun updateName(newName: String) {
        autoNameView.text = newName
    }
}
