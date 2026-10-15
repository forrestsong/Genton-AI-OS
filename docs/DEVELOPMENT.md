# AI Native OS 开发指南

本指南面向 AI Native OS 的开发者，提供代码组织、开发规范和最佳实践。

## 项目结构

```
genton-os/
├── build/                        # AOSP 构建系统（上游）
├── frameworks/                   # Android 框架层（上游）
├── system/                       # 系统核心（上游）
├── packages/                     # 系统应用（上游）
│
├── overlay/                      # AI Native OS 覆盖层
│   ├── frameworks/base/core/java/com/genton/
│   │   ├── perception/          # 感知层 AIDL
│   │   ├── action/              # 执行层 AIDL
│   │   ├── agent/               # Agent 运行时 AIDL
│   │   ├── memory/              # 记忆服务 AIDL
│   │   ├── audit/               # 审计引擎 AIDL
│   │   └── ...                  # 其他 14 个接口族
│   └── packages/apps/
│       └── GentonBrain/       # 大脑特权 App
│
├── vendor/genton/              # AI Native OS 专有代码
│   ├── services/                # 系统服务实现
│   │   ├── perception/          # 感知服务
│   │   ├── action/              # 执行服务
│   │   ├── policy/              # 策略引擎
│   │   └── ...
│   ├── config/                  # 配置文件
│   │   ├── action_registry.json
│   │   ├── capability_definitions.json
│   │   └── policy_baseline.dsl
│   ├── products/                # 产品配置
│   └── sepolicy/                # SELinux 策略
│
├── patches/                      # AOSP 上游补丁
├── tools/                        # 开发工具
│   ├── aidl_generator.py        # AIDL 生成器
│   ├── policy_compiler.py       # 策略 DSL 编译器
│   └── security_audit.py        # 安全检查工具
│
├── test/                         # 测试套件
│   ├── unit/                    # 单元测试
│   ├── integration/             # 集成测试
│   └── e2e/                     # 端到端测试
│
└── docs/                         # 文档
    ├── security/                # 安全规范
    └── architecture/            # 架构设计
```

## 开发工作流

### 1. 环境设置

```bash
# 克隆代码
cd ~/genton-os
source build/envsetup.sh
lunch aosp_arm64-userdebug

# 设置 Python 虚拟环境（用于工具）
python3 -m venv venv
source venv/bin/activate
pip install -r tools/requirements.txt
```

### 2. 创建功能分支

```bash
# 基于主线创建分支
cd ~/genton-os
repo start feature-my-new-feature --all

# 或针对单个模块
cd frameworks/base
git checkout -b feature-my-new-feature
```

### 3. 开发流程

#### 添加新的 AIDL 接口

```bash
# 使用生成器创建骨架
python tools/aidl_generator.py \
  --interface IMyNewService \
  --package com.genton.mymodule \
  --output overlay/frameworks/base/core/java/com/genton/mymodule/

# 生成文件：
# - IMyNewService.aidl
# - Android.bp
# - api/current.txt
```

#### 实现系统服务

```bash
# 在 vendor/genton/services/ 创建实现
mkdir -p vendor/genton/services/mymodule/java/com/genton/mymodule

# 创建服务类
cat > vendor/genton/services/mymodule/java/com/genton/mymodule/MyNewService.java << 'EOF'
package com.genton.mymodule;

import android.content.Context;
import com.genton.mymodule.IMyNewService;

public class MyNewService extends IMyNewService.Stub {
    private final Context mContext;
    
    public MyNewService(Context context) {
        mContext = context;
    }
    
    // 实现 AIDL 方法
}
EOF

# 创建 Android.bp
cat > vendor/genton/services/mymodule/Android.bp << 'EOF'
java_library {
    name: "genton.mymodule.service",
    srcs: ["java/**/*.java"],
    sdk_version: "system_current",
    static_libs: [
        "genton.mymodule",
    ],
    platform_apis: true,
}
EOF
```

#### 注册到 SystemServer

在 `frameworks/base/services/java/com/android/server/SystemServer.java` 中添加：

```java
// 添加导入
import com.genton.mymodule.MyNewService;

// 在 startOtherServices() 中注册
private void startOtherServices() {
    // ...
    
    traceBeginAndSlog("StartMyNewService");
    MyNewService myNewService = new MyNewService(mSystemContext);
    ServiceManager.addService("mynew", myNewService);
    traceEnd();
    
    // ...
}
```

#### 配置 SELinux 策略

```bash
# 在 vendor/genton/sepolicy/private/ 创建策略
cat > vendor/genton/sepolicy/private/mynew_service.te << 'EOF'
type mynew_service, system_api_service, system_server_service, service_manager_type;
EOF

# 在 service_contexts 中声明
echo "mynew    u:object_r:mynew_service:s0" >> vendor/genton/sepolicy/private/service_contexts
```

### 4. 构建与测试

```bash
# 增量构建
m genton.mymodule.service

# 运行单元测试
atest MyNewServiceTest

# 运行安全检查
python tools/security_audit.py --module mymodule
```

### 5. 提交代码

```bash
# 提交到本地仓库
cd frameworks/base
git add -A
git commit -m "Add MyNewService AIDL interface

- Define IMyNewService.aidl with core methods
- Add Android.bp for aidl_interface
- Update API surface

Bug: none
Test: atest MyNewServiceTest
"

# 上传审查（如果使用 Gerrit）
repo upload
```

## 开发规范

### 命名规范

#### Java/Kotlin

- **包名**: `com.genton.<module>`
- **AIDL 接口**: `IAgent*Service`, `IAgent*Manager`
- **实现类**: `Agent*Service`, `*ManagerImpl`
- **常量**: `UPPER_SNAKE_CASE`
- **方法**: `camelCase`

示例：
```java
package com.genton.perception;

public class AgentUiTreeService extends IAgentUiTreeService.Stub {
    private static final String TAG = "Genton.UiTree";
    private static final int MAX_DEPTH = 100;
    
    public UiNode captureUiTree(String packageName) {
        // ...
    }
}
```

#### C++

- **命名空间**: `android::genton::<module>`
- **类名**: `PascalCase`
- **方法**: `camelCase`
- **成员变量**: `mCamelCase`

#### 配置文件

- **JSON**: `snake_case`
- **DSL**: `kebab-case`
- **XML**: `camelCase`（遵循 Android 约定）

### 安全规范

遵循 [E1.1 安全红线检查单](../docs/security/E1.1-安全红线检查单v1.md)：

#### 1. 权限声明

```xml
<!-- AndroidManifest.xml -->
<permission android:name="com.genton.permission.ACCESS_AGENT_CONTEXT"
    android:protectionLevel="signature|privileged"
    android:label="@string/perm_access_agent_context" />
```

#### 2. 能力令牌检查

```java
public void performAction(String actionId, CapabilityToken token) {
    // 三查验证
    if (!token.verify()) {
        throw new SecurityException("Invalid capability token");
    }
    
    if (!token.hasScope(actionId)) {
        throw new SecurityException("Token does not cover action: " + actionId);
    }
    
    if (token.isExpired()) {
        throw new SecurityException("Token expired");
    }
    
    // 执行动作
}
```

#### 3. Taint 标注

```java
public class AgentMemoryService {
    public void store(@TrustLevel.UserInput String userInput) {
        // 自动标记为 USER_INPUT
        MemoryEntry entry = new MemoryEntry(userInput, TrustLevel.USER_INPUT);
        mDatabase.insert(entry);
    }
}
```

#### 4. Secret 安全路径

```java
// ❌ 错误：密码明文传递
public void login(String password) {
    Log.d(TAG, "Login with password: " + password);  // 泄露
}

// ✅ 正确：使用 SecureInput
public void login(SecureInput passwordInput) {
    char[] password = passwordInput.getValue();
    // 使用后立即清零
    Arrays.fill(password, '\0');
}
```

### 测试规范

#### 单元测试

```java
// MyServiceTest.java
@RunWith(AndroidJUnit4.class)
public class MyServiceTest {
    private MyService mService;
    
    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mService = new MyService(context);
    }
    
    @Test
    public void testBasicOperation() {
        String result = mService.performOperation();
        assertThat(result).isNotNull();
    }
    
    @Test
    public void testSecurityCheck() {
        assertThrows(SecurityException.class, () -> {
            mService.performAction(null, null);
        });
    }
}
```

#### 集成测试

```python
# test/integration/test_policy_engine.py
import unittest
from genton.policy import PolicyEngine

class PolicyEngineIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.engine = PolicyEngine.from_config('config/policy_baseline.dsl')
    
    def test_deny_default(self):
        """测试未匹配规则返回 DENY"""
        result = self.engine.evaluate('unknown_action', {})
        self.assertEqual(result.decision, 'DENY')
    
    def test_performance(self):
        """测试 P50 < 1ms"""
        latencies = []
        for _ in range(1000):
            start = time.time()
            self.engine.evaluate('test_action', {})
            latencies.append(time.time() - start)
        
        p50 = np.percentile(latencies, 50)
        self.assertLess(p50, 0.001)  # < 1ms
```

## 调试技巧

### Logcat 过滤

```bash
# 仅显示 AI Native OS 日志
adb logcat -s "Genton.*"

# 显示特定模块
adb logcat -s "Genton.Policy" "Genton.Action"
```

### 使用 AIDL Dump

```bash
# 查看服务状态
adb shell dumpsys agent_runtime

# 查看策略引擎状态
adb shell dumpsys policy_engine
```

### 审计日志查看

```bash
# 导出审计日志
adb shell cat /data/system/genton/audit.log > audit.log

# 验证哈希链
python tools/verify_audit_chain.py audit.log
```

## 性能优化

### Binder 调用优化

```java
// 使用异步 Binder 调用
@Override
public void performActionAsync(String actionId, IActionCallback callback) {
    mExecutor.execute(() -> {
        try {
            ActionResult result = performActionInternal(actionId);
            callback.onSuccess(result);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    });
}
```

### 策略引擎缓存

```java
public class PolicyEngine {
    private final LruCache<String, PolicyDecision> mCache = 
        new LruCache<>(1000);
    
    public PolicyDecision evaluate(PolicyRequest request) {
        String key = request.getCacheKey();
        PolicyDecision cached = mCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }
        
        PolicyDecision decision = evaluateInternal(request);
        mCache.put(key, decision);
        return decision;
    }
}
```

## 常见问题

### AIDL 编译失败

```bash
# 清理 AIDL 缓存
rm -rf out/soong/.intermediates/frameworks/base/core/java/com/genton/

# 重新生成
m genton.perception-java
```

### SELinux 拒绝

```bash
# 查看拒绝日志
adb shell dmesg | grep avc

# 生成临时策略（仅用于调试）
adb shell setenforce 0
```

### 服务未注册

```bash
# 检查服务列表
adb shell service list | grep genton

# 重启 system_server
adb shell stop
adb shell start
```

## 相关文档

- [构建指南](BUILD.md)
- [A1 里程碑实施计划](../A1里程碑实施计划.md)
- [安全红线检查单](security/E1.1-安全红线检查单v1.md)
- [AOSP 开发文档](https://source.android.com/docs)
