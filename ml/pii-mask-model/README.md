# C4.3 PII 掩码模型训练与部署

## 概述

**任务编号**: C4.3  
**目标**: 训练端侧 PII 检测模型，在 E3.1 评估集上达到 ≥90% 召回率、≥85% 精确率  
**模型类型**: BERT-based NER（命名实体识别）  
**部署平台**: Android（TensorFlow Lite）

## 验收标准（来自 E3.1）

### 性能指标
- 总体召回率（Recall）≥ 90%
- 总体精确率（Precision）≥ 85%
- 金融类召回率 ≥ 95%
- 健康类召回率 ≥ 92%
- 账号类召回率 ≥ 88%

### 性能约束
- 端侧推理延迟：P50 ≤20ms, P95 ≤50ms
- 内存占用：峰值 ≤30MB
- 模型大小：≤10MB（.tflite 文件）
- CPU 占用：单核 ≤30%（推理期间）

## 模型架构

### 基础模型选择

采用 **DistilBERT-base-chinese**（6 层，66M 参数）作为基础模型：
- 预训练中文语料（Wikipedia + 百科）
- 相比 BERT-base 减少 40% 参数，速度提升 60%
- 保留 BERT 97% 的性能

### NER 任务适配

```
输入层: [CLS] 文本 token [SEP]
  ↓
DistilBERT Encoder (6 layers)
  ↓
Token-level Classification Head
  ↓
输出: BIO 标签（21 个 PII 类别）
```

**标签体系**（BIO 标注）:
```python
LABELS = [
    "O",              # Outside（非 PII）
    
    # 金融类（6 个）
    "B-FIN-CARD",     # 银行卡号开始
    "I-FIN-CARD",     # 银行卡号内部
    "B-FIN-CVV",
    "I-FIN-CVV",
    "B-FIN-AMOUNT",
    "I-FIN-AMOUNT",
    # ... (共 12 个标签)
    
    # 健康类（7 个）
    "B-HEALTH-ID",    # 身份证号
    "I-HEALTH-ID",
    "B-HEALTH-DIAG",  # 诊断
    "I-HEALTH-DIAG",
    # ... (共 14 个标签)
    
    # 账号类（7 个）
    "B-ACCOUNT-PHONE",
    "I-ACCOUNT-PHONE",
    "B-ACCOUNT-EMAIL",
    "I-ACCOUNT-EMAIL",
    # ... (共 14 个标签)
]
# 总计：1 (O) + 40 (BIO) = 41 个标签
```

### 模型优化策略

1. **知识蒸馏**：从 BERT-base 蒸馏到 DistilBERT
2. **量化感知训练**：训练时模拟 INT8 量化
3. **动态量化**：推理时权重 INT8，激活 FP32
4. **剪枝**：移除低权重连接（稀疏度 30%）

## 训练流程

### 数据准备

使用 E3.1 评估集 + 生成数据：
```
训练集：10,000 样本（E3.1 提供）
验证集：2,000 样本
测试集：3,000 样本（验收用）
```

### 训练配置

```python
# 超参数
BATCH_SIZE = 16
LEARNING_RATE = 2e-5
EPOCHS = 10
MAX_SEQ_LENGTH = 128
WARMUP_STEPS = 500

# 优化器
AdamW (weight_decay=0.01)

# 学习率调度
Linear warmup + Cosine decay
```

### 训练脚本

```bash
# 1. 数据预处理
python scripts/prepare_data.py \
    --input tests/pii-mask-evaluation-set-v1/ \
    --output ml/pii-mask-model/data/

# 2. 训练模型
python scripts/train_pii_model.py \
    --data_dir ml/pii-mask-model/data/ \
    --output_dir ml/pii-mask-model/checkpoints/ \
    --num_epochs 10

# 3. 转换为 TFLite
python scripts/convert_to_tflite.py \
    --checkpoint ml/pii-mask-model/checkpoints/best_model.pt \
    --output ml/pii-mask-model/pii_mask_model.tflite

# 4. 量化优化
python scripts/quantize_model.py \
    --input ml/pii-mask-model/pii_mask_model.tflite \
    --output ml/pii-mask-model/pii_mask_model_quantized.tflite

# 5. 验收测试
python scripts/evaluate_model.py \
    --model ml/pii-mask-model/pii_mask_model_quantized.tflite \
    --test_data tests/pii-mask-evaluation-set-v1/test_set.jsonl
```

## Android 端推理

### 推理引擎架构

```
PiiMaskModelInference.kt
  ↓
TensorFlow Lite Interpreter
  ↓
PiiMaskRules.kt (fallback)
```

### 使用示例

```kotlin
val inference = PiiMaskModelInference(context)

val text = "我的银行卡号是 6222001234567890"
val result = inference.detectAndMask(text)

// result.maskedText = "我的银行卡号是 6222 **** **** 7890"
// result.entities = [DetectedPii(category=BANK_CARD, ...)]
// result.modelUsed = true (如果模型成功)
```

### Fallback 策略

```kotlin
fun detectAndMask(text: String): MaskResult {
    return try {
        // 尝试模型推理
        val modelResult = runModelInference(text)
        
        if (modelResult.confidence >= 0.6) {
            modelResult
        } else {
            // 置信度低，降级到规则库
            PiiMaskRules.maskText(text)
        }
    } catch (e: Exception) {
        // 模型失败，使用规则库兜底
        Log.w(TAG, "Model inference failed, fallback to rules", e)
        PiiMaskRules.maskText(text)
    }
}
```

## 目录结构

```
ml/pii-mask-model/
├── README.md                           # 本文档
├── requirements.txt                    # Python 依赖
├── data/                               # 训练数据
│   ├── train.jsonl
│   ├── val.jsonl
│   └── test.jsonl
├── scripts/                            # 训练脚本
│   ├── prepare_data.py                 # 数据预处理
│   ├── train_pii_model.py              # 模型训练
│   ├── convert_to_tflite.py            # 转换为 TFLite
│   ├── quantize_model.py               # 量化优化
│   └── evaluate_model.py               # 验收测试
├── checkpoints/                        # 训练检查点
│   └── best_model.pt
├── pii_mask_model.tflite               # 原始模型
├── pii_mask_model_quantized.tflite    # 量化模型（部署用）
└── evaluation_results.json             # 验收结果

overlay/frameworks/base/core/java/com/genton/privacy/
├── PiiMaskRules.kt                     # 规则库（已完成）
├── PiiMaskModelInference.kt            # 模型推理引擎（待实现）
└── PiiMaskService.kt                   # 统一掩码服务（待实现）
```

## 依赖

### Python 依赖

```txt
transformers==4.30.0
torch==2.0.0
tensorflow==2.13.0
scikit-learn==1.3.0
faker==18.10.1
```

### Android 依赖

```gradle
implementation 'org.tensorflow:tensorflow-lite:2.13.0'
implementation 'org.tensorflow:tensorflow-lite-support:0.4.3'
```

## 验收清单

- [ ] 模型训练完成（10 epochs）
- [ ] 总体召回率 ≥ 90%
- [ ] 总体精确率 ≥ 85%
- [ ] 金融类召回率 ≥ 95%
- [ ] 健康类召回率 ≥ 92%
- [ ] 账号类召回率 ≥ 88%
- [ ] 模型大小 ≤ 10MB
- [ ] 推理延迟 P95 ≤ 50ms
- [ ] 内存占用 ≤ 30MB
- [ ] CPU 占用 ≤ 30%
- [ ] Fallback 集成测试通过
- [ ] 端到端集成测试（A5.1 记忆服务）

## 参考文献

1. [E3.1-PII掩码验收基准v1.md](../../docs/security/E3.1-PII掩码验收基准v1.md)
2. [DistilBERT Paper](https://arxiv.org/abs/1910.01108)
3. [TensorFlow Lite Quantization](https://www.tensorflow.org/lite/performance/post_training_quantization)
