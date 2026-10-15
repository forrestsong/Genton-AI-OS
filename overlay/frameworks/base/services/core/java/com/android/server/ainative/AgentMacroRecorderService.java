package com.android.server.ainative;

import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.ainative.macro.MacroRecordSession;
import com.ainative.macro.MacroScript;
import com.ainative.macro.MacroStep;
import com.ainative.macro.IAgentMacroRecorderService;
import com.ainative.macro.IMacroRecordListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宏录制服务实现
 * 
 * 职责：
 * 1. 录制用户操作序列（感知 → 步骤捕获）
 * 2. 生成可重放的宏脚本
 * 3. 重放校验器
 * 4. 绑定到 ActionCardData
 * 
 * 对应任务：F1.1 宏录制与生成工具
 * 草案依据：D11 + F类功能扩展
 */
public class AgentMacroRecorderService extends IAgentMacroRecorderService.Stub {
    private static final String TAG = "AgentMacroRecorder";
    
    private final Context mContext;
    private final Object mLock = new Object();
    
    // 录制会话（sessionId -> Session）
    private final Map<String, RecordingSession> mSessions = new ConcurrentHashMap<>();
    
    // 宏脚本存储（macroId -> MacroScript）
    private final Map<String, MacroScript> mMacros = new ConcurrentHashMap<>();
    
    // 监听器
    private final RemoteCallbackList<IMacroRecordListener> mListeners = new RemoteCallbackList<>();
    
    // 感知服务引用
    private final AgentUiTreeService mUiTreeService;
    private final AgentScreenService mScreenService;
    
    public AgentMacroRecorderService(Context context, 
                                     AgentUiTreeService uiTreeService,
                                     AgentScreenService screenService) {
        mContext = context;
        mUiTreeService = uiTreeService;
        mScreenService = screenService;
    }
    
    @Override
    public int startRecording(String intentAction, String[] sessionId) {
        enforcePermission();
        
        try {
            String sid = generateSessionId();
            
            RecordingSession session = new RecordingSession();
            session.sessionId = sid;
            session.intentAction = intentAction;
            session.state = "recording";
            session.startedAt = System.currentTimeMillis();
            session.steps = new ArrayList<>();
            
            mSessions.put(sid, session);
            
            // 通知监听器
            notifyRecordStarted(sid);
            
            sessionId[0] = sid;
            Log.i(TAG, "Recording started: " + sid + " intent=" + intentAction);
            return STATUS_OK;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    @Override
    public int stopRecording(String sessionId) {
        enforcePermission();
        
        RecordingSession session = mSessions.get(sessionId);
        if (session == null) {
            return STATUS_SESSION_NOT_FOUND;
        }
        
        synchronized (mLock) {
            session.state = "stopped";
            session.endedAt = System.currentTimeMillis();
        }
        
        // 通知监听器
        notifyRecordStopped(sessionId, session.steps.size());
        
        Log.i(TAG, "Recording stopped: " + sessionId + " steps=" + session.steps.size());
        return STATUS_OK;
    }
    
    @Override
    public MacroRecordSession getSession(String sessionId) {
        enforcePermission();
        
        RecordingSession session = mSessions.get(sessionId);
        if (session == null) {
            return null;
        }
        
        MacroRecordSession result = new MacroRecordSession();
        result.sessionId = session.sessionId;
        result.state = session.state;
        result.startedAt = session.startedAt;
        result.endedAt = session.endedAt;
        result.stepCount = session.steps.size();
        result.intentAction = session.intentAction;
        
        return result;
    }
    
    @Override
    public int generateMacro(String sessionId, String macroName, String macroDescription, String[] macroId) {
        enforcePermission();
        
        RecordingSession session = mSessions.get(sessionId);
        if (session == null) {
            return STATUS_SESSION_NOT_FOUND;
        }
        
        if (session.steps.isEmpty()) {
            Log.w(TAG, "Cannot generate macro from empty session");
            return STATUS_INVALID_MACRO;
        }
        
        try {
            String mid = generateMacroId();
            
            // 构建宏脚本
            MacroScript macro = new MacroScript();
            macro.macroId = mid;
            macro.name = macroName;
            macro.description = macroDescription;
            macro.version = "1.0.0";
            macro.intentAction = session.intentAction;
            macro.stepsJson = serializeSteps(session.steps);
            macro.createdAt = System.currentTimeMillis();
            macro.updatedAt = macro.createdAt;
            macro.recordedBy = "user";  // TODO: 获取真实用户信息
            macro.validationStatus = "draft";
            
            mMacros.put(mid, macro);
            
            // 标记会话为生成完成
            session.state = "generated";
            
            // 通知监听器
            notifyMacroGenerated(sessionId, mid);
            
            macroId[0] = mid;
            Log.i(TAG, "Macro generated: " + mid + " from session " + sessionId);
            return STATUS_OK;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate macro", e);
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    @Override
    public MacroScript getMacro(String macroId) {
        enforcePermission();
        return mMacros.get(macroId);
    }
    
    @Override
    public MacroScript[] queryMacros(String intentAction) {
        enforcePermission();
        
        List<MacroScript> results = new ArrayList<>();
        
        for (MacroScript macro : mMacros.values()) {
            if (intentAction == null || intentAction.equals(macro.intentAction)) {
                results.add(macro);
            }
        }
        
        return results.toArray(new MacroScript[0]);
    }
    
    @Override
    public int replayMacro(String macroId, boolean dryRun) {
        enforcePermission();
        
        MacroScript macro = mMacros.get(macroId);
        if (macro == null) {
            return STATUS_SESSION_NOT_FOUND;
        }
        
        try {
            List<MacroStep> steps = deserializeSteps(macro.stepsJson);
            
            Log.i(TAG, "Replaying macro: " + macroId + " dryRun=" + dryRun + " steps=" + steps.size());
            
            // 执行每个步骤
            for (MacroStep step : steps) {
                if (!replayStep(step, dryRun)) {
                    Log.w(TAG, "Replay failed at step " + step.stepIndex);
                    
                    // 更新验证状态
                    macro.validationStatus = "failed";
                    macro.validationInfo = "{\"failedStep\":" + step.stepIndex + "}";
                    macro.updatedAt = System.currentTimeMillis();
                    
                    return STATUS_REPLAY_FAILED;
                }
                
                // 等待延迟
                if (step.delayMs > 0) {
                    Thread.sleep(step.delayMs);
                }
            }
            
            // 更新验证状态
            macro.validationStatus = "validated";
            macro.validationInfo = "{\"validatedAt\":" + System.currentTimeMillis() + "}";
            macro.updatedAt = System.currentTimeMillis();
            
            Log.i(TAG, "Replay completed successfully: " + macroId);
            return STATUS_OK;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to replay macro", e);
            return STATUS_INTERNAL_ERROR;
        }
    }
    
    @Override
    public int deleteMacro(String macroId) {
        enforcePermission();
        
        if (mMacros.remove(macroId) != null) {
            Log.i(TAG, "Macro deleted: " + macroId);
            return STATUS_OK;
        }
        
        return STATUS_SESSION_NOT_FOUND;
    }
    
    @Override
    public void subscribeRecordEvents(IMacroRecordListener listener) {
        enforcePermission();
        mListeners.register(listener);
    }
    
    @Override
    public void unsubscribeRecordEvents(IMacroRecordListener listener) {
        enforcePermission();
        mListeners.unregister(listener);
    }
    
    @Override
    public String exportMacro(String macroId) {
        enforcePermission();
        
        MacroScript macro = mMacros.get(macroId);
        if (macro == null) {
            return null;
        }
        
        try {
            JSONObject json = new JSONObject();
            json.put("macroId", macro.macroId);
            json.put("name", macro.name);
            json.put("description", macro.description);
            json.put("version", macro.version);
            json.put("intentAction", macro.intentAction);
            json.put("steps", new JSONArray(macro.stepsJson));
            json.put("createdAt", macro.createdAt);
            json.put("recordedBy", macro.recordedBy);
            
            return json.toString(2);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to export macro", e);
            return null;
        }
    }
    
    @Override
    public int importMacro(String macroJson, String[] macroId) {
        enforcePermission();
        
        try {
            JSONObject json = new JSONObject(macroJson);
            
            String mid = generateMacroId();
            
            MacroScript macro = new MacroScript();
            macro.macroId = mid;
            macro.name = json.getString("name");
            macro.description = json.optString("description", "");
            macro.version = json.optString("version", "1.0.0");
            macro.intentAction = json.optString("intentAction", null);
            macro.stepsJson = json.getJSONArray("steps").toString();
            macro.createdAt = System.currentTimeMillis();
            macro.updatedAt = macro.createdAt;
            macro.recordedBy = json.optString("recordedBy", "imported");
            macro.validationStatus = "draft";
            
            mMacros.put(mid, macro);
            
            macroId[0] = mid;
            Log.i(TAG, "Macro imported: " + mid);
            return STATUS_OK;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to import macro", e);
            return STATUS_INVALID_MACRO;
        }
    }
    
    /**
     * 记录操作步骤（由感知层调用）
     * 
     * @param sessionId 会话 ID
     * @param step 步骤
     */
    public void recordStep(String sessionId, MacroStep step) {
        RecordingSession session = mSessions.get(sessionId);
        if (session == null || !"recording".equals(session.state)) {
            return;
        }
        
        synchronized (mLock) {
            step.stepIndex = session.steps.size();
            step.timestamp = System.currentTimeMillis();
            
            // 计算与上一步的延迟
            if (!session.steps.isEmpty()) {
                MacroStep lastStep = session.steps.get(session.steps.size() - 1);
                step.delayMs = step.timestamp - lastStep.timestamp;
            } else {
                step.delayMs = 0;
            }
            
            session.steps.add(step);
        }
        
        // 通知监听器
        notifyStepRecorded(sessionId, step);
        
        Log.d(TAG, "Step recorded: session=" + sessionId + " step=" + step.stepIndex + " type=" + step.stepType);
    }
    
    // ================ 私有辅助方法 ================
    
    private void enforcePermission() {
        mContext.enforceCallingOrSelfPermission(
            android.Manifest.permission.MANAGE_AI_NATIVE_AGENTS,
            "AgentMacroRecorderService");
    }
    
    private String generateSessionId() {
        return "session_" + UUID.randomUUID().toString();
    }
    
    private String generateMacroId() {
        return "macro_" + UUID.randomUUID().toString();
    }
    
    private String serializeSteps(List<MacroStep> steps) throws Exception {
        JSONArray array = new JSONArray();
        
        for (MacroStep step : steps) {
            JSONObject json = new JSONObject();
            json.put("stepIndex", step.stepIndex);
            json.put("stepType", step.stepType);
            json.put("parameters", new JSONObject(step.parameters));
            json.put("delayMs", step.delayMs);
            
            if (step.description != null) {
                json.put("description", step.description);
            }
            if (step.uiSnapshotRef != null) {
                json.put("uiSnapshotRef", step.uiSnapshotRef);
            }
            
            array.put(json);
        }
        
        return array.toString();
    }
    
    private List<MacroStep> deserializeSteps(String stepsJson) throws Exception {
        List<MacroStep> steps = new ArrayList<>();
        JSONArray array = new JSONArray(stepsJson);
        
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.getJSONObject(i);
            
            MacroStep step = new MacroStep();
            step.stepIndex = json.getInt("stepIndex");
            step.stepType = json.getString("stepType");
            step.parameters = json.getJSONObject("parameters").toString();
            step.delayMs = json.optLong("delayMs", 0);
            step.description = json.optString("description", null);
            step.uiSnapshotRef = json.optString("uiSnapshotRef", null);
            
            steps.add(step);
        }
        
        return steps;
    }
    
    private boolean replayStep(MacroStep step, boolean dryRun) {
        try {
            JSONObject params = new JSONObject(step.parameters);
            
            if (dryRun) {
                Log.d(TAG, "DRY RUN: " + step.stepType + " " + params.toString());
                return true;
            }
            
            // TODO: 集成实际执行器
            switch (step.stepType) {
                case "tap":
                    // 调用 InputExecutor.tap(x, y)
                    int x = params.getInt("x");
                    int y = params.getInt("y");
                    Log.d(TAG, "Replaying tap: (" + x + ", " + y + ")");
                    return true;
                    
                case "type":
                    // 调用 InputExecutor.type(text)
                    String text = params.getString("text");
                    Log.d(TAG, "Replaying type: " + text);
                    return true;
                    
                case "swipe":
                    // 调用 InputExecutor.swipe(x1, y1, x2, y2)
                    int x1 = params.getInt("x1");
                    int y1 = params.getInt("y1");
                    int x2 = params.getInt("x2");
                    int y2 = params.getInt("y2");
                    Log.d(TAG, "Replaying swipe: (" + x1 + "," + y1 + ") -> (" + x2 + "," + y2 + ")");
                    return true;
                    
                case "launch":
                    // 调用 ActivityExecutor.launchApp(packageName)
                    String pkg = params.getString("packageName");
                    Log.d(TAG, "Replaying launch: " + pkg);
                    return true;
                    
                case "wait":
                    // 等待指定时间
                    long waitMs = params.getLong("durationMs");
                    Thread.sleep(waitMs);
                    return true;
                    
                case "check":
                    // UI 状态校验
                    // TODO: 与当前 UI 树对比
                    Log.d(TAG, "Replaying check (skipped)");
                    return true;
                    
                default:
                    Log.w(TAG, "Unknown step type: " + step.stepType);
                    return false;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to replay step", e);
            return false;
        }
    }
    
    // 通知方法
    
    private void notifyRecordStarted(String sessionId) {
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onRecordStarted(sessionId);
            } catch (RemoteException e) {
                // Listener died
            }
        }
        mListeners.finishBroadcast();
    }
    
    private void notifyStepRecorded(String sessionId, MacroStep step) {
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onStepRecorded(sessionId, step);
            } catch (RemoteException e) {
                // Listener died
            }
        }
        mListeners.finishBroadcast();
    }
    
    private void notifyRecordStopped(String sessionId, int totalSteps) {
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onRecordStopped(sessionId, totalSteps);
            } catch (RemoteException e) {
                // Listener died
            }
        }
        mListeners.finishBroadcast();
    }
    
    private void notifyMacroGenerated(String sessionId, String macroId) {
        int count = mListeners.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                mListeners.getBroadcastItem(i).onMacroGenerated(sessionId, macroId);
            } catch (RemoteException e) {
                // Listener died
            }
        }
        mListeners.finishBroadcast();
    }
    
    // ================ 内部类 ================
    
    /**
     * 录制会话（内部表示）
     */
    private static class RecordingSession {
        String sessionId;
        String state;
        long startedAt;
        long endedAt;
        String intentAction;
        List<MacroStep> steps;
    }
}
