/*
 * AI Native OS - Genton 系统服务注册入口（主线2 自动生成）
 *
 * 类↔接口映射来自 services/core/.../genton/*.java 的 `extends <Interface>.Stub`；
 * 构造参数顺序来自各服务类的 public 构造器签名（含依赖注入）。
 * 服务名与 system/sepolicy/private/service_contexts 一致。
 */
package com.android.server.genton;

import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Slog;

/** AI Native OS 服务注册表 */
public final class GentonServiceRegistry {
    private static final String TAG = "GentonServiceRegistry";

    private GentonServiceRegistry() {}

    /** 由 SystemServer 在 startOtherServices() 阶段调用 */
    public static void start(Context context) {
        Slog.i(TAG, "Registering AI Native OS services...");

        // 1) 共享依赖（供后续服务构造使用；按依赖顺序创建）
        final PolicyEngineService policyEngine = new PolicyEngineService(context);
        final AuditService auditService = new AuditService(context);
        final AgentAuditService agentAuditService = new AgentAuditService(context);
        final AgentAssetLibraryService assetLibrary = new AgentAssetLibraryService(context);
        final AgentUiTreeService uiTreeService = new AgentUiTreeService(context);
        final AgentScreenService screenService = new AgentScreenService(context);
        final ConfirmationHostService confirmationHost =
                new ConfirmationHostService(context, policyEngine, agentAuditService);
        final AgentMacroRecorderService recorderService =
                new AgentMacroRecorderService(context, uiTreeService, screenService);

        // 2) 注册对外服务
        int ok = 0;
        int fail = 0;

        if (reg("com.genton.policy.IPolicyEngine", () -> new PolicyEngineService(context))) { ok++; } else { fail++; }
        if (reg("com.genton.audit.IAuditService", () -> auditService)) { ok++; } else { fail++; }
        if (reg("com.genton.assets.IAgentAssetLibraryService", () -> assetLibrary)) { ok++; } else { fail++; }
        if (reg("com.genton.context.IAgentContextService", () -> new AgentContextService(context))) { ok++; } else { fail++; }
        if (reg("com.genton.inference.IAgentInferenceRouter", () -> new AgentInferenceRouterService(context))) { ok++; } else { fail++; }
        if (reg("com.genton.memory.IAgentMemoryService", () -> new AgentMemoryService(context))) { ok++; } else { fail++; }
        if (reg("com.genton.motion.IMotionHubService", () -> new MotionHubService(context))) { ok++; } else { fail++; }
        if (reg("com.genton.perception.IAgentScreenService", () -> screenService)) { ok++; } else { fail++; }
        if (reg("com.genton.perception.IAgentUiTreeService", () -> uiTreeService)) { ok++; } else { fail++; }
        if (reg("com.genton.semantics.IEntityQueryService", () -> new EntityQueryService(context))) { ok++; } else { fail++; }
        if (reg("com.genton.agent.IAgentRuntimeService", () -> new AgentRuntimeService(context, policyEngine))) { ok++; } else { fail++; }
        if (reg("com.genton.call.IAgentCallGateService", () -> new AgentCallGateService(context, policyEngine))) { ok++; } else { fail++; }
        if (reg("com.genton.execution.IActionExecutionService", () -> new ActionExecutionService(context, policyEngine, auditService))) { ok++; } else { fail++; }
        if (reg("com.genton.ui.IAgentConfirmationHost", () -> confirmationHost)) { ok++; } else { fail++; }
        if (reg("com.genton.macro.IAgentMacroRecorderService", () -> recorderService)) { ok++; } else { fail++; }
        if (reg("com.genton.macro.IMacroValidationService", () -> new AgentMacroValidationService(context, recorderService, assetLibrary))) { ok++; } else { fail++; }
        if (reg("com.genton.action.IAgentActionService", () -> new AgentActionService(context, policyEngine, confirmationHost, agentAuditService))) { ok++; } else { fail++; }

        Slog.i(TAG, "AI Native OS services registered: ok=" + ok + " fail=" + fail);
    }

    private interface Factory {
        IBinder create();
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
