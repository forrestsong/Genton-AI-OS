#
# AI Native OS 定制产品配置（主线2 W6-7）
# 由 ainative_overlay.mk 引入：大脑 App、定制配置、权限白名单
#

# ---- 大脑特权 App ----
# [主线2] 暂时移出：ActionExecutor 引用了 AIDL 中尚不存在的接口
#         （executeAction/queryActionStatus/ActionStatus），需先补齐契约
# PRODUCT_PACKAGES += \
#     AINativeBrain

# ---- 定制配置（vendor 分区，供 services/core 读取）----
PRODUCT_COPY_FILES += \
    vendor/ainative/config/action_registry.json:$(TARGET_COPY_OUT_VENDOR)/ainative/config/action_registry.json \
    vendor/ainative/config/action-registry.schema.json:$(TARGET_COPY_OUT_VENDOR)/ainative/config/action-registry.schema.json \
    vendor/ainative/config/policy_baseline.dsl:$(TARGET_COPY_OUT_VENDOR)/ainative/config/policy_baseline.dsl \
    vendor/ainative/config/policy_catalog_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/ainative/config/policy_catalog_v1.yaml \
    vendor/ainative/config/baseline_policy_rules_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/ainative/config/baseline_policy_rules_v1.yaml \
    vendor/ainative/config/conflict_matrix_golden_examples_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/ainative/config/conflict_matrix_golden_examples_v1.yaml \
    vendor/ainative/config/device_capability_table_v1.json:$(TARGET_COPY_OUT_VENDOR)/ainative/config/device_capability_table_v1.json \
    vendor/ainative/config/decision_thresholds_v1.json:$(TARGET_COPY_OUT_VENDOR)/ainative/config/decision_thresholds_v1.json \
    vendor/ainative/config/system_activity_whitelist.json:$(TARGET_COPY_OUT_VENDOR)/ainative/config/system_activity_whitelist.json \
    vendor/ainative/config/value_domain_catalog_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/ainative/config/value_domain_catalog_v1.yaml \
    vendor/ainative/config/inference_providers.json:$(TARGET_COPY_OUT_VENDOR)/ainative/config/inference_providers.json

# ---- 权限白名单 ----
PRODUCT_COPY_FILES += \
    frameworks/base/data/etc/privapp-permissions-ainative.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-ainative.xml \
    frameworks/base/data/etc/sysconfig-ainative.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/sysconfig-ainative.xml

# [主线2] vendor 树为最小配置（无完整 MTK blobs），VINTF manifest/matrix 不完整，
#         暂时关闭 VINTF 强制检查；待 MTK vendor 适配完成后恢复为 true
# VINTF 开关在 build/make/core/config.mk 中按产品条件统一设置（此处设置会被覆盖）
