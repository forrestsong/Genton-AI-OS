# 沙箱隔离机制配置指南

## 1. isolatedProcess 配置

### AndroidManifest.xml（第三方 Agent App）

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.thirdparty.agent">
    
    <!-- 沙箱网关访问权限（系统授予） -->
    <uses-permission android:name="com.ainative.permission.USE_SANDBOX" />
    
    <application>
        <!-- 沙箱 Agent 服务（isolatedProcess） -->
        <service
            android:name=".SandboxedAgentService"
            android:exported="true"
            android:isolatedProcess="true"
            android:permission="com.ainative.permission.MANAGE_SANDBOX">
            <intent-filter>
                <action android:name="com.ainative.sandbox.AGENT_SERVICE" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

### 特点
- `isolatedProcess="true"`：独立 UID，无共享权限
- `exported="true"`：允许系统宿主绑定
- `permission`：限制只有系统可以绑定

---

## 2. 存储隔离

### 目录结构

```
/data/system/ainative/sandbox/
├── {handleId_1}/
│   ├── files/          # 沙箱私有文件
│   ├── cache/          # 沙箱缓存
│   └── prefs/          # 沙箱配置
├── {handleId_2}/
└── ...
```

### 实现要点
- 每个沙箱独立加密目录
- 沙箱间不可互访
- 系统可审计访问
- 沙箱停止后可选清理

---

## 3. 网络白名单（netd）

### iptables 规则示例

```bash
# 默认 deny 沙箱 UID 所有出网
iptables -A OUTPUT -m owner --uid-owner 99001 -j DROP

# 白名单域名（需 DNS 解析为 IP）
iptables -I OUTPUT -m owner --uid-owner 99001 -d 203.0.113.10 -j ACCEPT
iptables -I OUTPUT -m owner --uid-owner 99001 -d 203.0.113.20 -j ACCEPT
```

### 实现要点
- 沙箱启动时配置 iptables 规则
- 只允许清单声明的域名
- 沙箱停止时清理规则
- 支持动态更新白名单

---

## 4. cgroup 资源配额

### CPU 配额

```bash
# 创建 cgroup（10% CPU）
mkdir -p /sys/fs/cgroup/cpu/uid_99001
echo 102 > /sys/fs/cgroup/cpu/uid_99001/cpu.shares  # 102 = 1024 * 0.1

# 添加进程到 cgroup
echo {pid} > /sys/fs/cgroup/cpu/uid_99001/cgroup.procs
```

### 内存配额

```bash
# 创建 cgroup（256MB 内存）
mkdir -p /sys/fs/cgroup/memory/uid_99001
echo 268435456 > /sys/fs/cgroup/memory/uid_99001/memory.limit_in_bytes  # 256MB

# 添加进程到 cgroup
echo {pid} > /sys/fs/cgroup/memory/uid_99001/cgroup.procs
```

### 实现要点
- 沙箱启动时创建 cgroup
- 限制 CPU、内存、IO
- 超限自动 OOM kill
- 沙箱停止时清理 cgroup

---

## 5. SELinux 策略收紧

### sepolicy 规则（ainative_sandbox.te）

```
# 沙箱域定义
type ainative_sandbox, domain;
type ainative_sandbox_exec, exec_type, file_type;

# 沙箱进程转换
type_transition init_t ainative_sandbox_exec:process ainative_sandbox;

# 允许绑定沙箱网关
allow ainative_sandbox ainative_sandbox_gateway_service:service_manager find;
allow ainative_sandbox ainative_sandbox_gateway:binder { call transfer };

# 禁止访问其他系统服务（neverallow）
neverallow ainative_sandbox {
    system_server
    surfaceflinger
    audioserver
    cameraserver
}:binder { call transfer };

# 禁止访问敏感文件
neverallow ainative_sandbox {
    system_file
    vendor_file
    system_data_file
}:file { read write };

# 允许访问沙箱私有存储
allow ainative_sandbox ainative_sandbox_data_file:dir { create_dir_perms };
allow ainative_sandbox ainative_sandbox_data_file:file { create_file_perms };
```

### 实现要点
- 沙箱进程运行在独立 SELinux 域
- 只允许绑定沙箱网关服务
- neverallow 阻断非白名单 Binder 调用
- 禁止访问系统敏感文件

---

## 6. 权限配置

### privapp-permissions-ainative.xml

```xml
<!-- 沙箱宿主权限（大脑特权 App） -->
<privapp-permissions package="com.ainative.brain">
    <permission name="com.ainative.permission.MANAGE_SANDBOX"/>
    <permission name="android.permission.INTERACT_ACROSS_USERS"/>
    <permission name="android.permission.MANAGE_USERS"/>
</privapp-permissions>

<!-- 第三方 Agent 无特权权限 -->
<!-- 沙箱进程只能通过网关访问系统能力 -->
```

### 实现要点
- 宿主拥有 MANAGE_SANDBOX 权限
- 第三方 Agent 无任何特权权限
- 沙箱进程只能绑定网关服务

---

## 7. 验证清单

### 启动时检查
- [ ] isolatedProcess 进程独立 UID
- [ ] 私有存储目录已创建
- [ ] iptables 规则已配置
- [ ] cgroup 限制已生效
- [ ] SELinux 域转换正确

### 运行时检查
- [ ] 非白名单网络访问被阻断
- [ ] 超出 CPU/内存配额自动限制
- [ ] 非白名单 Binder 调用被 SELinux 拒绝
- [ ] 网关调用超限自动终止
- [ ] 敏感操作触发用户确认

### 停止时检查
- [ ] 进程已终止
- [ ] iptables 规则已清理
- [ ] cgroup 已删除
- [ ] 私有存储已清理（可选）

---

## 8. 调试命令

```bash
# 查看沙箱进程
ps -ef | grep isolated

# 查看 iptables 规则
iptables -L OUTPUT -v -n | grep 99001

# 查看 cgroup 配置
cat /sys/fs/cgroup/cpu/uid_99001/cpu.shares
cat /sys/fs/cgroup/memory/uid_99001/memory.limit_in_bytes

# 查看 SELinux 拒绝日志
ausearch -m avc -ts recent | grep ainative_sandbox

# 查看沙箱存储
ls -la /data/system/ainative/sandbox/
```

---

## 9. 安全测试

### E5.3 沙箱逃逸测试
- [ ] 尝试访问非白名单 Binder 服务（应被 SELinux 阻断）
- [ ] 尝试读取系统文件（应被 SELinux 阻断）
- [ ] 尝试访问非白名单网络域名（应被 iptables 阻断）
- [ ] 尝试超出资源配额（应被 cgroup 限制）
- [ ] 尝试绕过网关直接调用系统服务（应失败）

---

## 10. 参考文档

- Android isolatedProcess: https://developer.android.com/guide/topics/manifest/service-element#isolated
- iptables: https://linux.die.net/man/8/iptables
- cgroups: https://www.kernel.org/doc/Documentation/cgroup-v1/
- SELinux: https://source.android.com/docs/security/features/selinux
