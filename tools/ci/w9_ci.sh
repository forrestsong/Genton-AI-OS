#!/bin/bash
# ============================================================
# 主线2 W9：构建回归与 CI 固化（aosp_cpday10）
#
# 用法:
#   bash w9_ci.sh              # 构建 + 验收
#   bash w9_ci.sh --verify-only  # 仅验收（不构建）
#   AOSP_ROOT=/path bash w9_ci.sh
#
# 退出码: 0=通过, 非0=失败（可直接用作 CI 门禁）
# ============================================================
set -o pipefail

AOSP="${AOSP_ROOT:-/root/aosp}"
LUNCH_TARGET="${LUNCH_TARGET:-aosp_cpday10-bp2a-eng}"   # 注意：必须 bp2a（Android 16）
JOBS="${JOBS:-20}"
VERIFY_ONLY=0
[ "$1" = "--verify-only" ] && VERIFY_ONLY=1

REPORT_DIR="$AOSP/out/ainative-reports"
mkdir -p "$REPORT_DIR"
STAMP=$(date '+%Y%m%d-%H%M%S')
LOG="$REPORT_DIR/build-$STAMP.log"
JSON="$REPORT_DIR/verify-$STAMP.json"

echo "============================================================"
echo "[W9] 主线2 构建回归与 CI 固化"
echo "  开始     : $(date '+%F %T')"
echo "  AOSP     : $AOSP"
echo "  lunch    : $LUNCH_TARGET"
echo "  模式     : $([ $VERIFY_ONLY -eq 1 ] && echo '仅验收' || echo '构建+验收')"
echo "============================================================"

BUILD_RC=0
if [ $VERIFY_ONLY -eq 0 ]; then
    cd "$AOSP" || exit 1
    source build/envsetup.sh >/dev/null 2>&1
    lunch "$LUNCH_TARGET" >/dev/null 2>&1
    if [ -z "$TARGET_PRODUCT" ]; then
        echo "[ERR] lunch 失败: $LUNCH_TARGET" | tee -a "$LOG"
        exit 2
    fi
    # 开发期 ABI 绕过（正式发布须生成 ABI 参考转储，见 README）
    export SKIP_ABI_CHECKS=true

    echo ""
    echo "[1/2] 完整构建 droid (m -j$JOBS)"
    S=$(date +%s)
    m -j"$JOBS" droid > "$LOG" 2>&1
    BUILD_RC=$?
    E=$(date +%s)
    echo "  退出码: $BUILD_RC, 耗时: $((E-S))s"
    echo "  日志  : $LOG"
    if [ $BUILD_RC -ne 0 ]; then
        echo "  失败目标:"
        grep '^FAILED' "$LOG" | head -5
    fi
else
    echo ""
    echo "[1/2] 跳过构建（--verify-only）"
fi

echo ""
echo "[2/2] 产物验收校验"
VERIFY_RC=0
python3 "$(dirname "$0")/w9_verify.py" "$AOSP" --json "$JSON" || VERIFY_RC=$?

# ---- 生成汇总报告 ----
REPORT="$REPORT_DIR/w9-report-$STAMP.md"
{
    echo "# 主线2 W9 构建回归报告"
    echo ""
    echo "| 项 | 值 |"
    echo "|---|---|"
    echo "| 时间 | $(date '+%F %T') |"
    echo "| AOSP 根 | \`$AOSP\` |"
    echo "| lunch | \`$LUNCH_TARGET\` |"
    echo "| SKIP_ABI_CHECKS | true（开发期绕过） |"
    echo "| 构建退出码 | $BUILD_RC |"
    echo "| 验收退出码 | $VERIFY_RC |"
    echo "| 构建日志 | \`$LOG\` |"
    echo "| 验收 JSON | \`$JSON\` |"
    echo ""
    echo "## 镜像产物"
    echo ""
    ls -lh "$AOSP/out/target/product/cpday10/"*.img 2>/dev/null | \
        awk '{printf "- `%s`  %s\n", $9, $5}'
} > "$REPORT"

echo ""
echo "============================================================"
if [ $BUILD_RC -eq 0 ] && [ $VERIFY_RC -eq 0 ]; then
    echo "[W9] ✅ 全部通过"
    RC=0
else
    echo "[W9] ❌ 失败  (构建=$BUILD_RC, 验收=$VERIFY_RC)"
    RC=1
fi
echo "  报告: $REPORT"
echo "  结束: $(date '+%F %T')"
echo "============================================================"
exit $RC
