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

# MTK Helio G99 (MT6789) Board Configuration

# Architecture
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-2a
TARGET_CPU_VARIANT := cortex-a76
TARGET_CPU_ABI := arm64-v8a

TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-2a
TARGET_2ND_CPU_VARIANT := cortex-a55
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi

# Board
TARGET_BOARD_PLATFORM := mt6789
TARGET_BOOTLOADER_BOARD_NAME := mt6789

# Kernel
BOARD_KERNEL_CMDLINE := bootopt=64S3,32N2,64N2
BOARD_KERNEL_BASE := 0x40078000
BOARD_KERNEL_PAGESIZE := 2048
BOARD_RAMDISK_OFFSET := 0x11088000
BOARD_KERNEL_TAGS_OFFSET := 0x07c08000

# Partitions (dynamic partitioning)
BOARD_SUPER_PARTITION_SIZE := 9126805504  # 8.5GB
BOARD_SUPER_PARTITION_GROUPS := mtk_dynamic_partitions
# [主线2修复] 虚拟 A/B（virtual_ab_cow_version=3）要求动态分区组大小 < super 分区大小/2；
# 原值 9122611200（≈8.5GB）不满足该校验。实际用量 system1.2G+vendor41M+product303M≈1.55GB，
# 设为 4GiB 既满足校验又留有充足余量。
BOARD_MTK_DYNAMIC_PARTITIONS_SIZE := 4294967296  # 4GiB
BOARD_MTK_DYNAMIC_PARTITIONS_PARTITION_LIST := system vendor product

# File systems
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_USE_F2FS := true
# [主线2修复] AOSP 标准变量名为 *_FILE_SYSTEM_TYPE（原 *_PARTITION_TYPE 为旧式 MTK 变量，
#             导致 vendor 未被纳入动态分区列表、vendor.img 不构建）
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
# [主线2] 独立 vendor 分区（动态分区/MTK 布局），配合 BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE
TARGET_COPY_OUT_VENDOR := vendor
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_PRODUCTIMAGE_FILE_SYSTEM_TYPE := ext4
TARGET_COPY_OUT_PRODUCT := product
BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE := f2fs

# AVB (Android Verified Boot)
BOARD_AVB_ENABLE := true
BOARD_AVB_ROLLBACK_INDEX := 0

# SELinux
BOARD_VENDOR_SEPOLICY_DIRS += device/ainative/cpday10/sepolicy/vendor

# Properties
TARGET_SYSTEM_PROP += device/ainative/cpday10/system.prop

# Display (Real device: 720x720 square screen at 220 DPI)
TARGET_SCREEN_DENSITY := 220

# Audio
USE_XML_AUDIO_POLICY_CONF := 1

# Wi-Fi

# Bluetooth
BOARD_HAVE_BLUETOOTH := true

# Include 64-bit mediaserver
TARGET_DYNAMIC_64_32_MEDIASERVER := true
TARGET_DYNAMIC_64_32_DRMSERVER := true


# Include vendor board configuration
-include vendor/ainative/mt6789/BoardConfigVendor.mk

# Skip kernel build and VINTF check (no prebuilt kernel available)
TARGET_NO_KERNEL := true
BOARD_KERNEL_BINARIES := 

