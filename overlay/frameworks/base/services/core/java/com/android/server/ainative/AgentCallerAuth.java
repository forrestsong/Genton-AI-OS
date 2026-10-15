/*
 * Copyright (C) 2026 Genton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.ainative;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.util.Slog;

/**
 * Agent 平台服务的调用方鉴权（WBS 1.3）
 *
 * 背景：审计发现约 200 个 Binder 方法中仅极少数做了调用方校验，
 * 且部分校验以注释形式存在（如 AgentScreenService.checkPermission() 返回恒 true）。
 * 敏感能力（截屏、UI 树、记忆、审计、策略）因此可被任意三方应用调用。
 *
 * 本类提供**单点鉴权**：在服务类的 onTransact() 里强制校验一次，
 * 从而覆盖该接口的全部方法（含未来新增方法），避免"逐方法补校验"必然漏项的问题。
 *
 * 权限模型：以下权限均为 signature|privileged 级（见 privapp-permissions 与
 * framework manifest 声明），因此"持有权限"等价于"平台签名或特权预置应用"。
 * 系统内部调用（system uid / 本进程）放行。
 *
 * 失败语义：拒绝时抛 SecurityException（Binder 标准行为），不做静默返回。
 */
public final class AgentCallerAuth {
    private static final String TAG = "AgentCallerAuth";

    // 服务绑定权限（均已在项目内声明为 signature|privileged）
    public static final String PERM_BIND_SCREEN  = "com.ainative.permission.BIND_SCREEN_SERVICE";
    public static final String PERM_BIND_UI_TREE = "com.ainative.permission.BIND_UI_TREE_SERVICE";
    public static final String PERM_BIND_POLICY  = "com.ainative.permission.BIND_POLICY_ENGINE";
    public static final String PERM_BIND_AUDIT   = "com.ainative.permission.BIND_AUDIT_SERVICE";
    public static final String PERM_BIND_MEMORY  = "com.ainative.permission.BIND_MEMORY_SERVICE";

    // 内容读取权限（更细粒度，供方法级校验使用）
    public static final String PERM_READ_SCREEN  = "com.ainative.permission.READ_SCREEN_CONTENT";
    public static final String PERM_READ_UI_TREE = "com.ainative.permission.READ_UI_TREE";

    // 用户确认回灌权限（专供确认 UI；Agent 大脑不持有 —— 见 WBS 1.5 防"确认自批"）
    public static final String PERM_SUBMIT_DECISION = "com.ainative.permission.SUBMIT_USER_DECISION";

    private AgentCallerAuth() {
        // 纯静态工具类
    }

    /** 是否为系统内部调用方（system uid 或本进程）。 */
    public static boolean isSystemCaller() {
        final int uid = Binder.getCallingUid();
        return uid == Process.SYSTEM_UID || uid == Process.myUid();
    }

    /** 调用方是否持有指定权限（不抛异常，供需要返回错误码的方法使用）。 */
    public static boolean holdsPermission(Context ctx, String permission) {
        if (isSystemCaller()) {
            return true;
        }
        return ctx.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 单点鉴权：对一次 Binder 事务强制要求权限。
     *
     * @param code 事务码；INTERFACE_TRANSACTION 放行（仅返回接口描述符，不含业务数据）
     */
    public static void enforceTransaction(Context ctx, String permission, String serviceName, int code) {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            return;
        }
        if (isSystemCaller()) {
            return;
        }
        try {
            ctx.enforceCallingPermission(permission, serviceName + " requires " + permission);
        } catch (SecurityException e) {
            Slog.w(TAG, "Denied transaction: service=" + serviceName + " code=" + code
                    + " uid=" + Binder.getCallingUid() + " pid=" + Binder.getCallingPid());
            throw e;
        }
    }
}
