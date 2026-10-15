#
# AI Native OS - x86_64 模拟器验证产品
#
# 目的：在 AOSP 官方 emulator 产品基础上，加入 AI Native OS 的运行时配置，
#       使服务层（含 IPolicyEngine）能在模拟器中完整初始化，用于验证
#       「服务开机注册 + 配置加载」链路（主线2 W8 / B-M2）。
#
$(call inherit-product, device/generic/goldfish/64bitonly/product/sdk_phone64_x86_64.mk)

# 复用 genton 的配置安装规则（与 cpday10 共用同一份 overlay）
$(call inherit-product, device/genton/cpday10/genton_overlay.mk)

PRODUCT_NAME := genton_emu
PRODUCT_DEVICE := emu64x
PRODUCT_BRAND := genton
PRODUCT_MODEL := AI Native OS (x86_64 emulator)
PRODUCT_MANUFACTURER := genton
