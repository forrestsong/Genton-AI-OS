#!/usr/bin/env python3
"""
数据预处理脚本 - 将 E3.1 评估集转换为 NER 训练格式

输入：E3.1 评估集 JSONL（test_pii_mask_baseline.py 生成）
输出：BIO 标注的训练数据
"""

import json
import argparse
from pathlib import Path
from typing import List, Dict, Tuple
from collections import Counter

# PII 类别到 BIO 标签的映射
CATEGORY_TO_LABEL = {
    "BANK_CARD": "FIN-CARD",
    "CVV": "FIN-CVV",
    "AMOUNT": "FIN-AMOUNT",
    "TRANSACTION": "FIN-TRANS",
    
    "ID_CARD": "HEALTH-ID",
    "PASSPORT": "HEALTH-PASSPORT",
    "DIAGNOSIS": "HEALTH-DIAG",
    "MEDICINE": "HEALTH-MED",
    
    "PHONE": "ACCOUNT-PHONE",
    "EMAIL": "ACCOUNT-EMAIL",
    "IP_ADDRESS": "ACCOUNT-IP",
    "API_KEY": "ACCOUNT-KEY",
    "PASSWORD": "ACCOUNT-PASS",
}

class DataPreprocessor:
    """数据预处理器"""
    
    def __init__(self):
        self.label_to_id = self._build_label_vocab()
        self.id_to_label = {v: k for k, v in self.label_to_id.items()}
    
    def _build_label_vocab(self) -> Dict[str, int]:
        """构建标签词表"""
        labels = ["O"]  # Outside
        
        # 为每个类别生成 B- 和 I- 标签
        for pii_type in CATEGORY_TO_LABEL.values():
            labels.append(f"B-{pii_type}")
            labels.append(f"I-{pii_type}")
        
        return {label: idx for idx, label in enumerate(labels)}
    
    def process_sample(self, sample: Dict) -> Dict:
        """处理单个样本，生成 BIO 标签"""
        text = sample["text"]
        entities = sample.get("entities", [])
        
        # 初始化标签为 O
        tokens = list(text)  # 字符级分词（中文）
        labels = ["O"] * len(tokens)
        
        # 为每个实体标注 BIO 标签
        for entity in entities:
            pii_type = entity.get("type", "UNKNOWN")
            value = entity["value"]
            
            if pii_type not in CATEGORY_TO_LABEL:
                continue
            
            # 查找实体在文本中的位置
            start_idx = text.find(value)
            if start_idx == -1:
                continue
            
            end_idx = start_idx + len(value)
            bio_label = CATEGORY_TO_LABEL[pii_type]
            
            # 标注 B- 和 I-
            labels[start_idx] = f"B-{bio_label}"
            for i in range(start_idx + 1, end_idx):
                labels[i] = f"I-{bio_label}"
        
        return {
            "id": sample.get("id", "unknown"),
            "tokens": tokens,
            "labels": labels,
            "original_text": text,
            "category": sample.get("category", "unknown")
        }
    
    def convert_to_training_format(self, input_file: Path, output_file: Path):
        """转换为训练格式"""
        processed_samples = []
        label_counts = Counter()
        
        print(f"📖 读取数据：{input_file}")
        
        with open(input_file, 'r', encoding='utf-8') as f:
            for line_no, line in enumerate(f, 1):
                try:
                    sample = json.loads(line)
                    processed = self.process_sample(sample)
                    processed_samples.append(processed)
                    
                    # 统计标签分布
                    for label in processed["labels"]:
                        label_counts[label] += 1
                        
                except Exception as e:
                    print(f"⚠️  第 {line_no} 行处理失败: {e}")
        
        # 保存处理后的数据
        output_file.parent.mkdir(parents=True, exist_ok=True)
        
        with open(output_file, 'w', encoding='utf-8') as f:
            for sample in processed_samples:
                f.write(json.dumps(sample, ensure_ascii=False) + '\n')
        
        print(f"✅ 数据已保存：{output_file}")
        print(f"   样本数：{len(processed_samples)}")
        print(f"\n📊 标签分布：")
        for label, count in label_counts.most_common(10):
            print(f"   {label}: {count}")
    
    def save_label_vocab(self, output_dir: Path):
        """保存标签词表"""
        vocab_file = output_dir / "label_vocab.json"
        vocab_file.parent.mkdir(parents=True, exist_ok=True)
        
        with open(vocab_file, 'w', encoding='utf-8') as f:
            json.dump({
                "label_to_id": self.label_to_id,
                "id_to_label": self.id_to_label,
                "num_labels": len(self.label_to_id)
            }, f, indent=2, ensure_ascii=False)
        
        print(f"✅ 标签词表已保存：{vocab_file}")
        print(f"   标签数：{len(self.label_to_id)}")


def main():
    parser = argparse.ArgumentParser(description="PII 掩码数据预处理")
    parser.add_argument('--input', type=str, required=True,
                       help="输入目录（E3.1 评估集）")
    parser.add_argument('--output', type=str, required=True,
                       help="输出目录（训练数据）")
    args = parser.parse_args()
    
    input_dir = Path(args.input)
    output_dir = Path(args.output)
    
    preprocessor = DataPreprocessor()
    
    # 处理训练集（如果存在）
    train_input = input_dir / "train_set.jsonl"
    if train_input.exists():
        preprocessor.convert_to_training_format(
            train_input,
            output_dir / "train.jsonl"
        )
    
    # 处理验证集
    val_input = input_dir / "val_set.jsonl"
    if val_input.exists():
        preprocessor.convert_to_training_format(
            val_input,
            output_dir / "val.jsonl"
        )
    
    # 处理测试集
    test_input = input_dir / "test_set.jsonl"
    if test_input.exists():
        preprocessor.convert_to_training_format(
            test_input,
            output_dir / "test.jsonl"
        )
    else:
        print(f"⚠️  测试集不存在：{test_input}")
        print("   请先运行：python tools/test_pii_mask_baseline.py --mode generate")
    
    # 保存标签词表
    preprocessor.save_label_vocab(output_dir)
    
    print("\n✅ 数据预处理完成！")


if __name__ == '__main__':
    main()
