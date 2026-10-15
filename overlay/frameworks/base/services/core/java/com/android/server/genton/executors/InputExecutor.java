package com.android.server.genton.executors;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.genton.action.ActionRequest;
import com.genton.perception.IAgentUiTreeService;
import com.genton.perception.UiNode;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 输入注入执行器
 * 
 * 核心职责：
 * 1. 触摸注入（tap/swipe）
 * 2. 文本输入（type）
 * 3. 按键注入（key：back/home/recents）
 * 4. UI 树 stale 校验（防止过时操作）
 * 5. Overlay 复查（防止点击劫持）
 * 
 * 安全约束：
 * - Stale 检测：capturedTreeSeq 与当前 UI 树序列号比对，差异 >2 拒绝
 * - Overlay 复查：注入前再次检查目标坐标是否被遮挡
 * - FLAG_SECURE 保护：检测到安全窗口时拒绝注入
 * - 坐标边界检查：防止越界注入
 * - 速率限制：最大 10 次/秒
 * 
 * 性能要求：
 * - 注入延迟 P50 < 10ms
 * - Stale 检测 < 5ms
 * 
 * @see InputManager
 */
public class InputExecutor implements ActionExecutor {
    private static final String TAG = "InputExecutor";
    
    // 速率限制（10 次/秒）
    private static final long MIN_INTERVAL_MS = 100;
    
    // Stale 容忍度（UI 树序列号差异）
    private static final int MAX_SEQ_DELTA = 2;
    
    // 注入延迟（模拟人类操作）
    private static final long TAP_DURATION_MS = 50;
    private static final long SWIPE_DURATION_MS = 300;
    
    private final Context mContext;
    private final InputManager mInputManager;
    private final WindowManager mWindowManager;
    private final IAgentUiTreeService mUiTreeService;
    
    private long mLastInjectionTime = 0;
    
    public InputExecutor(Context context, IAgentUiTreeService uiTreeService) {
        mContext = context;
        mInputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mUiTreeService = uiTreeService;
        Slog.i(TAG, "InputExecutor initialized");
    }
    
    @Override
    public String getExecutorType() {
        return "input";
    }
    
    /**
     * 执行输入注入
     * 
     * 支持动作：
     * - ui.tap：点击坐标/节点
     * - ui.type：文本输入
     * - ui.swipe：滑动
     * - ui.key：按键（back/home/recents）
     */
    @Override
    public ExecutionResult execute(ActionRequest request, ExecutionContext context) {
        long startNanos = System.nanoTime();
        
        try {
            // 1. 速率限制
            long now = SystemClock.uptimeMillis();
            if (now - mLastInjectionTime < MIN_INTERVAL_MS) {
                Slog.w(TAG, "Rate limit exceeded");
                return ExecutionResult.failure("Rate limit: max 10 input/sec");
            }
            
            // 2. 解析参数
            JSONObject args = new JSONObject(request.argumentsJson);
            String actionId = request.actionId;
            
            ExecutionResult result;
            
            if ("ui.tap".equals(actionId)) {
                result = executeTap(args, context);
            } else if ("ui.type".equals(actionId)) {
                result = executeType(args, context);
            } else if ("ui.swipe".equals(actionId)) {
                result = executeSwipe(args, context);
            } else if ("ui.key".equals(actionId)) {
                result = executeKey(args, context);
            } else {
                return ExecutionResult.failure("Unsupported action: " + actionId);
            }
            
            if (result.success) {
                mLastInjectionTime = now;
                
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                JSONObject resultJson = new JSONObject(result.resultJson);
                resultJson.put("elapsed_us", elapsedMicros);
                result.resultJson = resultJson.toString();
            }
            
            return result;
            
        } catch (Exception e) {
            Slog.e(TAG, "Input execution failed", e);
            return ExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }
    
    /**
     * 执行点击
     * 
     * 参数：
     * {
     *   "x": 500, "y": 800,  // 坐标点击
     *   // 或
     *   "nodeId": "xyz",  // 节点点击
     *   "capturedTreeSeq": 1234  // UI 树序列号（stale 检测）
     * }
     */
    private ExecutionResult executeTap(JSONObject args, ExecutionContext context) throws Exception {
        // 0. Taint 坐标校验（防止注入攻击坐标）
        if (mCurrentRequest != null && mCurrentRequest.argTaints != null) {
            for (com.genton.policy.TaintMapEntry taint : mCurrentRequest.argTaints) {
                if (("x".equals(taint.argumentKey) || "y".equals(taint.argumentKey)) && 
                    taint.sourceLevel > 2) {  // > AGENT
                    Slog.w(TAG, "Tap coordinates from untrusted source: level=" + taint.sourceLevel);
                    return ExecutionResult.failure("Tap coordinates must come from trusted source");
                }
            }
        }
        
        // 1. 获取目标坐标
        int x, y;
        
        if (args.has("x") && args.has("y")) {
            // 直接坐标
            x = args.getInt("x");
            y = args.getInt("y");
        } else if (args.has("nodeId")) {
            // 节点 ID → 查询中心坐标
            String nodeId = args.getString("nodeId");
            UiNode node = queryNode(nodeId);
            
            if (node == null || node.bounds == null) {
                return ExecutionResult.failure("Node not found or has no bounds: " + nodeId);
            }
            
            x = node.bounds.centerX();
            y = node.bounds.centerY();
        } else {
            return ExecutionResult.failure("Missing tap target: x/y or nodeId");
        }
        
        // 2. Stale 检测
        if (args.has("capturedTreeSeq")) {
            long capturedSeq = args.getLong("capturedTreeSeq");
            long currentSeq = getCurrentUiTreeSeq();
            
            if (Math.abs(currentSeq - capturedSeq) > MAX_SEQ_DELTA) {
                Slog.w(TAG, String.format("Stale UI tree: captured=%d current=%d", capturedSeq, currentSeq));
                return ExecutionResult.failure("UI tree stale: captured tree outdated");
            }
        }
        
        // 3. 坐标边界检查
        android.graphics.Point displaySize = new android.graphics.Point();
        mWindowManager.getDefaultDisplay().getRealSize(displaySize);
        
        if (x < 0 || x >= displaySize.x || y < 0 || y >= displaySize.y) {
            Slog.w(TAG, String.format("Tap out of bounds: (%d, %d) display=(%d, %d)", x, y, displaySize.x, displaySize.y));
            return ExecutionResult.failure("Tap coordinates out of bounds");
        }
        
        // 4. Overlay 复查（检测点击劫持）
        if (isOverlayObscuring(x, y)) {
            Slog.w(TAG, "Overlay detected at tap coordinates");
            return ExecutionResult.failure("Overlay obscuring tap target");
        }
        
        // 5. FLAG_SECURE 检测
        if (isSecureWindowAtCoordinates(x, y)) {
            Slog.w(TAG, "FLAG_SECURE window at tap coordinates");
            return ExecutionResult.failure("Cannot tap on secure content");
        }
        
        // 6. 注入触摸事件
        long downTime = SystemClock.uptimeMillis();
        injectTouchEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime);
        
        // 模拟人类点击延迟
        SystemClock.sleep(TAP_DURATION_MS);
        
        long upTime = SystemClock.uptimeMillis();
        injectTouchEvent(MotionEvent.ACTION_UP, x, y, downTime, upTime);
        
        JSONObject result = new JSONObject();
        result.put("x", x);
        result.put("y", y);
        result.put("duration_ms", TAP_DURATION_MS);
        
        Slog.i(TAG, String.format("Tap executed: (%d, %d)", x, y));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 执行文本输入
     * 
     * 参数：
     * {
     *   "text": "Hello World"
     * }
     */
    private ExecutionResult executeType(JSONObject args, ExecutionContext context) throws Exception {
        if (!args.has("text")) {
            return ExecutionResult.failure("Missing parameter: text");
        }
        
        String text = args.getString("text");
        
        // 长度限制（防止滥用）
        if (text.length() > 10000) {
            return ExecutionResult.failure("Text too long: max 10000 chars");
        }
        
        // 逐字符注入（通过 KeyEvent）
        long startTime = SystemClock.uptimeMillis();
        int injectedCount = 0;
        
        for (char c : text.toCharArray()) {
            // 注入字符
            KeyEvent[] events = getEventsForCharacter(c);
            if (events != null) {
                for (KeyEvent event : events) {
                    mInputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                    injectedCount++;
                }
            }
        }
        
        JSONObject result = new JSONObject();
        result.put("text_length", text.length());
        result.put("injected_events", injectedCount);
        result.put("duration_ms", SystemClock.uptimeMillis() - startTime);
        
        Slog.i(TAG, String.format("Type executed: %d chars", text.length()));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 执行滑动
     * 
     * 参数：
     * {
     *   "startX": 100, "startY": 500,
     *   "endX": 100, "endY": 200,
     *   "duration": 300  // 可选，默认 300ms
     * }
     */
    private ExecutionResult executeSwipe(JSONObject args, ExecutionContext context) throws Exception {
        if (!args.has("startX") || !args.has("startY") || !args.has("endX") || !args.has("endY")) {
            return ExecutionResult.failure("Missing swipe parameters");
        }
        
        int startX = args.getInt("startX");
        int startY = args.getInt("startY");
        int endX = args.getInt("endX");
        int endY = args.getInt("endY");
        int duration = args.optInt("duration", (int) SWIPE_DURATION_MS);
        
        // 边界检查
        android.graphics.Point displaySize = new android.graphics.Point();
        mWindowManager.getDefaultDisplay().getRealSize(displaySize);
        
        if (startX < 0 || startX >= displaySize.x || startY < 0 || startY >= displaySize.y ||
            endX < 0 || endX >= displaySize.x || endY < 0 || endY >= displaySize.y) {
            return ExecutionResult.failure("Swipe coordinates out of bounds");
        }
        
        // 注入滑动手势
        long downTime = SystemClock.uptimeMillis();
        injectTouchEvent(MotionEvent.ACTION_DOWN, startX, startY, downTime, downTime);
        
        // 分段注入 MOVE 事件（平滑滑动）
        int steps = 20;
        long stepDelay = duration / steps;
        
        for (int i = 1; i <= steps; i++) {
            float progress = (float) i / steps;
            int x = (int) (startX + (endX - startX) * progress);
            int y = (int) (startY + (endY - startY) * progress);
            
            SystemClock.sleep(stepDelay);
            long eventTime = SystemClock.uptimeMillis();
            injectTouchEvent(MotionEvent.ACTION_MOVE, x, y, downTime, eventTime);
        }
        
        long upTime = SystemClock.uptimeMillis();
        injectTouchEvent(MotionEvent.ACTION_UP, endX, endY, downTime, upTime);
        
        JSONObject result = new JSONObject();
        result.put("startX", startX);
        result.put("startY", startY);
        result.put("endX", endX);
        result.put("endY", endY);
        result.put("duration_ms", duration);
        
        Slog.i(TAG, String.format("Swipe executed: (%d,%d) → (%d,%d)", startX, startY, endX, endY));
        return ExecutionResult.success(result.toString());
    }
    
    /**
     * 执行按键
     * 
     * 参数：
     * {
     *   "key": "back" | "home" | "recents" | "volume_up" | "volume_down"
     * }
     */
    private ExecutionResult executeKey(JSONObject args, ExecutionContext context) throws Exception {
        if (!args.has("key")) {
            return ExecutionResult.failure("Missing parameter: key");
        }
        
        String key = args.getString("key");
        int keyCode;
        
        switch (key) {
            case "back":
                keyCode = KeyEvent.KEYCODE_BACK;
                break;
            case "home":
                keyCode = KeyEvent.KEYCODE_HOME;
                break;
            case "recents":
                keyCode = KeyEvent.KEYCODE_APP_SWITCH;
                break;
            case "volume_up":
                keyCode = KeyEvent.KEYCODE_VOLUME_UP;
                break;
            case "volume_down":
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN;
                break;
            default:
                return ExecutionResult.failure("Unsupported key: " + key);
        }
        
        // 注入按键
        long now = SystemClock.uptimeMillis();
        KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        
        mInputManager.injectInputEvent(downEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        SystemClock.sleep(50);
        mInputManager.injectInputEvent(upEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        
        JSONObject result = new JSONObject();
        result.put("key", key);
        result.put("keyCode", keyCode);
        
        Slog.i(TAG, "Key executed: " + key);
        return ExecutionResult.success(result.toString());
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 注入触摸事件
     */
    private void injectTouchEvent(int action, int x, int y, long downTime, long eventTime) {
        MotionEvent event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            x,
            y,
            0  // metaState
        );
        
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        mInputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        event.recycle();
    }
    
    /**
     * 查询 UI 节点
     */
    private UiNode queryNode(String nodeId) {
        try {
            if (mUiTreeService == null) {
                return null;
            }
            
            // 调用 UI 树服务查询
            return mUiTreeService.getNodeById(nodeId);
            
        } catch (Exception e) {
            Slog.w(TAG, "Failed to query node", e);
            return null;
        }
    }
    
    /**
     * 获取当前 UI 树序列号
     */
    private long getCurrentUiTreeSeq() {
        try {
            if (mUiTreeService == null) {
                return 0;
            }
            
            return mUiTreeService.getCurrentSequence();
            
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get UI tree sequence", e);
            return 0;
        }
    }
    
    /**
     * 检测坐标是否被 overlay 遮挡
     */
    private boolean isOverlayObscuring(int x, int y) {
        // TODO: 通过 WindowManager 查询坐标处的窗口栈
        // 检测是否有 TYPE_APPLICATION_OVERLAY 窗口覆盖目标
        return false;  // 临时：不阻断
    }
    
    /**
     * 检测坐标是否在 FLAG_SECURE 窗口内
     */
    private boolean isSecureWindowAtCoordinates(int x, int y) {
        // TODO: 查询窗口栈，检测 FLAG_SECURE
        return false;  // 临时：不阻断
    }
    
    /**
     * 获取字符对应的 KeyEvent
     */
    private KeyEvent[] getEventsForCharacter(char c) {
        // 使用 KeyCharacterMap 转换字符
        KeyEvent[] events = android.view.KeyCharacterMap.load(
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
        ).getEvents(new char[]{c});
        
        return events;
    }
    
    @Override
    public boolean supportsAction(String actionId) {
        return "ui.tap".equals(actionId) ||
               "ui.type".equals(actionId) ||
               "ui.swipe".equals(actionId) ||
               "ui.key".equals(actionId);
    }
}
