package com.genton.macro

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * F1.1 宏录制工具链 - 录制引擎
 * 
 * 职责：
 * 1. 录制用户交互（点击、滑动、输入等）
 * 2. 捕获屏幕状态和感知结果
 * 3. 生成时序化的步骤序列
 * 4. 与 A2.1 感知聚合器集成
 * 
 * 技术要点：
 * - 通过 AccessibilityService 捕获用户交互
 * - 通过 A2.1 获取感知结果（元素识别）
 * - 时序同步：记录相对时间戳
 * - 坐标归一化：适配不同分辨率
 */
class MacroRecordingEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "MacroRecordingEngine"
        
        // 步骤类型
        const val STEP_TYPE_TAP = 0
        const val STEP_TYPE_SWIPE = 1
        const val STEP_TYPE_INPUT_TEXT = 2
        const val STEP_TYPE_WAIT = 3
        const val STEP_TYPE_ASSERT_ELEMENT = 4
        const val STEP_TYPE_SCREENSHOT = 5
        
        // 自动插入等待的最小间隔（毫秒）
        private const val MIN_WAIT_MS = 300L
        
        // 坐标归一化因子（0.0-1.0）
        private const val USE_NORMALIZED_COORDS = true
    }
    
    // 当前活跃录制
    private val activeRecordings = ConcurrentHashMap<String, RecordingSession>()
    
    // 录制 ID 生成器
    private val recordingIdCounter = AtomicLong(0)
    
    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * 开始录制
     */
    fun startRecording(name: String, targetPackage: String?): String {
        val recordingId = generateRecordingId()
        
        val session = RecordingSession(
            recordingId = recordingId,
            name = name,
            targetPackage = targetPackage,
            startTime = System.currentTimeMillis()
        )
        
        activeRecordings[recordingId] = session
        
        Log.i(TAG, "Started recording: id=$recordingId, name=$name, package=$targetPackage")
        
        return recordingId
    }
    
    /**
     * 停止录制
     */
    fun stopRecording(recordingId: String): MacroRecording? {
        val session = activeRecordings.remove(recordingId)
        if (session == null) {
            Log.w(TAG, "Recording not found: $recordingId")
            return null
        }
        
        session.endTime = System.currentTimeMillis()
        session.state = "completed"
        
        // 构建 MacroRecording
        val recording = buildMacroRecording(session)
        
        Log.i(TAG, "Stopped recording: id=$recordingId, steps=${session.steps.size}")
        
        return recording
    }
    
    /**
     * 取消录制
     */
    fun cancelRecording(recordingId: String): Boolean {
        val session = activeRecordings.remove(recordingId)
        if (session == null) {
            Log.w(TAG, "Recording not found: $recordingId")
            return false
        }
        
        session.state = "canceled"
        
        Log.i(TAG, "Canceled recording: id=$recordingId")
        
        return true
    }
    
    /**
     * 记录触摸事件
     * 
     * 由 AccessibilityService 调用
     */
    fun recordMotionEvent(recordingId: String, event: MotionEvent) {
        val session = activeRecordings[recordingId] ?: return
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录按下位置
                session.lastTapX = event.x.toInt()
                session.lastTapY = event.y.toInt()
                session.lastTapTime = System.currentTimeMillis()
            }
            
            MotionEvent.ACTION_UP -> {
                val upX = event.x.toInt()
                val upY = event.y.toInt()
                val upTime = System.currentTimeMillis()
                
                // 判断是点击还是滑动
                val distance = calculateDistance(session.lastTapX, session.lastTapY, upX, upY)
                val duration = upTime - session.lastTapTime
                
                if (distance < 20 && duration < 500) {
                    // 点击
                    recordTap(session, session.lastTapX, session.lastTapY)
                } else {
                    // 滑动
                    recordSwipe(session, session.lastTapX, session.lastTapY, upX, upY, duration.toInt())
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                // 记录移动轨迹（简化实现，只记录起点和终点）
            }
        }
    }
    
    /**
     * 记录 Accessibility 事件
     * 
     * 用于捕获输入、导航等高层事件
     */
    fun recordAccessibilityEvent(recordingId: String, event: AccessibilityEvent) {
        val session = activeRecordings[recordingId] ?: return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // 记录文本输入
                val text = event.text.firstOrNull()?.toString()
                if (text != null && text.isNotEmpty()) {
                    recordInputText(session, text)
                }
            }
            
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // 记录焦点变化（可选）
            }
            
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 记录窗口切换（可选，用于多应用宏）
            }
        }
    }
    
    /**
     * 记录点击
     */
    private fun recordTap(session: RecordingSession, x: Int, y: Int) {
        val step = createStep(session, STEP_TYPE_TAP).apply {
            this.x = if (USE_NORMALIZED_COORDS) normalizeX(x) else x
            this.y = if (USE_NORMALIZED_COORDS) normalizeY(y) else y
            description = "Tap at ($x, $y)"
        }
        
        addStep(session, step)
        
        Log.d(TAG, "Recorded tap: x=$x, y=$y")
    }
    
    /**
     * 记录滑动
     */
    private fun recordSwipe(session: RecordingSession, startX: Int, startY: Int, endX: Int, endY: Int, duration: Int) {
        val step = createStep(session, STEP_TYPE_SWIPE).apply {
            this.x = if (USE_NORMALIZED_COORDS) normalizeX(startX) else startX
            this.y = if (USE_NORMALIZED_COORDS) normalizeY(startY) else startY
            this.targetX = if (USE_NORMALIZED_COORDS) normalizeX(endX) else endX
            this.targetY = if (USE_NORMALIZED_COORDS) normalizeY(endY) else endY
            this.durationMs = duration
            description = "Swipe from ($startX, $startY) to ($endX, $endY)"
        }
        
        addStep(session, step)
        
        Log.d(TAG, "Recorded swipe: ($startX, $startY) -> ($endX, $endY), duration=$duration ms")
    }
    
    /**
     * 记录文本输入
     */
    private fun recordInputText(session: RecordingSession, text: String) {
        val step = createStep(session, STEP_TYPE_INPUT_TEXT).apply {
            this.inputText = text
            description = "Input text: $text"
        }
        
        addStep(session, step)
        
        Log.d(TAG, "Recorded input: text=$text")
    }
    
    /**
     * 记录等待
     */
    fun recordWait(recordingId: String, waitMs: Int) {
        val session = activeRecordings[recordingId] ?: return
        
        val step = createStep(session, STEP_TYPE_WAIT).apply {
            this.waitMs = waitMs
            description = "Wait $waitMs ms"
        }
        
        addStep(session, step)
        
        Log.d(TAG, "Recorded wait: $waitMs ms")
    }
    
    /**
     * 记录截图检查点
     */
    fun recordScreenshot(recordingId: String, screenshotPath: String) {
        val session = activeRecordings[recordingId] ?: return
        
        val step = createStep(session, STEP_TYPE_SCREENSHOT).apply {
            this.screenshotPath = screenshotPath
            description = "Screenshot checkpoint"
        }
        
        addStep(session, step)
        
        Log.d(TAG, "Recorded screenshot: $screenshotPath")
    }
    
    /**
     * 记录元素断言（感知集成）
     */
    fun recordElementAssertion(recordingId: String, elementDescription: String, confidence: Float = 0.85f) {
        val session = activeRecordings[recordingId] ?: return
        
        val step = createStep(session, STEP_TYPE_ASSERT_ELEMENT).apply {
            this.elementDescription = elementDescription
            this.confidenceThreshold = confidence
            description = "Assert element: $elementDescription"
        }
        
        addStep(session, step)
        
        Log.d(TAG, "Recorded element assertion: $elementDescription")
    }
    
    /**
     * 创建步骤
     */
    private fun createStep(session: RecordingSession, type: Int): MacroStep {
        val stepId = session.steps.size
        val timestamp = System.currentTimeMillis() - session.startTime
        
        return MacroStep().apply {
            this.stepId = stepId
            this.type = type
            this.timestamp = timestamp
        }
    }
    
    /**
     * 添加步骤（自动插入等待）
     */
    private fun addStep(session: RecordingSession, step: MacroStep) {
        // 自动插入等待步骤（如果间隔过长）
        if (session.steps.isNotEmpty()) {
            val lastStep = session.steps.last()
            val interval = step.timestamp - lastStep.timestamp
            
            if (interval > MIN_WAIT_MS) {
                val waitStep = MacroStep().apply {
                    this.stepId = session.steps.size
                    this.type = STEP_TYPE_WAIT
                    this.timestamp = lastStep.timestamp
                    this.waitMs = interval.toInt()
                    this.description = "Auto wait ${interval}ms"
                }
                session.steps.add(waitStep)
            }
        }
        
        session.steps.add(step)
    }
    
    /**
     * 构建 MacroRecording
     */
    private fun buildMacroRecording(session: RecordingSession): MacroRecording {
        return MacroRecording().apply {
            recordingId = session.recordingId
            name = session.name
            description = session.name
            startTime = session.startTime
            endTime = session.endTime
            steps = session.steps.toTypedArray()
            
            // 设备信息
            deviceModel = android.os.Build.MODEL
            deviceManufacturer = android.os.Build.MANUFACTURER
            screenWidth = context.resources.displayMetrics.widthPixels
            screenHeight = context.resources.displayMetrics.heightPixels
            screenDensity = context.resources.displayMetrics.density
            sdkVersion = android.os.Build.VERSION.SDK_INT
            
            targetPackage = session.targetPackage ?: ""
            state = session.state
        }
    }
    
    /**
     * 坐标归一化（0.0-1.0）
     */
    private fun normalizeX(x: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return ((x.toFloat() / screenWidth) * 10000).toInt()  // 存储为万分比
    }
    
    private fun normalizeY(y: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return ((y.toFloat() / screenHeight) * 10000).toInt()  // 存储为万分比
    }
    
    /**
     * 计算距离
     */
    private fun calculateDistance(x1: Int, y1: Int, x2: Int, y2: Int): Float {
        val dx = (x2 - x1).toFloat()
        val dy = (y2 - y1).toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
    
    /**
     * 生成录制 ID
     */
    private fun generateRecordingId(): String {
        val id = recordingIdCounter.incrementAndGet()
        return "rec_${System.currentTimeMillis()}_${id}"
    }
    
    /**
     * 获取当前录制
     */
    fun getCurrentRecording(): String? {
        return activeRecordings.keys.firstOrNull()
    }
    
    /**
     * 录制会话
     */
    private data class RecordingSession(
        val recordingId: String,
        val name: String,
        val targetPackage: String?,
        val startTime: Long,
        var endTime: Long = 0,
        var state: String = "recording",
        val steps: MutableList<MacroStep> = mutableListOf(),
        var lastTapX: Int = 0,
        var lastTapY: Int = 0,
        var lastTapTime: Long = 0
    )
}
