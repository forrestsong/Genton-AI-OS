# Genton AI OS Manifests

此目录包含 repo 工具所需的 manifest 文件，用于同步 AOSP 基座与 Genton AI OS 定制层。

## 使用方法

### 初始化代码树

```bash
# 安装 repo 工具（如果尚未安装）
mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
export PATH=~/bin:$PATH

# 初始化代码树
mkdir genton-ai-os
cd genton-ai-os
repo init -u https://github.com/forrestsong/Genton-AI-OS -b main -m manifests/genton.xml

# 同步代码（AOSP 基座体积较大，首次较慢）
repo sync -c -j$(nproc --all)
```

### 应用定制层

Genton AI OS 定制层是**单一仓库（monorepo）**，由 manifest 检出到 `genton-layer/`，
其中包含 `overlay/`、`patches/`、`tools/`、`vendor/genton/` 等。

```bash
cd genton-ai-os

# 查看部署计划（默认 dry-run，不写入）
./genton-layer/patches/apply-patches.sh

# 实际写入到当前源码树
./genton-layer/patches/apply-patches.sh --apply --target .
```

> `patches/apply_patches.py` 由载荷自身推导目标路径（代码文件读 `package` 声明，
> 非代码载荷按显式映射表），**任何无法推导的载荷都会非零退出**，不会静默跳过。
> 部署基线见 [patches/deploy_baseline.json](../patches/deploy_baseline.json)，
> 其条目只允许减少。

## Manifest 文件

| 文件 | 用途 |
|------|------|
| `genton.xml` | 主 manifest（Android 15.0.0_r1 基座 + monorepo 定制层），**推荐使用** |
| `genton-android16.xml` | Android 16 变体；仍按多仓库布局编写，**尚不能直接用于 `repo sync`**（见文件内说明） |
| `genton-build.xml` | 使用清华 AOSP 镜像加速同步的辅助说明 |

## 目录结构（同步后）

```
genton-ai-os/
├── build/                    # AOSP 构建系统
├── frameworks/               # Android 框架层
├── system/                   # 系统核心
├── packages/                 # 系统应用
└── genton-layer/             # Genton AI OS 定制层（单一仓库）
    ├── overlay/              # 覆盖 AOSP 源码的修改（已按目标路径组织）
    ├── patches/              # 可部署补丁载荷 + 部署器
    ├── tools/                # 构建/测试/部署工具
    ├── vendor/genton/        # 平台配置与策略 DSL 基线
    ├── manifests/            # 本目录
    ├── registry/             # 动作注册表
    ├── verification/         # 静态验证门禁
    └── sepolicy/             # SELinux 策略参考
```

## 相关链接

- [项目主页 README](../README.md)
- [验证体系说明](../verification/README.md)
- [补丁系统说明](../patches/README.md)
