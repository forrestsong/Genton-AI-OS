#
# AI Native OS 定制产品配置（主线2 W6-7）
# 由 genton_overlay.mk 引入：大脑 App、定制配置、权限白名单
#

# ---- 大脑特权 App ----
PRODUCT_PACKAGES += \
    GentonBrain

# ---- 定制配置（vendor 分区，供 services/core 读取）----
PRODUCT_COPY_FILES += \
    vendor/genton/config/action_registry.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/action_registry.json \
    vendor/genton/config/action-registry.schema.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/action-registry.schema.json \
    vendor/genton/config/policy_baseline.dsl:$(TARGET_COPY_OUT_VENDOR)/genton/config/policy_baseline.dsl \
    vendor/genton/config/policy_catalog_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/genton/config/policy_catalog_v1.yaml \
    vendor/genton/config/baseline_policy_rules_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/genton/config/baseline_policy_rules_v1.yaml \
    vendor/genton/config/conflict_matrix_golden_examples_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/genton/config/conflict_matrix_golden_examples_v1.yaml \
    vendor/genton/config/device_capability_table_v1.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/device_capability_table_v1.json \
    vendor/genton/config/decision_thresholds_v1.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/decision_thresholds_v1.json \
    vendor/genton/config/system_activity_whitelist.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/system_activity_whitelist.json \
    vendor/genton/config/value_domain_catalog_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/genton/config/value_domain_catalog_v1.yaml \
    vendor/genton/config/inference_providers.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/inference_providers.json

# ---- 权限白名单 ----
PRODUCT_COPY_FILES += \
    frameworks/base/packages/SystemUI/privapp-permissions-genton-systemui.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-genton-systemui.xml \
    frameworks/base/data/etc/privapp-permissions-genton.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-genton.xml \
    frameworks/base/data/etc/sysconfig-genton.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/sysconfig-genton.xml

# [主线2] vendor 树为最小配置（无完整 MTK blobs），VINTF manifest/matrix 不完整，
#         暂时关闭 VINTF 强制检查；待 MTK vendor 适配完成后恢复为 true
# VINTF 开关在 build/make/core/config.mk 中按产品条件统一设置（此处设置会被覆盖）
