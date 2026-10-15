#
# Copyright (C) 2026 AI Native OS Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Enable dynamic partitions
PRODUCT_USE_DYNAMIC_PARTITIONS := true

# Inherit from 64-bit core configuration
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)

# Inherit from generic product configuration
$(call inherit-product, $(SRC_TARGET_DIR)/product/aosp_base_telephony.mk)

# Inherit from device-specific configuration
$(call inherit-product, device/ainative/cpday10/device.mk)

# AI Native OS 定制 overlay（大脑 App / 配置 / 权限）
$(call inherit-product, device/ainative/cpday10/ainative_overlay.mk)

# Product identification
PRODUCT_NAME := aosp_cpday10
PRODUCT_DEVICE := cpday10
PRODUCT_BRAND := AI_Native
PRODUCT_MODEL := CPDAY10
PRODUCT_MANUFACTURER := ainative

# Build fingerprint
PRODUCT_BUILD_PROP_OVERRIDES += \
    PRODUCT_NAME=aosp_cpday10 \
    PRIVATE_BUILD_DESC="aosp_cpday10-ap3a-eng"

# GMS (Google Mobile Services) - placeholder for future integration
# Vendor blobs will be integrated in next phase
