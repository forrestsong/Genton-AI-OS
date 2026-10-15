/*
 * AI Native OS - Ainative 系统服务注册入口（主线2 自动生成）
 *
 * 类↔接口映射来自 services/core/.../ainative/*.java 的 `extends <Interface>.Stub`；
 * 构造参数顺序来自各服务类的 public 构造器签名（含依赖注入）。
 * 服务名与 system/sepolicy/private/service_contexts 一致。
 */
package com.android.server.ainative;

import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Slog;

/** AI Native OS 服务注册表 */
public final class AinativeServiceRegistry {
    private static final String TAG = "AinativeServiceRegistry";

    private AinativeServiceRegistry() {}

    /** 由 SystemServer 在 startOtherServices() 阶段调用 */
    public static void start(Context context) {
        Slog.i(TAG, "Registering AI Native OS services...");

        // 1) 共享依赖（供后续服务构造使用；按依赖顺序创建）
        final PolicyEngineService policyEngine = tryNew(() -> new PolicyEngineService(context));
        final AuditService auditService = tryNew(() -> new AuditService(context));
        final AgentAuditService agentAuditService = tryNew(() -> new AgentAuditService(context));
        final AgentAssetLibraryService assetLibrary = tryNew(() -> new AgentAssetLibraryService(context));
        final AgentUiTreeService uiTreeService = tryNew(() -> new AgentUiTreeService(context));
        final AgentScreenService screenService = tryNew(() -> new AgentScreenService(context));
        final ConfirmationHostService confirmationHost = tryNew(() -> new ConfirmationHostService(context, policyEngine, agentAuditService));
        final AgentMacroRecorderService recorderService = tryNew(() -> new AgentMacroRecorderService(context, uiTreeService, screenService));

        // 2) 注册对外服务
        int ok = 0;
        int fail = 0;

        if (reg("com.ainative.policy.IPolicyEngine", () -> new PolicyEngineService(context))) { ok++; } else { fail++; }
        if (reg("com.ainative.audit.IAuditService", () -> auditService)) { ok++; } else { fail++; }
        if (reg("com.ainative.assets.IAgentAssetLibraryService", () -> assetLibrary)) { ok++; } else { fail++; }
        if (reg("com.ainative.context.IAgentContextService", () -> new AgentContextService(context))) { ok++; } else { fail++; }
        if (reg("com.ainative.inference.IAgentInferenceRouter", () -> new AgentInferenceRouterService(context))) { ok++; } else { fail++; }
        if (reg("com.ainative.memory.IAgentMemoryService", () -> new AgentMemoryService(context))) { ok++; } else { fail++; }
        if (reg("com.ainative.motion.IMotionHubService", () -> new MotionHubService(context))) { ok++; } else { fail++; }
        if (reg("com.ainative.perception.IAgentScreenService", () -> screenService)) { ok++; } else { fail++; }
        if (reg("com.ainative.perception.IAgentUiTreeService", () -> uiTreeService)) { ok++; } else { fail++; }
        if (reg("com.ainative.semantics.IEntityQueryService", () -> new EntityQueryService(context))) { ok++; } else { fail++; }
        if (reg("com.ainative.agent.IAgentRuntimeService", () -> new AgentRuntimeService(context, policyEngine))) { ok++; } else { fail++; }
        if (reg("com.ainative.call.IAgentCallGateService", () -> new AgentCallGateService(context, policyEngine))) { ok++; } else { fail++; }
        if (reg("com.ainative.execution.IActionExecutionService", () -> new ActionExecutionService(context, policyEngine, auditService))) { ok++; } else { fail++; }
        if (reg("com.ainative.ui.IAgentConfirmationHost", () -> confirmationHost)) { ok++; } else { fail++; }
        if (reg("com.ainative.macro.IAgentMacroRecorderService", () -> recorderService)) { ok++; } else { fail++; }
        if (reg("com.ainative.macro.IMacroValidationService", () -> new AgentMacroValidationService(context, recorderService, assetLibrary))) { ok++; } else { fail++; }
        // [P5] 补齐 5 个未注册后端服务
        final AgentNotificationService notificationService =
                tryNew(() -> new AgentNotificationService(context));
        if (notificationService != null
                && regBinder("com.ainative.perception.IAgentNotificationService",
                             notificationService.getBinder())) { ok++; } else { fail++; }

        final AgentEventStreamService eventStreamService =
                tryNew(() -> new AgentEventStreamService(context));
        if (eventStreamService != null
                && regBinder("com.ainative.events.IAgentEventStreamService",
                             eventStreamService.getBinder())) { ok++; } else { fail++; }

        if (reg("com.ainative.action.IAgentQueryService",
                () -> new AgentQueryService(context))) { ok++; } else { fail++; }

        if (reg("com.ainative.sandbox.IAgentSandboxManager",
                () -> new AgentSandboxManagerService(context))) { ok++; } else { fail++; }

        if (reg("com.ainative.sandbox.IAgentSandboxGateway",
                () -> new AgentSandboxGatewayService(context))) { ok++; } else { fail++; }

        if (reg("com.ainative.action.IAgentActionService", () -> new AgentActionService(context, policyEngine, confirmationHost, agentAuditService))) { ok++; } else { fail++; }
        if (reg("com.ainative.ui.IAgentAssistantUi", () -> new AgentAssistantUiService(context))) { ok++; } else { fail++; }

        Slog.i(TAG, "AI Native OS services registered: ok=" + ok + " fail=" + fail);
    }


    /** 构造失败时记日志并返回 null（避免单个服务/依赖失败中断整个注册） */
    private interface Supplier<T> {
        T get();
    }

    private static <T> T tryNew(Supplier<T> s) {
        try {
            return s.get();
        } catch (Throwable t) {
            Slog.e(TAG, "construction failed", t);
            return null;
        }
    }

    private interface Factory {
        IBinder create();
    }

    /** [P5] 以给定 binder 注册服务（用于 SystemService 子类暴露的 binder） */
    private static boolean regBinder(String name, android.os.IBinder binder) {
        if (binder == null) {
            Slog.w(TAG, "skip null binder: " + name);
            return false;
        }
        try {
            android.os.ServiceManager.addService(name, binder);
            Slog.i(TAG, "registered " + name);
            return true;
        } catch (Throwable t) {
            Slog.e(TAG, "failed to register " + name, t);
            return false;
        }
    }

    private static boolean reg(String name, Factory f) {
        try {
            IBinder binder = f.create();
            if (binder == null) {
                Slog.w(TAG, "service factory returned null: " + name);
                return false;
            }
            ServiceManager.addService(name, binder);
            Slog.i(TAG, "registered " + name);
            return true;
        } catch (Throwable t) {
            Slog.e(TAG, "failed to register " + name, t);
            return false;
        }
    }
}
