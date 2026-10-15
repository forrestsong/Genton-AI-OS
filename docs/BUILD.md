# AI Native OS - 构建指南

## 📋 概述

本文档描述如何配置 Android 16 AOSP 构建环境，用于 AI Native OS 深度定制系统的开发。

## ✅ 已完成配置

### 1. Repo 工具
- **路径**: `tools/repo.cmd`
- **版本**: 最新稳定版（清华镜像源）
- **用途**: AOSP 源码同步工具

### 2. Manifest 配置
- **文件**: `manifests/genton-android16.xml`
- **内容**: 14 个 AIDL 模块 + 系统服务 + 大脑 App + AI Runtime
- **基线**: Android 16 (`android-16` 分支)

### 3. 工具链检测
- **Python**: 3.14.5 ✓
- **Git**: 2.55.0 ✓
- **Java**: OpenJDK 17.0.20.1 ✓

### 4. 目录结构
```
AI Native OS/
├── manifests/
│   └── genton-android16.xml        # AOSP manifest 配置
├── overlay/                          # 系统覆盖层
│   ├── frameworks/base/              # Framework 扩展
│   │   └── core/java/com/genton/  # AIDL 接口
│   └── system/sepolicy/              # SELinux 策略
├── patches/                          # AOSP 补丁
├── registry/                         # 动作注册表
├── tools/
│   └── repo.cmd                      # Repo 工具 (Windows)
├── setup_repo.py                     # Repo 安装脚本
├── setup_build_env.sh                # Linux 构建环境脚本
└── build_prepare.ps1                 # Windows 准备脚本
```

## 🚀 构建流程

### 方案 A: WSL 2 + Ubuntu (推荐)

**为什么选择 WSL 2:**
- AOSP 官方支持 Linux 构建
- 完整的 POSIX 兼容性
- 与 Windows 文件系统无缝集成
- 可直接访问 GPU/NPU 驱动

**步骤:**

1. **安装 WSL 2 + Ubuntu 22.04**
   ```powershell
   wsl --install -d Ubuntu-22.04
   wsl --set-default-version 2
   ```

2. **进入 WSL 环境**
   ```bash
   wsl
   ```

3. **运行构建环境脚本**
   ```bash
   cd /mnt/d/AI\ Native\ OS
   chmod +x setup_build_env.sh
   ./setup_build_env.sh
   ```

4. **初始化 AOSP 仓库**
   ```bash
   cd ~/genton-aosp
   repo init -u https://android.googlesource.com/platform/manifest -b android-16
   
   # 复制 AI Native OS manifest
   mkdir -p .repo/local_manifests
   cp /mnt/d/AI\ Native\ OS/manifests/genton-android16.xml .repo/local_manifests/
   
   # 同步源码 (约 150GB, 需要 2-4 小时)
   repo sync -c -j$(nproc)
   ```

5. **应用 AI Native OS 补丁**
   ```bash
   cd ~/genton-aosp
   cp -r /mnt/d/AI\ Native\ OS/overlay/* .
   cp -r /mnt/d/AI\ Native\ OS/patches/* .
   
   # 应用 Framework 补丁
   cd frameworks/base
   git am ../../patches/frameworks_base/*.patch
   ```

6. **配置构建环境**
   ```bash
   source build/envsetup.sh
   lunch genton_arm64-userdebug
   ```

7. **开始构建**
   ```bash
   make -j$(nproc)
   ```

### 方案 B: Docker 容器

```bash
# 拉取 AOSP 构建镜像
docker pull ubuntu:22.04

# 启动容器并挂载工作目录
docker run -it -v "d:/AI Native OS:/workspace" ubuntu:22.04

# 在容器内执行构建
cd /workspace
./setup_build_env.sh
# ... 后续步骤同 WSL
```

### 方案 C: 虚拟机

- VirtualBox/VMware + Ubuntu 22.04 LTS
- 分配资源: 16GB+ RAM, 500GB+ 磁盘
- 通过共享文件夹访问 Windows 项目目录

## 📦 构建产物

成功构建后，产物位于:
```
~/genton-aosp/out/target/product/generic_arm64/
├── system.img              # 系统分区镜像
├── vendor.img              # Vendor 分区
├── boot.img                # 启动镜像
├── genton-ota.zip        # OTA 更新包
└── symbols/                # 调试符号
```

## 🔧 仅接口开发 (不需要完整 AOSP)

如果只进行 AIDL 接口开发和系统服务实现，无需同步完整 AOSP:

1. **使用现有 overlay 目录**
   ```
   overlay/frameworks/base/core/java/com/genton/
   ```

2. **本地编译 AIDL**
   ```bash
   cd overlay/frameworks/base/core/java/com/genton/perception
   aidl --lang=java -o out android/com/genton/perception/*.aidl
   ```

3. **Java 服务端开发**
   - 直接在 `overlay/` 目录下编写服务实现
   - 使用 Android Studio 进行开发
   - 模拟 SystemServer 环境进行单元测试

## 📝 环境变量

可在 `~/.bashrc` 或 `~/.zshrc` 中添加:

```bash
# AOSP 构建环境
export AOSP_ROOT=~/genton-aosp
export USE_CCACHE=1
export CCACHE_DIR=~/.ccache
export CCACHE_MAXSIZE=50G

# AI Native OS 配置
export GENTON_CONFIG=$AOSP_ROOT/vendor/genton/config
export GENTON_POLICY=$AOSP_ROOT/vendor/genton/policy

# Java
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Repo
export PATH=~/bin:$PATH
```

## 🔍 故障排查

### 问题 1: repo sync 失败
```bash
# 使用清华镜像源
export REPO_URL='https://mirrors.tuna.tsinghua.edu.cn/git/git-repo'
repo init --repo-url=$REPO_URL -u https://android.googlesource.com/platform/manifest -b android-16
```

### 问题 2: 磁盘空间不足
```bash
# 仅同步当前分支 (-c)
repo sync -c -j8 --no-tags

# 清理 ccache
ccache -C
```

### 问题 3: 构建内存不足
```bash
# 减少并行任务数
make -j4  # 代替 -j$(nproc)
```

## 📊 预期时间

| 操作 | 时间 | 磁盘占用 |
|------|------|----------|
| repo sync | 2-4 小时 | ~150GB |
| 首次完整构建 | 4-8 小时 | +250GB |
| 增量构建 | 10-30 分钟 | - |
| 仅 Framework 构建 | 20-40 分钟 | - |

## 🎯 下一步

1. ✅ **A1.1 平台工程基线** - 已完成 repo + manifest 配置
2. 🔄 **A1.3 AIDL 模块** - 需要在 AOSP 环境中创建 14 个 aidl_interface
3. 🔄 **A1.4 权限清单** - SELinux 策略 + privapp-permissions

---

**注意**: 由于 AOSP 构建对资源要求较高（16GB+ RAM, 400GB+ 磁盘），建议:
- 优先使用 WSL 2（性能接近原生 Linux）
- 开启 ccache 加速增量构建
- 首次构建选择空闲时段进行

**当前状态**: 工具链配置完成 ✓
