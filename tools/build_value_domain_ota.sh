#!/bin/bash
# C6.4 值域知识库 OTA 更新脚本
# 用于打包值域知识库并生成 OTA 包

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 配置
VALUE_DOMAIN_CATALOG="$PROJECT_ROOT/vendor/ainative/config/value_domain_catalog_v1.yaml"
OTA_OUTPUT_DIR="$PROJECT_ROOT/out/value_domain_ota"
SIGNATURE_KEY="$PROJECT_ROOT/keys/platform.pk8"  # 平台签名私钥

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查依赖
check_dependencies() {
    log_info "Checking dependencies..."
    
    if ! command -v python3 &> /dev/null; then
        log_error "python3 not found"
        exit 1
    fi
    
    if ! command -v openssl &> /dev/null; then
        log_error "openssl not found"
        exit 1
    fi
    
    log_info "Dependencies OK"
}

# 计算 SHA256 校验和
calculate_checksum() {
    local file=$1
    openssl dgst -sha256 -hex "$file" | awk '{print $2}'
}

# 生成签名
generate_signature() {
    local file=$1
    local signature_file="$OTA_OUTPUT_DIR/signature.bin"
    
    if [ ! -f "$SIGNATURE_KEY" ]; then
        log_warn "Signature key not found, skipping signing"
        echo ""
        return
    fi
    
    # 使用平台私钥签名
    openssl dgst -sha256 -sign "$SIGNATURE_KEY" -out "$signature_file" "$file"
    
    # 转换为 Base64
    openssl base64 -in "$signature_file" -out "$signature_file.b64"
    cat "$signature_file.b64" | tr -d '\n'
}

# 更新值域知识库元数据
update_metadata() {
    local catalog=$1
    local checksum=$2
    local signature=$3
    
    log_info "Updating metadata in catalog..."
    
    # 使用 Python 脚本更新 YAML 文件
    python3 - <<EOF
import yaml
import sys

catalog_path = "$catalog"

with open(catalog_path, 'r', encoding='utf-8') as f:
    data = yaml.safe_load(f)

if 'metadata' not in data:
    data['metadata'] = {}

data['metadata']['checksum_sha256'] = "$checksum"
data['metadata']['signature'] = "$signature"

with open(catalog_path, 'w', encoding='utf-8') as f:
    yaml.dump(data, f, allow_unicode=True, default_flow_style=False, sort_keys=False)

print("Metadata updated successfully")
EOF
}

# 验证值域知识库格式
validate_catalog() {
    local catalog=$1
    
    log_info "Validating catalog format..."
    
    python3 - <<EOF
import yaml
import sys

catalog_path = "$catalog"

try:
    with open(catalog_path, 'r', encoding='utf-8') as f:
        data = yaml.safe_load(f)
    
    # 检查必需字段
    required_fields = ['version', 'last_updated', 'metadata', 'notification', 'call_gate']
    for field in required_fields:
        if field not in data:
            print(f"Missing required field: {field}")
            sys.exit(1)
    
    # 检查元数据
    metadata = data['metadata']
    required_metadata = ['schema_version', 'classifier_version', 
                        'min_policy_engine_version', 'min_dsl_compiler_version']
    for field in required_metadata:
        if field not in metadata:
            print(f"Missing required metadata: {field}")
            sys.exit(1)
    
    # 检查分类器版本
    classifier_version = metadata['classifier_version']
    if 'notification_classifier' not in classifier_version:
        print("Missing notification_classifier version")
        sys.exit(1)
    if 'spam_scorer' not in classifier_version:
        print("Missing spam_scorer version")
        sys.exit(1)
    
    print("Catalog validation passed")
    
except Exception as e:
    print(f"Validation failed: {e}")
    sys.exit(1)
EOF
    
    if [ $? -ne 0 ]; then
        log_error "Catalog validation failed"
        exit 1
    fi
    
    log_info "Catalog validation OK"
}

# 打包 OTA 包
package_ota() {
    local version=$1
    local ota_package="$OTA_OUTPUT_DIR/value_domain_ota_v${version}.zip"
    
    log_info "Packaging OTA update..."
    
    mkdir -p "$OTA_OUTPUT_DIR"
    
    # 复制值域知识库到 OTA 目录
    cp "$VALUE_DOMAIN_CATALOG" "$OTA_OUTPUT_DIR/"
    
    # 创建 OTA 元数据文件
    cat > "$OTA_OUTPUT_DIR/ota_metadata.json" <<EOF
{
  "package_version": "$version",
  "package_type": "value_domain_update",
  "created_at": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "files": [
    {
      "name": "value_domain_catalog_v1.yaml",
      "checksum": "$(calculate_checksum "$OTA_OUTPUT_DIR/value_domain_catalog_v1.yaml")"
    }
  ]
}
EOF
    
    # 打包成 ZIP
    cd "$OTA_OUTPUT_DIR"
    zip -q "$ota_package" value_domain_catalog_v1.yaml ota_metadata.json
    
    log_info "OTA package created: $ota_package"
    
    # 输出包信息
    ls -lh "$ota_package"
}

# 主流程
main() {
    log_info "Starting value domain OTA packaging..."
    
    # 1. 检查依赖
    check_dependencies
    
    # 2. 验证值域知识库格式
    validate_catalog "$VALUE_DOMAIN_CATALOG"
    
    # 3. 计算校验和
    log_info "Calculating checksum..."
    checksum=$(calculate_checksum "$VALUE_DOMAIN_CATALOG")
    log_info "Checksum: $checksum"
    
    # 4. 生成签名
    log_info "Generating signature..."
    signature=$(generate_signature "$VALUE_DOMAIN_CATALOG")
    if [ -n "$signature" ]; then
        log_info "Signature generated"
    else
        log_warn "No signature generated (development mode)"
    fi
    
    # 5. 更新元数据
    update_metadata "$VALUE_DOMAIN_CATALOG" "$checksum" "$signature"
    
    # 6. 提取版本号
    version=$(python3 -c "import yaml; print(yaml.safe_load(open('$VALUE_DOMAIN_CATALOG'))['version'])")
    log_info "Packaging version: $version"
    
    # 7. 打包 OTA 包
    package_ota "$version"
    
    log_info "Value domain OTA packaging completed successfully!"
}

# 运行
main "$@"
