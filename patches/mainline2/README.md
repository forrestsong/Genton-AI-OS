# 主线②「真实 AOSP 构建验证」修复清单

构建产出：`artifacts/cpday10/`（system/vendor/product/vbmeta/super_empty/ramdisk）

最终状态：`#### build completed successfully ####` / EXIT=0 / FAILED=0
（`system.img` 1.2 GB，2026-10-07 16:36）

---

## 目录内容

| 文件 | 作用 | 关键性 |
|---|---|---|
| `services/proguard.flags` | **R8 keep 规则**，防止服务实现被裁剪 | ⭐⭐⭐ 关键 |
| `services/SystemServer.java.patched` | SystemServer 接入（import + 注册调用） | ⭐⭐⭐ 关键 |
| `services/AinativeServiceRegistry.java` | 服务注册表（自动生成，17 个服务，含依赖顺序） | ⭐⭐⭐ 关键 |
| `sepolicy/ainative_service.te` | 服务类型定义（含新增 4 个） | ⭐⭐ |
| `sepolicy/service_contexts` | 服务上下文（41 条） | ⭐⭐ |
| `sepolicy/file_contexts` | 平台文件上下文（AOSP 基线 + 7 条 ainative） | ⭐⭐ |
| `sepolicy/plat_file_contexts_test` | 上述 file_contexts 的测试项 | ⭐ |
| `sepolicy/service_fuzzer_bindings.go` | 服务 fuzzer 绑定登记 | ⭐ |
| `sepolicy/vendor_file_contexts` / `vendor_service_contexts` | vendor 侧上下文 | ⭐ |
| `device/BoardConfig.mk` | 分区布局（vendor/product 独立分区） | ⭐⭐ |
| `device/ainative_overlay.mk` | 产品包与配置安装 | ⭐⭐ |
| `device/aosp_cpday10.mk` | 产品继承链 | ⭐ |

---

## 必须知道的三个「坑」

### 1. release config 必须用 `bp2a`（Android 16），不能用 `ap3a`

```bash
lunch aosp_cpday10-bp2a-eng     # ✅ 正确：PLATFORM_VERSION=16、Java 21
# lunch aosp_cpday10-ap3a-eng   # ❌ 错误：PLATFORM_VERSION=15、Java 17
```

树中代码是 Android 16，用 `ap3a`（Android 15）配置会导致**平台代码大面积 Java 21 语法编译失败**。

### 2. `SKIP_ABI_CHECKS=true` 是开发期绕过

```bash
export SKIP_ABI_CHECKS=true      # 不是 DISABLE_ABI_CHECKS
```

正式发布需生成 ABI 参考转储：
```bash
create_reference_dumps.py --lib libbinder_ndk --lib-variant LLNDK --release ap3a
```

### 3. `PRODUCT_ENFORCE_VINTF_MANIFEST` 须改 `build/make/core/config.mk`

产品级 overlay 中设置会被覆盖，必须直接改基准配置（当前为 `false`，因 MTK vendor 树是最小配置、VINTF 不完整）。

---

## 关键修复：为什么服务会从镜像里"消失"

**症状**：dex 前 `combined/services.jar` 有 167 个 `com/android/server/ainative/*` 类，镜像内 dex 后的 `services.jar` 却是 0。

**根因**：`frameworks/base/services/proguard.flags` 对 `com.android.server.**` **只有条件式 keep**，没有全量 keep。AOSP 自家服务被 `SystemServer`（有 `-keep`）静态引用而存活；我们的服务只通过 `ServiceManager` 按字符串名注册，R8 判定不可达 → **整包裁剪**。

**修复**（两层缺一不可）：

```proguard
-keep,allowoptimization,allowaccessmodification class com.android.server.ainative.** { *; }
```
+ 把服务接入 `SystemServer` 的静态引用链（`AinativeServiceRegistry.start(context)`）。

- keep 规则 → 保证类**存在于镜像**
- SystemServer 注册 → 保证服务**被实例化并对外可用**

---

## 尚未完成

| 项 | 说明 |
|---|---|
| **W8 实机验证（B-M2）** | 需真实 CPDAY10 设备或可用 emulator；`boot.img` 亦缺失（无内核源） |
| **AINativeBrain 应用** | 已移出构建；`ActionExecutor` 引用了 AIDL 中不存在的 `executeAction`/`queryActionStatus`/`ActionStatus`，需补齐契约 |
| **SystemUI 覆盖层** | 61 个文件移出；覆盖层直接引用了 `com.android.server.*` 服务端类（独立 APK 不可见），需改为 AIDL 跨进程调用 |
| **AINativeBrain 的 sepolicy 域** | 随应用一并停用，重新纳入时需重建 |
