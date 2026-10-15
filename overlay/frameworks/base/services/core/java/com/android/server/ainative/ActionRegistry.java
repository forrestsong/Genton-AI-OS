package com.android.server.ainative;

import com.ainative.execution.ActionInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Context;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作注册表管理器
 * 
 * 从 /vendor/ainative/config/action_registry.json 加载动作定义。
 */
public class ActionRegistry {
    private static final String TAG = "ActionRegistry";
    private static final String REGISTRY_PATH = "/vendor/ainative/config/action_registry.json";
    
    private final Map<String, ActionInfo> mActions = new HashMap<>();
    
    public ActionRegistry(Context context) {
        loadRegistry();
    }
    
    private void loadRegistry() {
        try {
            File registryFile = new File(REGISTRY_PATH);
            if (!registryFile.exists()) {
                Slog.w(TAG, "Action registry not found at " + REGISTRY_PATH + ", using defaults");
                loadDefaultActions();
                return;
            }
            
            String content = new String(Files.readAllBytes(registryFile.toPath()));
            JSONObject root = new JSONObject(content);
            JSONArray actions = root.getJSONArray("actions");
            
            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.getJSONObject(i);
                ActionInfo info = parseActionInfo(action);
                mActions.put(info.actionId, info);
            }
            
            Slog.i(TAG, "Loaded " + mActions.size() + " actions from registry");
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to load action registry", e);
            loadDefaultActions();
        }
    }
    
    private ActionInfo parseActionInfo(JSONObject json) throws Exception {
        ActionInfo info = new ActionInfo();
        info.actionId = json.getString("name");
        info.displayName = json.getString("displayName");
        info.description = json.optString("description", "");
        info.executorType = json.getString("executorId");
        info.requiredCapability = json.getString("requiredCapability");
        info.riskLevel = json.getInt("riskLevel");
        return info;
    }
    
    private void loadDefaultActions() {
        // 默认动作（存根）
        ActionInfo tapAction = new ActionInfo();
        tapAction.actionId = "ui.tap";
        tapAction.displayName = "点击界面元素";
        tapAction.executorType = "input";
        tapAction.requiredCapability = "agent.input_inject";
        tapAction.riskLevel = 1;
        mActions.put("ui.tap", tapAction);
        
        Slog.i(TAG, "Loaded " + mActions.size() + " default actions");
    }
    
    public ActionInfo getActionInfo(String actionId) {
        return mActions.get(actionId);
    }
    
    public String[] listActionIds() {
        return mActions.keySet().toArray(new String[0]);
    }
    
    public boolean isRegistered(String actionId) {
        return mActions.containsKey(actionId);
    }
}
