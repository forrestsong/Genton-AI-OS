# AI Native OS Patches

此目录包含应用到 AOSP 上游代码的补丁文件，用于实现 AI Native OS 核心功能。

## 目录结构

每个子目录对应一个 AOSP 模块：

```
patches/
├── frameworks_base/              # Android 框架层补丁
│   ├── 0001-add-agent-manager-service.patch
│   ├── 0002-add-capability-token-chain.patch
│   └── ...
├── frameworks_native/            # Native 框架补丁
├── system_core/                  # 系统核心补丁
├── system_sepolicy/              # SELinux 策略补丁
├── packages_apps_Settings/       # Settings 应用补丁
├── packages_apps_SystemUI/       # SystemUI 补丁
├── build_make/                   # 构建系统补丁
├── build_soong/                  # Soong 构建补丁
└── apply-patches.sh              # 补丁应用脚本
```

## 补丁命名规范

```
NNNN-short-description.patch
```

- `NNNN`: 4位数字序号（按依赖顺序排列）
- `short-description`: 简短英文描述
- 示例: `0001-add-agent-manager-service.patch`

## 使用方法

### 应用所有补丁

```bash
cd genton-ai-os

# 查看部署计划（默认 dry-run）
./genton-layer/patches/apply-patches.sh

# 实际写入到源码树
./genton-layer/patches/apply-patches.sh --apply --target .
```

### 手动应用单个补丁

```bash
cd frameworks/base
git apply ../../patches/frameworks_base/0001-add-agent-manager-service.patch
```

### 检查补丁是否已应用

```bash
cd frameworks/base
git apply --check ../../patches/frameworks_base/0001-add-agent-manager-service.patch
```

## 补丁内容（A1 里程碑）

### frameworks_base（核心功能）

| 编号 | 补丁文件 | 功能 | 关联任务 |
|------|---------|------|---------|
| 0001 | add-agent-manager-service.patch | AgentManager 系统服务 | A4.1 |
| 0002 | add-capability-token-chain.patch | 令牌链机制 | A4.2 |
| 0003 | add-policy-engine.patch | 策略引擎集成 | A7.1 |
| 0004 | add-audit-hash-chain.patch | 审计哈希链 | A5.2 |
| 0005 | add-confirmation-host.patch | 确认宿主 SystemUI | A11.2 |
| 0006 | add-session-bridge.patch | 会话桥 AIDL | A11.1 |

### system_sepolicy（安全策略）

| 编号 | 补丁文件 | 功能 |
|------|---------|------|
| 0001 | label-genton-services.patch | 标记 19 个系统服务 |
| 0002 | agent-runtime-context.patch | Agent 运行时上下文 |
| 0003 | capability-token-domain.patch | 令牌验证域 |

### packages_apps_SystemUI（用户界面）

| 编号 | 补丁文件 | 功能 |
|------|---------|------|
| 0001 | add-confirmation-panel.patch | FLAG_SECURE 确认面板 |
| 0002 | add-activity-indicator.patch | 活性指示器 |

## 从 openphone 迁移

许多补丁参考了 openphone 项目的实现：

```bash
# openphone 补丁位置
d:\AI Native OS\openphone\patches\frameworks_base\

# 迁移方法
1. 复制 openphone 补丁作为起点
2. 根据轨道 A 规范调整接口名称（OpenPhone* -> Genton*）
3. 添加新功能（令牌链、策略 DSL、多层记忆等）
4. 运行安全检查: python tools/security_audit.py
```

## 维护指南

### 创建新补丁

```bash
# 在目标目录做修改
cd frameworks/base
# ... 编辑代码 ...

# 生成补丁
git add -A
git diff --cached > ../../patches/frameworks_base/0007-my-new-feature.patch
```

### 更新现有补丁

```bash
# 回退补丁
cd frameworks/base
git apply -R ../../patches/frameworks_base/0001-add-agent-manager-service.patch

# 修改代码
# ... 编辑 ...

# 重新生成补丁
git add -A
git diff --cached > ../../patches/frameworks_base/0001-add-agent-manager-service.patch
```

### 解决冲突

如果补丁应用失败：

1. 检查 AOSP 版本是否匹配（本项目基于 android-15.0.0_r1）
2. 手动应用变更
3. 重新生成补丁

## 与 overlay 的关系

- **patches/**: 修改 AOSP 上游代码（如 SystemServer、SystemUI）
- **overlay/**: 新增 AI Native OS 专有代码（AIDL 接口、系统服务实现）

两者配合实现完整功能。

## 相关文档

- [构建指南](../docs/BUILD.md)
- [A1 里程碑实施计划](../A1里程碑实施计划.md)
- [openphone 参考](../openphone/patches/)
