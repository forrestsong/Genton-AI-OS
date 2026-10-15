# AI Native OS - SELinux 策略集成指南

## 📋 概述

本目录包含 AI Native OS 的 SELinux 强制访问控制 (MAC) 策略，确保系统服务的安全隔离与权限最小化。

## 📁 文件列表

| 文件 | 功能 | 行数 |
|------|------|------|
| [genton.te](genton.te) | 核心域定义（6 个服务域 + 15 个服务类型） | 220 |
| [system_server.te](system_server.te) | SystemServer 扩展策略 | 45 |
| [init.te](init.te) | Init 进程启动策略 | 30 |
| [attributes](attributes) | 安全属性定义 | 15 |
| [service_contexts](service_contexts) | 服务名称到类型映射 | 25 |
| [file_contexts](file_contexts) | 文件路径到类型映射 | 50 |

**总计**: ~385 行 SELinux 策略代码

## 🏗️ 域架构

### 服务域层级

```
genton_domain (抽象属性)
├── genton_service        # 通用服务域
├── genton_audit          # 审计服务域
├── genton_policy         # 策略引擎域
├── genton_inference      # 推理路由域
├── genton_call           # 通话门控域
└── genton_motion         # 姿态交互域

genton_brain_app          # 大脑特权应用域
```

### 数据文件类型

```
genton_data_file_type (抽象属性)
├── genton_audit_data_file        # 审计日志（哈希链）
├── genton_policy_data_file       # 策略缓存
└── genton_brain_app_data_file    # 大脑 App 数据
```

### 服务类型（15 个）

```
genton_service_type (抽象属性)
├── genton_perception_service     # 感知服务
├── genton_action_service         # 动作执行
├── genton_agent_service          # Agent 运行时
├── genton_memory_service         # 记忆服务
├── genton_audit_service          # 审计服务
├── genton_semantics_service      # 语义服务
├── genton_events_service         # 事件流
├── genton_context_service        # 上下文服务
├── genton_policy_service         # 策略引擎
├── genton_assets_service         # 资产目录
├── genton_ui_service             # UI 契约
├── genton_inference_service      # 推理路由
├── genton_sandbox_service        # 沙箱执行
├── genton_call_service           # 通话门控
└── genton_motion_service         # 姿态交互
```

## 🔒 安全策略

### 1. 服务隔离

每个服务运行在独立的 SELinux 域中：

```
genton_audit    ← 审计服务（独立域）
genton_policy   ← 策略引擎（独立域）
genton_inference← 推理路由（独立域）
...
```

### 2. 最小权限原则

每个域仅授予完成任务所需的最小权限：

| 域 | 关键权限 | 限制 |
|------|----------|------|
| **genton_audit** | 写审计日志 | 仅自己读取 |
| **genton_policy** | 读 DSL 规则 | 禁止写入 |
| **genton_inference** | NPU 设备访问 | 仅推理使用 |
| **genton_call** | Telephony 集成 | 紧急号码白名单 |
| **genton_motion** | 传感器访问 | 唯一消费者 |

### 3. 第三方应用隔离

```te
# 禁止第三方应用访问 AI Native OS 服务
neverallow { appdomain -genton_brain_app -system_app -priv_app } 
           genton_service_type:service_manager find;
```

**允许访问的域**:
- `genton_brain_app` - 大脑特权 App
- `system_app` - 系统应用
- `priv_app` - 特权应用

**禁止访问**:
- `untrusted_app` - 第三方应用
- `isolated_app` - 隔离应用

### 4. 数据保护

```te
# 禁止非特权域读取审计日志
neverallow { domain -genton_audit -system_server } 
           genton_audit_data_file:file read;

# 禁止非策略引擎修改 DSL 规则
neverallow { domain -genton_policy -init } 
           genton_policy_data_file:file write;
```

## 🔧 集成步骤

### 1. 复制策略文件到 AOSP

```bash
# 进入 AOSP 根目录
cd ~/genton-aosp

# 复制 SELinux 策略
cp -r /path/to/sepolicy/* system/sepolicy/private/

# 更新 Android.bp
cat >> system/sepolicy/private/Android.bp <<EOF
se_policy_conf {
    name: "genton_sepolicy",
    srcs: [
        "genton.te",
        "system_server.te",
        "init.te",
        "attributes",
    ],
}
EOF
```

### 2. 添加到构建系统

编辑 `system/sepolicy/Android.mk`:

```makefile
# AI Native OS 策略
BOARD_SEPOLICY_DIRS += \
    system/sepolicy/private/genton

BOARD_SEPOLICY_UNION += \
    genton.te \
    system_server.te \
    init.te \
    attributes \
    service_contexts \
    file_contexts
```

### 3. 安装 privapp-permissions

```bash
# 复制权限清单
cp permissions/privapp-permissions-genton.xml \
   frameworks/base/data/etc/

# 更新 Android.bp
cat >> frameworks/base/data/etc/Android.bp <<EOF
prebuilt_etc {
    name: "privapp-permissions-genton.xml",
    sub_dir: "permissions",
    src: "privapp-permissions-genton.xml",
    filename_from_src: true,
}
EOF
```

### 4. 编译验证

```bash
# 编译 SELinux 策略
m selinux_policy

# 检查策略语法
sepolicy-analyze -p out/target/product/generic/sepolicy

# 查看域转换
sesearch --allow -s init -t genton_service
```

## 🧪 测试验证

### 1. 域转换测试

```bash
# 启动服务，验证域转换
adb shell ps -Z | grep genton

# 预期输出:
# u:r:genton_service:s0  ... /system/bin/genton_service
# u:r:genton_audit:s0    ... /system/bin/genton_audit
# u:r:genton_policy:s0   ... /system/bin/genton_policy
```

### 2. 服务访问测试

```bash
# 大脑 App 可以访问（应成功）
adb shell cmd activity start-service \
  -n com.genton.brain/.service.AgentService

# 第三方 App 禁止访问（应被拒绝）
adb shell cmd activity start-service \
  -n com.example.thirdparty/.BadService

# 检查 AVC 拒绝日志
adb shell dmesg | grep avc | grep genton
```

### 3. 数据保护测试

```bash
# 审计日志保护
adb shell ls -Z /data/genton/audit/
# 预期: u:object_r:genton_audit_data_file:s0

# 非特权进程无法读取
adb shell cat /data/genton/audit/hash_chain.bin
# 预期: Permission denied
```

### 4. 权限验证

```bash
# 检查大脑 App 权限
adb shell dumpsys package com.genton.brain | grep permission

# 预期包含:
# android.permission.DUMP: granted=true
# android.permission.INJECT_EVENTS: granted=true
# android.permission.CALL_PHONE: granted=true
```

## 📊 权限清单详情

[privapp-permissions-genton.xml](../permissions/privapp-permissions-genton.xml) 定义了 **49 项**系统权限：

| 分类 | 权限数 | 关键权限 |
|------|--------|----------|
| 感知服务 | 6 | DUMP, READ_FRAME_BUFFER, ACCESS_NOTIFICATIONS |
| 动作执行 | 6 | INJECT_EVENTS, WRITE_SETTINGS, BIND_ACCESSIBILITY_SERVICE |
| Agent 运行时 | 4 | MANAGE_USERS, FOREGROUND_SERVICE |
| 记忆服务 | 3 | MANAGE_EXTERNAL_STORAGE |
| 事件流 | 6 | BATTERY_STATS, ACCESS_FINE_LOCATION |
| 推理路由 | 3 | INTERNET, ACCESS_WIFI_STATE |
| 通话门控 | 5 | CALL_PHONE, READ_CALL_LOG, ANSWER_PHONE_CALLS |
| 姿态交互 | 2 | HIGH_SAMPLING_RATE_SENSORS, BODY_SENSORS |
| 审计调试 | 3 | READ_LOGS, QUERY_ALL_PACKAGES |
| 系统级 | 11 | STATUS_BAR, WAKE_LOCK, CHANGE_CONFIGURATION |

## ⚠️ 安全注意事项

### 1. 调试构建 vs 生产构建

```te
# 调试构建允许 shell 访问（仅用于开发）
userdebug_or_eng(`
  allow shell genton_service_type:service_manager find;
')

# 生产构建严格禁止
# neverallow shell genton_service_type:service_manager find;
```

### 2. Neverallow 规则

以下操作被**永久禁止**：

- ❌ 第三方应用访问 AI Native OS 服务
- ❌ 非审计域读取审计日志
- ❌ 非策略引擎修改 DSL 规则
- ❌ Shell 访问（生产构建）

### 3. 权限审计

定期审计权限使用：

```bash
# 审计日志中的权限检查
adb shell cat /data/genton/audit/permission_checks.log

# 统计最常用权限
adb shell cat /data/genton/audit/permission_checks.log | \
  grep PERMISSION_CHECK | awk '{print $4}' | sort | uniq -c | sort -rn
```

## 🔍 故障排查

### 问题 1: AVC 拒绝

```bash
# 查看 SELinux 拒绝日志
adb shell dmesg | grep "avc.*denied"

# 示例输出:
# avc: denied { call } for scontext=u:r:untrusted_app:s0 
#      tcontext=u:r:genton_service:s0 tclass=binder

# 解决: 这是预期行为，第三方应用被正确拒绝
```

### 问题 2: 服务启动失败

```bash
# 检查域转换
adb shell ps -Z | grep genton

# 如果域为 init 或 system_server，说明转换失败
# 检查 init.te 和 file_contexts
```

### 问题 3: 权限未生效

```bash
# 检查权限清单是否安装
adb shell ls -l /system/etc/permissions/ | grep genton

# 预期: privapp-permissions-genton.xml

# 重启后生效
adb reboot
```

## 📚 参考资料

- [Android SELinux 官方文档](https://source.android.com/docs/security/features/selinux)
- [SELinux Policy Language](https://selinuxproject.org/page/PolicyLanguage)
- [AOSP SELinux 最佳实践](https://source.android.com/docs/security/features/selinux/device-policy)

---

**SELinux 策略配置完成 ✅** - 确保 AI Native OS 服务在强制访问控制下安全运行。
