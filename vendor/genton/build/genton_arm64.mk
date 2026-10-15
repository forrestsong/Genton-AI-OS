# Genton 产品配置（主线2 W6-7 完整镜像构建）
# 继承 aosp_arm64，追加 Genton 定制包与配置

$(call inherit-product, $(SRC_TARGET_DIR)/product/aosp_arm64.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)

PRODUCT_NAME := genton_arm64
PRODUCT_DEVICE := genton_arm64
PRODUCT_BRAND := genton
PRODUCT_MODEL := Genton
PRODUCT_MANUFACTURER := genton

# ---- Genton 定制包 ----
PRODUCT_PACKAGES += \
    GentonBrain \
    permissions_com.genton.brain.xml

# AIDL 接口模块（Java 后端产物随 framework 进入 system.img）
PRODUCT_PACKAGES += \
    genton.perception \
    genton.action \
    genton.agent \
    genton.audit \
    genton.memory \
    genton.policy \
    genton.events \
    genton.context \
    genton.execution \
    genton.inference \
    genton.assets \
    genton.macro \
    genton.decision \
    genton.ui \
    genton.call \
    genton.motion \
    genton.sandbox \
    genton.core

# ---- 定制配置文件（vendor 分区）----
PRODUCT_COPY_FILES += \
    vendor/genton/config/action_registry.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/action_registry.json \
    vendor/genton/config/policy_baseline.dsl:$(TARGET_COPY_OUT_VENDOR)/genton/config/policy_baseline.dsl \
    vendor/genton/config/policy_catalog_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/genton/config/policy_catalog_v1.yaml \
    vendor/genton/config/device_capability_table_v1.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/device_capability_table_v1.json \
    vendor/genton/config/decision_thresholds_v1.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/decision_thresholds_v1.json \
    vendor/genton/config/system_activity_whitelist.json:$(TARGET_COPY_OUT_VENDOR)/genton/config/system_activity_whitelist.json \
    vendor/genton/config/value_domain_catalog_v1.yaml:$(TARGET_COPY_OUT_VENDOR)/genton/config/value_domain_catalog_v1.yaml \
    frameworks/base/data/etc/privapp-permissions-genton.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-genton.xml \
    frameworks/base/data/etc/sysconfig-genton.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/sysconfig-genton.xml
