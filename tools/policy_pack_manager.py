#!/usr/bin/env python3
"""
PolicyPack 签名打包与灰度工具链
实现 D17 §6 规范

功能：
- PolicyPack 打包（规则集 + 元数据）
- 平台签名与验证
- 版本管理
- 灰度发布
- 回滚策略
"""

import yaml
import json
import hashlib
import hmac
import time
from pathlib import Path
from typing import Dict, List, Any, Optional
from dataclasses import dataclass, field, asdict
from enum import Enum


# ============================================================
# 常量定义
# ============================================================

class PackSource(Enum):
    """策略包来源"""
    PLATFORM = "platform"      # 平台 OTA
    USER = "user"              # 用户配置（不签名）
    LEARN = "learn"            # 学习建议（系统签名）


class RolloutStage(Enum):
    """灰度阶段"""
    CANARY = "canary"          # 金丝雀（1%）
    BETA = "beta"              # Beta（10%）
    STABLE = "stable"          # 稳定（100%）
    ROLLBACK = "rollback"      # 回滚


# ============================================================
# 数据结构
# ============================================================

@dataclass
class PolicyPackMetadata:
    """PolicyPack 元数据"""
    version: str                    # 版本号（语义化版本）
    min_engine_version: str         # 最低引擎版本
    source: str                     # platform/user/learn
    created_at: float               # 创建时间戳
    author: str = "platform"        # 作者
    description: str = ""           # 描述
    baseline_count: int = 0         # baseline 规则数
    user_count: int = 0             # user 规则数
    learn_count: int = 0            # learn 规则数
    total_count: int = 0            # 总规则数


@dataclass
class PolicyPack:
    """策略包"""
    metadata: PolicyPackMetadata
    rules: List[Dict[str, Any]]
    signature: Optional[str] = None
    checksum: Optional[str] = None  # SHA256 校验和
    
    def to_dict(self) -> Dict[str, Any]:
        """转换为字典"""
        return {
            'metadata': asdict(self.metadata),
            'rules': self.rules,
            'signature': self.signature,
            'checksum': self.checksum
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'PolicyPack':
        """从字典创建"""
        metadata = PolicyPackMetadata(**data['metadata'])
        return cls(
            metadata=metadata,
            rules=data['rules'],
            signature=data.get('signature'),
            checksum=data.get('checksum')
        )


@dataclass
class RolloutConfig:
    """灰度配置"""
    stage: RolloutStage
    rollout_percentage: int         # 灰度百分比（0-100）
    target_devices: List[str]       # 目标设备 ID 列表（为空则按百分比）
    min_success_rate: float         # 最低成功率（低于此值自动回滚）
    monitor_duration_hours: int     # 监控时长（小时）
    auto_promote: bool              # 自动晋升到下一阶段
    
    def to_dict(self) -> Dict[str, Any]:
        """转换为字典"""
        return {
            'stage': self.stage.value,
            'rollout_percentage': self.rollout_percentage,
            'target_devices': self.target_devices,
            'min_success_rate': self.min_success_rate,
            'monitor_duration_hours': self.monitor_duration_hours,
            'auto_promote': self.auto_promote
        }


@dataclass
class RolloutStatus:
    """灰度状态"""
    pack_version: str
    stage: RolloutStage
    deployed_count: int             # 已部署设备数
    success_count: int              # 成功设备数
    failed_count: int               # 失败设备数
    rollback_count: int             # 回滚设备数
    success_rate: float             # 成功率
    started_at: float               # 开始时间
    last_updated: float             # 最后更新时间


# ============================================================
# PolicyPack 打包器
# ============================================================

class PolicyPackBuilder:
    """PolicyPack 打包器"""
    
    def __init__(self, config_dir: str = "vendor/genton/config"):
        self.config_dir = Path(config_dir)
        # 平台签名密钥（简化版，实际应从 TEE 获取）
        self.platform_key = b"platform-signing-key-placeholder"
    
    def build_pack(
        self,
        version: str,
        min_engine_version: str,
        source: PackSource,
        rule_files: List[str],
        description: str = ""
    ) -> PolicyPack:
        """
        构建 PolicyPack
        
        Args:
            version: 版本号（如 "1.0.0"）
            min_engine_version: 最低引擎版本
            source: 策略包来源
            rule_files: 规则文件路径列表
            description: 描述
        
        Returns:
            PolicyPack 对象
        """
        # 1. 加载规则
        rules = self._load_rules(rule_files)
        
        # 2. 统计规则
        baseline_count = sum(1 for r in rules if r.get('baseline', False))
        user_count = sum(1 for r in rules if r.get('id', '').startswith('user.'))
        learn_count = sum(1 for r in rules if r.get('id', '').startswith('learn.'))
        
        # 3. 创建元数据
        metadata = PolicyPackMetadata(
            version=version,
            min_engine_version=min_engine_version,
            source=source.value,
            created_at=time.time(),
            author="platform" if source == PackSource.PLATFORM else source.value,
            description=description,
            baseline_count=baseline_count,
            user_count=user_count,
            learn_count=learn_count,
            total_count=len(rules)
        )
        
        # 4. 创建策略包
        pack = PolicyPack(metadata=metadata, rules=rules)
        
        # 5. 计算校验和
        pack.checksum = self._calculate_checksum(pack)
        
        return pack
    
    def _load_rules(self, rule_files: List[str]) -> List[Dict[str, Any]]:
        """加载规则文件"""
        all_rules = []
        
        for file_path in rule_files:
            path = Path(file_path)
            if not path.exists():
                print(f"警告: 规则文件不存在: {file_path}")
                continue
            
            with open(path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            # 支持多文档 YAML
            try:
                docs = list(yaml.safe_load_all(content))
                for doc in docs:
                    if not doc:
                        continue
                    
                    # 单个规则字典
                    if isinstance(doc, dict) and 'id' in doc:
                        all_rules.append(doc)
                    # 规则列表
                    elif isinstance(doc, list):
                        for item in doc:
                            if isinstance(item, dict) and 'id' in item:
                                all_rules.append(item)
                    # 嵌套结构（如 action_baseline_rules.rules）
                    elif isinstance(doc, dict):
                        # 提取 rules 字段
                        if 'rules' in doc and isinstance(doc['rules'], list):
                            for rule in doc['rules']:
                                if isinstance(rule, dict) and 'id' in rule:
                                    all_rules.append(rule)
                        # 递归查找所有规则
                        else:
                            self._extract_rules_recursive(doc, all_rules)
            except Exception as e:
                print(f"警告: 解析规则文件失败 {file_path}: {e}")
        
        return all_rules
    
    def _extract_rules_recursive(self, obj: Any, rules: List[Dict[str, Any]]):
        """递归提取规则"""
        if isinstance(obj, dict):
            # 如果是规则对象
            if 'id' in obj and 'domain' in obj:
                rules.append(obj)
            # 如果有 rules 字段
            elif 'rules' in obj and isinstance(obj['rules'], list):
                for rule in obj['rules']:
                    if isinstance(rule, dict) and 'id' in rule:
                        rules.append(rule)
            # 递归其他字段
            else:
                for value in obj.values():
                    if isinstance(value, (dict, list)):
                        self._extract_rules_recursive(value, rules)
        elif isinstance(obj, list):
            for item in obj:
                self._extract_rules_recursive(item, rules)
    
    def _calculate_checksum(self, pack: PolicyPack) -> str:
        """计算策略包校验和"""
        # 排除 signature 和 checksum 字段
        pack_data = {
            'metadata': asdict(pack.metadata),
            'rules': pack.rules
        }
        
        # 序列化为 JSON（确定性顺序）
        json_str = json.dumps(pack_data, sort_keys=True, ensure_ascii=False)
        
        # SHA256
        return hashlib.sha256(json_str.encode('utf-8')).hexdigest()
    
    def sign_pack(self, pack: PolicyPack) -> str:
        """
        签名策略包
        
        使用 HMAC-SHA256 签名（简化版）
        实际应使用 RSA/ECDSA + TEE
        
        Returns:
            签名字符串
        """
        # 签名内容：version + checksum
        message = f"{pack.metadata.version}:{pack.checksum}"
        
        signature = hmac.new(
            self.platform_key,
            message.encode('utf-8'),
            hashlib.sha256
        ).hexdigest()
        
        pack.signature = signature
        return signature
    
    def verify_signature(self, pack: PolicyPack) -> bool:
        """验证签名"""
        if not pack.signature or not pack.checksum:
            return False
        
        # 重新计算签名
        message = f"{pack.metadata.version}:{pack.checksum}"
        expected_signature = hmac.new(
            self.platform_key,
            message.encode('utf-8'),
            hashlib.sha256
        ).hexdigest()
        
        return pack.signature == expected_signature
    
    def save_pack(self, pack: PolicyPack, output_path: str):
        """保存策略包到文件"""
        output = Path(output_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        
        with open(output, 'w', encoding='utf-8') as f:
            json.dump(pack.to_dict(), f, indent=2, ensure_ascii=False)
        
        print(f"✓ 策略包已保存: {output}")
    
    def load_pack(self, pack_path: str) -> PolicyPack:
        """从文件加载策略包"""
        with open(pack_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        return PolicyPack.from_dict(data)


# ============================================================
# 灰度发布管理器
# ============================================================

class RolloutManager:
    """灰度发布管理器"""
    
    def __init__(self, state_dir: str = "vendor/genton/rollout"):
        self.state_dir = Path(state_dir)
        self.state_dir.mkdir(parents=True, exist_ok=True)
        
        # 灰度配置
        self.rollout_configs: Dict[str, RolloutConfig] = {}
        
        # 灰度状态
        self.rollout_status: Dict[str, RolloutStatus] = {}
    
    def create_rollout(
        self,
        pack_version: str,
        stage: RolloutStage = RolloutStage.CANARY,
        rollout_percentage: int = 1,
        target_devices: Optional[List[str]] = None,
        min_success_rate: float = 0.95,
        monitor_duration_hours: int = 24,
        auto_promote: bool = False
    ) -> RolloutConfig:
        """
        创建灰度发布配置
        
        Args:
            pack_version: 策略包版本
            stage: 灰度阶段
            rollout_percentage: 灰度百分比
            target_devices: 目标设备列表（为空则按百分比）
            min_success_rate: 最低成功率
            monitor_duration_hours: 监控时长
            auto_promote: 自动晋升
        
        Returns:
            RolloutConfig 对象
        """
        config = RolloutConfig(
            stage=stage,
            rollout_percentage=rollout_percentage,
            target_devices=target_devices or [],
            min_success_rate=min_success_rate,
            monitor_duration_hours=monitor_duration_hours,
            auto_promote=auto_promote
        )
        
        self.rollout_configs[pack_version] = config
        
        # 初始化状态
        status = RolloutStatus(
            pack_version=pack_version,
            stage=stage,
            deployed_count=0,
            success_count=0,
            failed_count=0,
            rollback_count=0,
            success_rate=0.0,
            started_at=time.time(),
            last_updated=time.time()
        )
        
        self.rollout_status[pack_version] = status
        
        # 保存状态
        self._save_state()
        
        return config
    
    def update_rollout_status(
        self,
        pack_version: str,
        deployed_count: int,
        success_count: int,
        failed_count: int,
        rollback_count: int = 0
    ):
        """更新灰度状态"""
        if pack_version not in self.rollout_status:
            print(f"警告: 灰度版本不存在: {pack_version}")
            return
        
        status = self.rollout_status[pack_version]
        status.deployed_count = deployed_count
        status.success_count = success_count
        status.failed_count = failed_count
        status.rollback_count = rollback_count
        status.success_rate = success_count / deployed_count if deployed_count > 0 else 0.0
        status.last_updated = time.time()
        
        # 保存状态
        self._save_state()
        
        # 检查是否需要回滚
        self._check_auto_rollback(pack_version)
    
    def _check_auto_rollback(self, pack_version: str):
        """检查是否需要自动回滚"""
        config = self.rollout_configs.get(pack_version)
        status = self.rollout_status.get(pack_version)
        
        if not config or not status:
            return
        
        # 成功率低于阈值
        if status.success_rate < config.min_success_rate:
            print(f"⚠ 警告: 版本 {pack_version} 成功率 {status.success_rate:.2%} 低于阈值 {config.min_success_rate:.2%}")
            print(f"  建议执行回滚操作")
    
    def promote_stage(self, pack_version: str) -> bool:
        """
        晋升到下一阶段
        
        CANARY (1%) → BETA (10%) → STABLE (100%)
        
        Returns:
            是否晋升成功
        """
        config = self.rollout_configs.get(pack_version)
        status = self.rollout_status.get(pack_version)
        
        if not config or not status:
            print(f"✗ 灰度版本不存在: {pack_version}")
            return False
        
        # 检查成功率
        if status.success_rate < config.min_success_rate:
            print(f"✗ 成功率不足，无法晋升: {status.success_rate:.2%} < {config.min_success_rate:.2%}")
            return False
        
        # 晋升
        current_stage = config.stage
        if current_stage == RolloutStage.CANARY:
            config.stage = RolloutStage.BETA
            config.rollout_percentage = 10
            print(f"✓ 晋升到 BETA 阶段 (10%)")
        elif current_stage == RolloutStage.BETA:
            config.stage = RolloutStage.STABLE
            config.rollout_percentage = 100
            print(f"✓ 晋升到 STABLE 阶段 (100%)")
        else:
            print(f"✓ 已是 STABLE 阶段")
            return True
        
        status.stage = config.stage
        self._save_state()
        
        return True
    
    def rollback(self, pack_version: str, target_version: str):
        """
        回滚到目标版本
        
        Args:
            pack_version: 当前版本
            target_version: 目标版本
        """
        status = self.rollout_status.get(pack_version)
        if status:
            status.stage = RolloutStage.ROLLBACK
            self._save_state()
        
        print(f"✓ 版本 {pack_version} 回滚到 {target_version}")
    
    def get_status(self, pack_version: str) -> Optional[RolloutStatus]:
        """获取灰度状态"""
        return self.rollout_status.get(pack_version)
    
    def list_rollouts(self) -> List[RolloutStatus]:
        """列出所有灰度发布"""
        return list(self.rollout_status.values())
    
    def _save_state(self):
        """保存灰度状态到文件"""
        state_file = self.state_dir / "rollout_state.json"
        
        state = {
            'configs': {
                version: config.to_dict()
                for version, config in self.rollout_configs.items()
            },
            'status': {
                version: {
                    'pack_version': status.pack_version,
                    'stage': status.stage.value,
                    'deployed_count': status.deployed_count,
                    'success_count': status.success_count,
                    'failed_count': status.failed_count,
                    'rollback_count': status.rollback_count,
                    'success_rate': status.success_rate,
                    'started_at': status.started_at,
                    'last_updated': status.last_updated
                }
                for version, status in self.rollout_status.items()
            }
        }
        
        with open(state_file, 'w', encoding='utf-8') as f:
            json.dump(state, f, indent=2)


# ============================================================
# 版本管理器
# ============================================================

class VersionManager:
    """版本管理器"""
    
    def __init__(self, versions_dir: str = "vendor/genton/policy_versions"):
        self.versions_dir = Path(versions_dir)
        self.versions_dir.mkdir(parents=True, exist_ok=True)
        
        # 版本历史
        self.versions: List[str] = []
        self.current_version: Optional[str] = None
        self.previous_version: Optional[str] = None
        
        self._load_history()
    
    def register_version(self, version: str, pack_path: str):
        """
        注册新版本
        
        Args:
            version: 版本号
            pack_path: 策略包文件路径
        """
        # 更新历史
        if version not in self.versions:
            self.versions.append(version)
        
        # 更新当前版本
        self.previous_version = self.current_version
        self.current_version = version
        
        # 复制策略包到版本目录
        version_dir = self.versions_dir / version
        version_dir.mkdir(parents=True, exist_ok=True)
        
        import shutil
        target_path = version_dir / "policy_pack.json"
        shutil.copy(pack_path, target_path)
        
        # 保存历史
        self._save_history()
        
        print(f"✓ 版本 {version} 已注册")
    
    def get_version_path(self, version: str) -> Optional[Path]:
        """获取版本策略包路径"""
        version_dir = self.versions_dir / version
        pack_path = version_dir / "policy_pack.json"
        
        if pack_path.exists():
            return pack_path
        return None
    
    def rollback_to_previous(self) -> Optional[str]:
        """回滚到上一版本"""
        if not self.previous_version:
            print("✗ 没有可回滚的版本")
            return None
        
        print(f"✓ 回滚到版本: {self.previous_version}")
        
        # 交换当前和上一版本
        self.current_version, self.previous_version = self.previous_version, self.current_version
        self._save_history()
        
        return self.current_version
    
    def list_versions(self) -> List[str]:
        """列出所有版本"""
        return self.versions
    
    def _load_history(self):
        """加载版本历史"""
        history_file = self.versions_dir / "version_history.json"
        if history_file.exists():
            with open(history_file, 'r', encoding='utf-8') as f:
                data = json.load(f)
                self.versions = data.get('versions', [])
                self.current_version = data.get('current_version')
                self.previous_version = data.get('previous_version')
    
    def _save_history(self):
        """保存版本历史"""
        history_file = self.versions_dir / "version_history.json"
        
        data = {
            'versions': self.versions,
            'current_version': self.current_version,
            'previous_version': self.previous_version
        }
        
        with open(history_file, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)


# ============================================================
# CLI 工具
# ============================================================

def main():
    """命令行工具入口"""
    import argparse
    
    parser = argparse.ArgumentParser(description='PolicyPack 签名打包与灰度工具')
    subparsers = parser.add_subparsers(dest='command', help='子命令')
    
    # build 命令
    build_parser = subparsers.add_parser('build', help='构建策略包')
    build_parser.add_argument('--version', required=True, help='版本号')
    build_parser.add_argument('--min-engine', default='1.0.0', help='最低引擎版本')
    build_parser.add_argument('--source', default='platform', choices=['platform', 'user', 'learn'], help='策略包来源')
    build_parser.add_argument('--rules', nargs='+', required=True, help='规则文件路径')
    build_parser.add_argument('--output', required=True, help='输出文件路径')
    build_parser.add_argument('--sign', action='store_true', help='签名策略包')
    build_parser.add_argument('--description', default='', help='描述')
    
    # verify 命令
    verify_parser = subparsers.add_parser('verify', help='验证策略包签名')
    verify_parser.add_argument('pack_path', help='策略包文件路径')
    
    # rollout 命令
    rollout_parser = subparsers.add_parser('rollout', help='创建灰度发布')
    rollout_parser.add_argument('--version', required=True, help='策略包版本')
    rollout_parser.add_argument('--stage', default='canary', choices=['canary', 'beta', 'stable'], help='灰度阶段')
    rollout_parser.add_argument('--percentage', type=int, default=1, help='灰度百分比')
    
    args = parser.parse_args()
    
    if args.command == 'build':
        builder = PolicyPackBuilder()
        
        # 构建策略包
        pack = builder.build_pack(
            version=args.version,
            min_engine_version=args.min_engine,
            source=PackSource(args.source),
            rule_files=args.rules,
            description=args.description
        )
        
        # 签名
        if args.sign:
            builder.sign_pack(pack)
            print(f"✓ 策略包已签名")
        
        # 保存
        builder.save_pack(pack, args.output)
        
        print(f"\n策略包信息:")
        print(f"  版本: {pack.metadata.version}")
        print(f"  规则数: {pack.metadata.total_count}")
        print(f"  baseline: {pack.metadata.baseline_count}")
        print(f"  user: {pack.metadata.user_count}")
        print(f"  校验和: {pack.checksum[:16]}...")
        if pack.signature:
            print(f"  签名: {pack.signature[:16]}...")
    
    elif args.command == 'verify':
        builder = PolicyPackBuilder()
        
        # 加载策略包
        pack = builder.load_pack(args.pack_path)
        
        # 验证签名
        if pack.signature:
            is_valid = builder.verify_signature(pack)
            if is_valid:
                print(f"✓ 签名验证通过")
            else:
                print(f"✗ 签名验证失败")
        else:
            print(f"✗ 策略包未签名")
    
    elif args.command == 'rollout':
        manager = RolloutManager()
        
        # 创建灰度发布
        stage = RolloutStage(args.stage)
        config = manager.create_rollout(
            pack_version=args.version,
            stage=stage,
            rollout_percentage=args.percentage
        )
        
        print(f"✓ 灰度发布已创建:")
        print(f"  版本: {args.version}")
        print(f"  阶段: {config.stage.value}")
        print(f"  百分比: {config.rollout_percentage}%")


if __name__ == "__main__":
    main()
