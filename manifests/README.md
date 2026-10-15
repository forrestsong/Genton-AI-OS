# AI Native OS Manifests

此目录包含 repo 工具所需的 manifest 文件，用于同步 AOSP 和 AI Native OS 定制代码。

## 使用方法

### 初始化仓库

```bash
# 安装 repo 工具（如果尚未安装）
mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
export PATH=~/bin:$PATH

# 初始化 AI Native OS 代码树
mkdir genton-os
cd genton-os
repo init -u https://github.com/genton-os/manifests -b main -m genton.xml

# 同步代码（首次约 100GB）
repo sync -c -j$(nproc --all)
```

### 应用补丁

```bash
# 应用 AI Native OS 补丁到 AOSP 上游
cd genton-os
./patches/apply-patches.sh
```

## Manifest 文件

| 文件 | 用途 |
|------|------|
| `genton.xml` | 主 manifest，基于 AOSP 15.0.0_r1 |

## 目录结构（同步后）

```
genton-os/
├── build/                    # AOSP 构建系统
├── frameworks/               # Android 框架层
├── system/                   # 系统核心
├── packages/                 # 系统应用
├── overlay/                  # AI Native OS 覆盖层
│   ├── frameworks/base/      # 框架层修改
│   ├── packages/apps/        # 应用修改
│   └── vendor/genton/      # 平台配置
├── vendor/genton/          # AI Native OS 专有代码
│   ├── services/             # 19 个系统服务
│   ├── policy/               # 策略引擎
│   └── config/               # 配置文件
├── patches/                  # 补丁集合
├── tools/                    # 工具脚本
└── docs/                     # 文档
```

## 相关链接

- [构建指南](../docs/BUILD.md)
- [开发指南](../docs/DEVELOPMENT.md)
- [A1 里程碑实施计划](../A1里程碑实施计划.md)
