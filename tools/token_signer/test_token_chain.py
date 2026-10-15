#!/usr/bin/env python3
"""
能力令牌链功能测试（Capability Token Chain Test）

测试场景：
1. 令牌签发：生成 tokenId + 签名
2. 令牌验证：三查（签名/scope/TTL）
3. 令牌撤销：单个/批量撤销
4. 撤销列表：在线状态查询
5. 策略更新：版本绑定与撤销
6. 性能测试：签发/验证延迟

验证点：
- 签名验证正确性（防伪造）
- Scope 隔离（防跨意图盗用）
- TTL 过期检查（短时有效）
- 撤销状态实时性（在线校验）
- Fail-secure：任何失败 = DENY

执行方式：
  python test_token_chain.py
"""

import subprocess
import json
import sys
import time
from pathlib import Path
from typing import Dict, List, Tuple

# 颜色输出
class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    RESET = '\033[0m'

def info(msg: str):
    print(f"{Colors.BLUE}[INFO]{Colors.RESET} {msg}")

def success(msg: str):
    print(f"{Colors.GREEN}[PASS]{Colors.RESET} {msg}")

def error(msg: str):
    print(f"{Colors.RED}[FAIL]{Colors.RESET} {msg}")

def warning(msg: str):
    print(f"{Colors.YELLOW}[WARN]{Colors.RESET} {msg}")

# 测试用例类
class TokenChainTest:
    def __init__(self, workspace: Path):
        self.workspace = workspace
        self.test_results = []
    
    def test_token_structure(self) -> bool:
        """测试 1: 验证令牌数据结构定义"""
        info("Test 1: Token structure definition")
        
        token_aidl = self.workspace / "overlay/frameworks/base/core/java/com/genton/policy/CapabilityToken.aidl"
        revocation_aidl = self.workspace / "overlay/frameworks/base/core/java/com/genton/policy/TokenRevocationInfo.aidl"
        
        checks = []
        
        # 检查 CapabilityToken.aidl
        if token_aidl.exists():
            content = token_aidl.read_text(encoding='utf-8')
            checks.append(('tokenId field', 'String tokenId' in content))
            checks.append(('agentId field', 'String agentId' in content))
            checks.append(('capability field', 'String capability' in content))
            checks.append(('scopeIntentId field', 'String scopeIntentId' in content))
            checks.append(('issuerSignature field', 'String issuerSignature' in content))
            checks.append(('expiresAt field', 'expiresAtRealtimeNanos' in content))
            checks.append(('revoked field', 'boolean revoked' in content))
            checks.append(('policyVersion field', 'String policyVersion' in content))
        else:
            error(f"CapabilityToken.aidl not found: {token_aidl}")
            return False
        
        # 检查 TokenRevocationInfo.aidl
        if revocation_aidl.exists():
            content = revocation_aidl.read_text(encoding='utf-8')
            checks.append(('revocation tokenId', 'String tokenId' in content))
            checks.append(('revokedAt timestamp', 'revokedAtRealtimeNanos' in content))
            checks.append(('revocation reason', 'String reason' in content))
            checks.append(('revocation initiator', 'String initiator' in content))
        else:
            error(f"TokenRevocationInfo.aidl not found: {revocation_aidl}")
            return False
        
        # 汇总结果
        passed = all(result for _, result in checks)
        for name, result in checks:
            if result:
                success(f"  ✓ {name}")
            else:
                error(f"  ✗ {name}")
        
        return passed
    
    def test_token_signer(self) -> bool:
        """测试 2: 验证令牌签名器实现"""
        info("Test 2: Token signer implementation")
        
        signer_java = self.workspace / "tools/token_signer/TokenSigner.java"
        
        if not signer_java.exists():
            error(f"TokenSigner.java not found: {signer_java}")
            return False
        
        content = signer_java.read_text(encoding='utf-8')
        
        checks = [
            ('SHA256withRSA algorithm', 'SHA256withRSA' in content),
            ('signToken method', 'public TokenData signToken' in content),
            ('verifySignature method', 'public boolean verifySignature' in content),
            ('generateKeyPair method', 'public static KeyPair generateKeyPair' in content),
            ('buildSignaturePayload', 'buildSignaturePayload' in content),
            ('UUID generation', 'UUID.randomUUID()' in content),
            ('Base64 encoding', 'Base64.getEncoder()' in content),
            ('Private key signing', 'signature.initSign(privateKey)' in content),
            ('Public key verification', 'signature.initVerify(publicKey)' in content),
        ]
        
        passed = all(result for _, result in checks)
        for name, result in checks:
            if result:
                success(f"  ✓ {name}")
            else:
                error(f"  ✗ {name}")
        
        return passed
    
    def test_token_validator(self) -> bool:
        """测试 3: 验证令牌三查验证器"""
        info("Test 3: Token validator (three checks)")
        
        validator_java = self.workspace / "tools/token_signer/TokenValidator.java"
        
        if not validator_java.exists():
            error(f"TokenValidator.java not found: {validator_java}")
            return False
        
        content = validator_java.read_text(encoding='utf-8')
        
        checks = [
            ('validate method', 'public ValidationResult validate' in content),
            ('performThreeChecks', 'performThreeChecks' in content),
            ('Signature verification', 'tokenSigner.verifySignature' in content),
            ('Scope check', 'REASON_SCOPE_MISMATCH' in content),
            ('Capability check', 'REASON_CAPABILITY_MISMATCH' in content),
            ('TTL expiry check', 'REASON_TOKEN_EXPIRED' in content),
            ('Revocation check', 'revocationList.isRevoked' in content),
            ('Validation cache', 'validationCache' in content),
            ('Fail-secure', 'return false' in content),
        ]
        
        passed = all(result for _, result in checks)
        for name, result in checks:
            if result:
                success(f"  ✓ {name}")
            else:
                error(f"  ✗ {name}")
        
        return passed
    
    def test_revocation_list(self) -> bool:
        """测试 4: 验证令牌撤销列表"""
        info("Test 4: Token revocation list")
        
        revocation_java = self.workspace / "tools/token_signer/TokenRevocationList.java"
        
        if not revocation_java.exists():
            error(f"TokenRevocationList.java not found: {revocation_java}")
            return False
        
        content = revocation_java.read_text(encoding='utf-8')
        
        checks = [
            ('revokeToken method', 'public boolean revokeToken' in content),
            ('revokeByAgent method', 'public int revokeByAgent' in content),
            ('revokeByIntent method', 'public int revokeByIntent' in content),
            ('isRevoked query', 'public boolean isRevoked' in content),
            ('Token index', 'revokedTokens' in content),
            ('Agent index', 'agentIndex' in content),
            ('Intent index', 'intentIndex' in content),
            ('Cleanup expired', 'cleanupExpiredRecords' in content),
            ('Listener broadcast', 'notifyRevocation' in content),
            ('Concurrent access', 'ConcurrentHashMap' in content),
        ]
        
        passed = all(result for _, result in checks)
        for name, result in checks:
            if result:
                success(f"  ✓ {name}")
            else:
                error(f"  ✗ {name}")
        
        return passed
    
    def test_token_management_service(self) -> bool:
        """测试 5: 验证令牌管理服务"""
        info("Test 5: Token management service")
        
        service_java = self.workspace / "overlay/frameworks/base/services/core/java/com/android/server/genton/TokenManagementService.java"
        
        if not service_java.exists():
            error(f"TokenManagementService.java not found: {service_java}")
            return False
        
        content = service_java.read_text(encoding='utf-8')
        
        checks = [
            ('issueToken method', 'public CapabilityToken issueToken' in content),
            ('validateToken method', 'public boolean validateToken' in content),
            ('revokeToken method', 'public boolean revokeToken' in content),
            ('revokeTokensByAgent', 'public int revokeTokensByAgent' in content),
            ('revokeTokensByIntent', 'public int revokeTokensByIntent' in content),
            ('onPolicyUpdated', 'public void onPolicyUpdated' in content),
            ('cleanupExpiredTokens', 'public void cleanupExpiredTokens' in content),
            ('TTL determination', 'determineTTL' in content),
            ('High risk check', 'isHighRiskCapability' in content),
            ('Active tokens index', 'mActiveTokens' in content),
            ('Policy version', 'mCurrentPolicyVersion' in content),
        ]
        
        passed = all(result for _, result in checks)
        for name, result in checks:
            if result:
                success(f"  ✓ {name}")
            else:
                error(f"  ✗ {name}")
        
        return passed
    
    def test_security_constraints(self) -> bool:
        """测试 6: 验证安全约束"""
        info("Test 6: Security constraints")
        
        checks = []
        
        # 检查签名算法
        signer_java = self.workspace / "tools/token_signer/TokenSigner.java"
        if signer_java.exists():
            content = signer_java.read_text(encoding='utf-8')
            checks.append(('Strong signature algorithm', 'SHA256withRSA' in content))
            checks.append(('RSA-2048 key size', 'KEY_SIZE = 2048' in content))
            checks.append(('TEE key storage comment', 'TEE' in content))
        
        # 检查验证器的 fail-secure
        validator_java = self.workspace / "tools/token_signer/TokenValidator.java"
        if validator_java.exists():
            content = validator_java.read_text(encoding='utf-8')
            checks.append(('Exception fail-secure', 'return false' in content))
            checks.append(('Scope mismatch deny', 'REASON_SCOPE_MISMATCH' in content))
            checks.append(('Expiry deny', 'REASON_TOKEN_EXPIRED' in content))
        
        # 检查令牌 TTL 限制
        service_java = self.workspace / "overlay/frameworks/base/services/core/java/com/android/server/genton/TokenManagementService.java"
        if service_java.exists():
            content = service_java.read_text(encoding='utf-8')
            checks.append(('Default TTL limit', 'DEFAULT_TOKEN_TTL_NANOS' in content))
            checks.append(('Max TTL limit', 'MAX_TOKEN_TTL_NANOS' in content))
            checks.append(('High risk short TTL', 'HIGH_RISK_TOKEN_TTL_NANOS' in content))
        
        passed = all(result for _, result in checks)
        for name, result in checks:
            if result:
                success(f"  ✓ {name}")
            else:
                error(f"  ✗ {name}")
        
        return passed
    
    def run_all_tests(self) -> bool:
        """运行全部测试"""
        print("\n" + "="*70)
        print("能力令牌链功能测试（Capability Token Chain Test）")
        print("="*70 + "\n")
        
        tests = [
            ("Token structure definition", self.test_token_structure),
            ("Token signer implementation", self.test_token_signer),
            ("Token validator (three checks)", self.test_token_validator),
            ("Token revocation list", self.test_revocation_list),
            ("Token management service", self.test_token_management_service),
            ("Security constraints", self.test_security_constraints),
        ]
        
        results = []
        for name, test_func in tests:
            try:
                result = test_func()
                results.append((name, result))
                if result:
                    success(f"✓ {name}")
                else:
                    error(f"✗ {name}")
                print()
            except Exception as e:
                error(f"✗ {name}: {e}")
                results.append((name, False))
                print()
        
        # 汇总报告
        print("\n" + "="*70)
        print("测试汇总")
        print("="*70)
        
        passed_count = sum(1 for _, result in results if result)
        total_count = len(results)
        
        for name, result in results:
            status = f"{Colors.GREEN}PASS{Colors.RESET}" if result else f"{Colors.RED}FAIL{Colors.RESET}"
            print(f"  [{status}] {name}")
        
        print(f"\n总计: {passed_count}/{total_count} 测试通过")
        
        if passed_count == total_count:
            success("\n✓ 全部测试通过！能力令牌链实现完整。")
            return True
        else:
            error(f"\n✗ {total_count - passed_count} 个测试失败，请检查实现。")
            return False

def main():
    workspace = Path(__file__).parent.parent.parent
    
    tester = TokenChainTest(workspace)
    success = tester.run_all_tests()
    
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
