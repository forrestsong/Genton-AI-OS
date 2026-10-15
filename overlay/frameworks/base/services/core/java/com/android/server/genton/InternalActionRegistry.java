package com.android.server.genton;

import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 内部动作注册表
 * 
 * 核心职责：
 * 1. 注册系统内部动作（sys.* / cmd.*）
 * 2. 内部动作不需要策略引擎授权（系统内部调用）
 * 3. 支持预热/渲染策略/日程准备等系统优化动作
 * 4. 支持测试/调试命令动作
 * 
 * 内部动作分类：
 * - sys.prewarm_*：应用预热（提升启动速度）
 * - sys.render_*：渲染策略（降采样/帧率控制）
 * - sys.schedule_*：日程准备（提前加载数据）
 * - sys.policy.*：策略操作（激活建议/重载规则）
 * - sys.camera.*：相机准备（QA 模式）
 * - sys.audio.*：音频操作（录制备忘）
 * - cmd.*：命令/测试动作
 * 
 * 安全约束：
 * - 仅允许系统内部调用（不接受外部请求）
 * - 不记录审计日志（内部优化动作）
 * - 不消耗 Agent 预算
 * 
 * @see ActionRegistry
 */
public class InternalActionRegistry {
    private static final String TAG = "InternalActionRegistry";
    
    // 内部动作前缀
    private static final String PREFIX_SYS = "sys.";
    private static final String PREFIX_CMD = "cmd.";
    
    // 内部动作定义
    private final Map<String, InternalActionDefinition> mInternalActions = new HashMap<>();
    
    /**
     * 内部动作定义
     */
    public static class InternalActionDefinition {
        public String actionId;
        public String category;  // prewarm/render/schedule/policy/camera/audio/cmd
        public String description;
        public String executorType;  // 执行器类型（可选，null=内部处理）
        public boolean auditEnabled;  // 是否记录审计（默认 false）
        public boolean budgetConsumed;  // 是否消耗预算（默认 false）
        public JSONObject argumentsSchema;  // 参数 schema（JSON Schema）
        
        public InternalActionDefinition(String actionId, String category, String description) {
            this.actionId = actionId;
            this.category = category;
            this.description = description;
            this.executorType = null;
            this.auditEnabled = false;
            this.budgetConsumed = false;
            this.argumentsSchema = new JSONObject();
        }
        
        public InternalActionDefinition withExecutor(String executorType) {
            this.executorType = executorType;
            return this;
        }
        
        public InternalActionDefinition withAudit(boolean enabled) {
            this.auditEnabled = enabled;
            return this;
        }
        
        public InternalActionDefinition withBudget(boolean consumed) {
            this.budgetConsumed = consumed;
            return this;
        }
        
        public InternalActionDefinition withSchema(JSONObject schema) {
            this.argumentsSchema = schema;
            return this;
        }
    }
    
    public InternalActionRegistry() {
        registerInternalActions();
        Slog.i(TAG, "InternalActionRegistry initialized: " + mInternalActions.size() + " actions");
    }
    
    /**
     * 注册所有内部动作
     */
    private void registerInternalActions() {
        // ========== 预热动作 (prewarm) ==========
        
        register(new InternalActionDefinition(
            "sys.prewarm_app",
            "prewarm",
            "预热应用进程（提前启动，加速后续启动）"
        ).withExecutor("activity").withSchema(schemaPrewarmApp()));
        
        register(new InternalActionDefinition(
            "sys.prewarm_model",
            "prewarm",
            "预热 AI 模型（加载到内存，加速推理）"
        ).withSchema(schemaPrewarmModel()));
        
        // ========== 渲染策略 (render) ==========
        
        register(new InternalActionDefinition(
            "sys.set_render_policy",
            "render",
            "设置渲染策略（降采样/帧率控制）"
        ).withSchema(schemaSetRenderPolicy()));
        
        register(new InternalActionDefinition(
            "sys.set_animation_duration",
            "render",
            "设置动画时长（0=禁用动画，加速操作）"
        ).withExecutor("device_control").withSchema(schemaSetAnimationDuration()));
        
        register(new InternalActionDefinition(
            "sys.set_animation_zero",
            "render",
            "动画置零模式（任务期间禁用所有动画，任务结束后恢复）"
        ).withExecutor("internal").withSchema(schemaSetAnimationZero()));
        
        // ========== 日程准备 (schedule) ==========
        
        register(new InternalActionDefinition(
            "sys.schedule_prep",
            "schedule",
            "日程准备（提前加载日程数据）"
        ).withSchema(schemaSchedulePrep()));
        
        register(new InternalActionDefinition(
            "sys.schedule_reminder",
            "schedule",
            "日程提醒（发送提前提醒通知）"
        ).withExecutor("notification").withSchema(schemaScheduleReminder()));
        
        // ========== 策略操作 (policy) ==========
        
        register(new InternalActionDefinition(
            "sys.policy.activate_suggestion",
            "policy",
            "激活策略建议（主动推荐动作）"
        ).withAudit(true).withSchema(schemaPolicyActivateSuggestion()));
        
        register(new InternalActionDefinition(
            "sys.policy.reload_rules",
            "policy",
            "重载策略规则（热更新）"
        ).withAudit(true).withSchema(schemaPolicyReloadRules()));
        
        // ========== 相机准备 (camera) ==========
        
        register(new InternalActionDefinition(
            "sys.camera.prepare_qa",
            "camera",
            "相机 QA 模式准备（视觉问答预加载）"
        ).withSchema(schemaCameraPrepareQa()));
        
        // ========== 音频操作 (audio) ==========
        
        register(new InternalActionDefinition(
            "sys.audio.record_memo",
            "audio",
            "录制语音备忘（后台录音）"
        ).withSchema(schemaAudioRecordMemo()));
        
        // ========== 命令/测试 (cmd) ==========
        
        register(new InternalActionDefinition(
            "cmd.run_harness",
            "cmd",
            "运行测试套件（自动化测试）"
        ).withAudit(true).withSchema(schemaCmdRunHarness()));
        
        register(new InternalActionDefinition(
            "cmd.dump_state",
            "cmd",
            "转储系统状态（调试）"
        ).withSchema(schemaCmdDumpState()));
        
        register(new InternalActionDefinition(
            "cmd.inject_event",
            "cmd",
            "注入测试事件（模拟事件）"
        ).withSchema(schemaCmdInjectEvent()));
    }
    
    /**
     * 注册内部动作
     */
    private void register(InternalActionDefinition action) {
        mInternalActions.put(action.actionId, action);
    }
    
    /**
     * 查询内部动作
     */
    public InternalActionDefinition get(String actionId) {
        return mInternalActions.get(actionId);
    }
    
    /**
     * 检查是否为内部动作
     *
     * 白名单唯一来源：注册表本身。
     * 安全红线：不得按 actionId 前缀（sys./cmd.）判定——前缀由调用方自填，
     * 用它做身份判定等于把"内部动作"身份交给调用方自称（WBS 1.1）。
     */
    public boolean isInternalAction(String actionId) {
        return actionId != null && mInternalActions.containsKey(actionId);
    }
    
    /**
     * 获取所有内部动作
     */
    public Map<String, InternalActionDefinition> getAllInternalActions() {
        return new HashMap<>(mInternalActions);
    }
    
    // ========== Schema 定义 ==========
    
    private JSONObject schemaPrewarmApp() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("packageName", new JSONObject()
                        .put("type", "string")
                        .put("description", "应用包名"))
                    .put("delay", new JSONObject()
                        .put("type", "integer")
                        .put("description", "延迟启动（毫秒）")
                        .put("default", 0)))
                .put("required", new JSONArray().put("packageName"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaPrewarmModel() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("modelId", new JSONObject()
                        .put("type", "string")
                        .put("description", "模型 ID"))
                    .put("priority", new JSONObject()
                        .put("type", "string")
                        .put("enum", new JSONArray().put("high").put("normal").put("low"))
                        .put("default", "normal")))
                .put("required", new JSONArray().put("modelId"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaSetRenderPolicy() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("policy", new JSONObject()
                        .put("type", "string")
                        .put("enum", new JSONArray().put("full_quality").put("balanced").put("performance"))
                        .put("description", "渲染策略"))
                    .put("maxFrameRate", new JSONObject()
                        .put("type", "integer")
                        .put("description", "最大帧率（fps）")
                        .put("default", 60)))
                .put("required", new JSONArray().put("policy"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaSetAnimationDuration() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("durationScale", new JSONObject()
                        .put("type", "number")
                        .put("description", "动画时长比例（0=禁用，1=正常）")
                        .put("minimum", 0)
                        .put("maximum", 10)))
                .put("required", new JSONArray().put("durationScale"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaSetAnimationZero() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("enable", new JSONObject()
                        .put("type", "boolean")
                        .put("description", "启用动画置零模式（true=禁用动画，false=恢复动画）")
                        .put("default", true)))
                .put("required", new JSONArray());
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaSchedulePrep() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("timeRange", new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                            .put("start", new JSONObject().put("type", "integer").put("description", "开始时间戳"))
                            .put("end", new JSONObject().put("type", "integer").put("description", "结束时间戳"))))
                    .put("prefetch", new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否预取数据")
                        .put("default", true)))
                .put("required", new JSONArray().put("timeRange"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaScheduleReminder() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("eventId", new JSONObject()
                        .put("type", "string")
                        .put("description", "日程事件 ID"))
                    .put("advanceMinutes", new JSONObject()
                        .put("type", "integer")
                        .put("description", "提前提醒分钟数")
                        .put("default", 15)))
                .put("required", new JSONArray().put("eventId"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaPolicyActivateSuggestion() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("suggestionId", new JSONObject()
                        .put("type", "string")
                        .put("description", "建议 ID"))
                    .put("reason", new JSONObject()
                        .put("type", "string")
                        .put("description", "激活原因")))
                .put("required", new JSONArray().put("suggestionId").put("reason"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaPolicyReloadRules() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("ruleSetPath", new JSONObject()
                        .put("type", "string")
                        .put("description", "规则集文件路径"))
                    .put("verifySignature", new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否验证签名")
                        .put("default", true)))
                .put("required", new JSONArray().put("ruleSetPath"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaCameraPrepareQa() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("mode", new JSONObject()
                        .put("type", "string")
                        .put("enum", new JSONArray().put("ocr").put("object_detection").put("scene_understanding"))
                        .put("description", "QA 模式")))
                .put("required", new JSONArray().put("mode"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaAudioRecordMemo() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("maxDurationSec", new JSONObject()
                        .put("type", "integer")
                        .put("description", "最大录制时长（秒）")
                        .put("default", 60))
                    .put("autoTranscribe", new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否自动转写")
                        .put("default", true)))
                .put("required", new JSONArray());
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaCmdRunHarness() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("testSuite", new JSONObject()
                        .put("type", "string")
                        .put("description", "测试套件名称"))
                    .put("parallel", new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否并行执行")
                        .put("default", false)))
                .put("required", new JSONArray().put("testSuite"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaCmdDumpState() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("components", new JSONObject()
                        .put("type", "array")
                        .put("items", new JSONObject().put("type", "string"))
                        .put("description", "要转储的组件列表")))
                .put("required", new JSONArray());
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    private JSONObject schemaCmdInjectEvent() {
        try {
            return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                    .put("eventType", new JSONObject()
                        .put("type", "string")
                        .put("description", "事件类型"))
                    .put("payload", new JSONObject()
                        .put("type", "object")
                        .put("description", "事件载荷")))
                .put("required", new JSONArray().put("eventType").put("payload"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
