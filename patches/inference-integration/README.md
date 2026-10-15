# 推理层集成：DeepSeek + NeoHorse-Jev-4B + MiniCPM5-2B

## 一、模型定位（重要架构澄清）

| 模型 | 类型 | 运行时 | 部署位置 | 在 AI Native OS 中的角色 |
|---|---|---|---|---|
| **MiniCPM5-2B** | **生成式** 2B LLM<br>`text-generation` / `on-device` / `tool-calling` | **llama.cpp**（GGUF） | 模拟器内 | 端侧对话/工具调用（`TIER_LOCAL_LARGE`） |
| **NeoHorse-Jev-4B** | **非生成式**决策模型<br>`non-generative` / `text-classification`<br>Prefill-only，Choice/Noul/Score | **PyTorch + vLLM/SGLang + CUDA** | **WSL 主机（GPU）** | 决策/路由/工具选择/评分（`TIER_LOCAL_TINY` 语义） |
| **DeepSeek** | 云端生成式 LLM | HTTP API（OpenAI 兼容） | 云端 | 重推理（`TIER_CLOUD`） |

> ⚠️ **关键约束**：NeoHorse-Jev-4B **不能跑在 llama.cpp 上**。
> 它是 `unified multimodal backbone + independent decision pointer head; NOT chat causal LM`
> （见其 `model_manifest.json`），backbone 为 Qwen3.5-4B，需要 PyTorch + CUDA，且
> 官方要求 **Python 3.12 + PyTorch 2.8.0 + Triton 3.7.1 + flash-linear-attention 0.5.2**。
> 它**自带 HTTP 服务**：`neohorse-decision serve --model-dir <dir> --port 8080`
> 接口：`POST /v1/decision`、`POST /v1/systemone`、`GET /health`；可用 `NEOHORSE_API_KEY` 鉴权。

---

## 二、已完成

### ✅ 1. 配置进模拟器产品（17/17 服务注册）

新建产品 `genton_emu`（继承官方 `sdk_phone64_x86_64` + 复用 `genton_overlay.mk`）：

- `device/genton/emulator/AndroidProducts.mk`
- `device/genton/emulator/genton_emu.mk`

lunch：`genton_emu-bp2a-userdebug`（`PRODUCT_BRAND` 不可含空格，否则 fingerprint 非法）

**实测结果**：
```
I GentonServiceRegistry: AI Native OS services registered: ok=17 fail=0
117  com.genton.policy.IPolicyEngine: [com.genton.policy.IPolicyEngine]
```

### ✅ 2. 顺带修复的真实 bug：`PolicyFileParser` success 语义

**症状**：`IPolicyEngine` 永远注册失败
```
IllegalStateException: A7.1 boot policy baseline fail-stop: []
```

**根因**：`LoadResult.success` 默认 `false`，而**全文件 438 行从未将其置为 `true`**；
`addError()` 只负责置 `false`。于是 `parsePolicyPack()` 在
`if (!result.success) return result;` 处**必然提前返回且不产生任何错误** → 报 `[]`。

**修复**：`public boolean success = true;`

> 这类"能编译、不能运行"的缺陷，正是真机/模拟器启动验证（W8）的价值所在。

### ✅ 3. DeepSeek 接线 + 用户设置密钥机制

**新增** `InferenceHttpClient.java`（OpenAI 兼容客户端，DeepSeek 与本地服务通用）：
- 配置读取顺序：**`Settings.Secure`（用户设置） > `/vendor/genton/config/inference_providers.json`（出厂默认）**
- 支持流式分块回调（按中英文句末标点/空白切分）

**接线**（`AgentInferenceRouterService`）：
- `executeCloudInference()` → 桩 `simulateCloudInference()` 替换为真实调用
- `executeLocalInference()` → 桩 `simulateInference()` 替换为真实调用
- 两者**未配置时自动回退原模拟实现**，不破坏既有行为

**用户在「设置」中填写的键**（`Settings.Secure`）：

| 键 | 用途 |
|---|---|
| `genton_inference_cloud_api_key` | DeepSeek API Key |
| `genton_inference_cloud_endpoint` | 云端端点（默认 `https://api.deepseek.com/chat/completions`） |
| `genton_inference_cloud_model` | 云端模型（默认 `deepseek-chat`） |
| `genton_inference_local_endpoint` | 端侧端点（llama.cpp / NeoHorse 服务） |
| `genton_inference_local_model` | 端侧模型名 |

验证方式（无需 UI，adb 即可）：
```bash
adb shell settings put secure genton_inference_cloud_api_key "sk-..."
adb shell settings get secure genton_inference_cloud_api_key
```

---

## 三、环境已就绪

| 项 | 状态 |
|---|---|
| **WSL2 CUDA 直通** | ✅ `/dev/dxg`、`libcuda.so`、`nvidia-smi` → **RTX 5070 Ti 16303 MiB** |
| 网络 | ✅ github 200 / gitee 200 / **hf-mirror 200** / gitclone 200 |
| 本地模型 | ✅ `NeoHorse-Jev-4B` 18 GB、`MiniCPM5-2B` 9.4 GB（WSL 可访问） |
| llama.cpp | 🔄 正在从 gitee 镜像克隆 |
| Python | ⚠️ 系统为 3.14.4；NeoHorse 需 **3.12** → 需单独 venv |

---

## 四、待办

### A. MiniCPM5-2B → GGUF（llama.cpp 路径）
1. 拉取 llama.cpp（进行中）
2. 建 Python 3.12 venv，装 `torch/transformers/sentencepiece` 等转换依赖
3. `python convert_hf_to_gguf.py /mnt/d/DSOM-Transformer/MiniCPM5-2B --outfile minicpm5-2b-f16.gguf`
4. 量化：`llama-quantize ... Q4_K_M`
5. 构建 Android x86_64 版 llama.cpp（需 NDK）→ push 进模拟器 → `llama-server` 起在 `127.0.0.1:8080`
   - 备选：主机跑 llama.cpp，模拟器经 `10.0.2.2` 回连（主机算力强得多，且模拟器仅 4 GB RAM）

### B. NeoHorse-Jev-4B 服务（GPU 主机路径）
1. 建 Python 3.12 venv（CUDA 版 PyTorch 2.8）
2. `pip install --no-deps dist/neohorse_decision-1.0.0-py3-none-any.whl` + fastapi/uvicorn 等
3. `neohorse-decision serve --model-dir /mnt/d/.../NeoHorse-Jev-4B --port 8080`
4. 配置 `genton_inference_local_endpoint` 指向该服务
   - 注意：NeoHorse 接口是 `/v1/decision`（非 OpenAI 兼容），
     需在 `AgentInferenceRouterService` 中为其增加**决策专用适配**（当前 `InferenceHttpClient`
     走的是 `/chat/completions` 生成式协议）

### C. 决策层适配（重要）
NeoHorse 是**决策模型**，语义上应对应「路由/工具选择/条件判断」，
而**不是** `IAgentInferenceRouter.infer()` 的文本生成。
建议：
- 为 `InferenceHttpClient` 增加 `decide()` 方法（POST `/v1/systemone`，传 `state` + `questions`）
- 在 `PolicyEngineService` / 意图路由处调用它，而非走生成路径

---

## 五、架构总结

```
                    ┌─────────────────────────────────────┐
   云端重推理  ────▶ │  DeepSeek (api.deepseek.com)        │  TIER_CLOUD
                    │  用户 API Key（设置中填写）           │
                    └─────────────────────────────────────┘
                                    ▲
                                    │ HTTP (OpenAI 兼容)
                    ┌───────────────┴─────────────────────┐
   端侧生成    ────▶ │  MiniCPM5-2B + llama.cpp            │  TIER_LOCAL_LARGE
                    │  (模拟器内 127.0.0.1:8080)           │
                    └─────────────────────────────────────┘
                                    ▲
                                    │ HTTP (OpenAI 兼容)
                    ┌───────────────┴─────────────────────┐
   Agent 推理路由 ──▶ │  AgentInferenceRouterService        │
   （system_server）  │  InferenceHttpClient（统一客户端）    │
                    └───────────────┬─────────────────────┘
                                    │ HTTP (/v1/decision, 需适配)
                    ┌───────────────┴─────────────────────┐
   端侧决策    ────▶ │  NeoHorse-Jev-4B (vLLM + CUDA)      │  TIER_LOCAL_TINY 语义
                    │  WSL 主机 GPU · RTX 5070 Ti          │  （路由/工具选择/评分）
                    └─────────────────────────────────────┘
```
