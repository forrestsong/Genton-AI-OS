#!/bin/bash
set +e
JDK=/root/aosp/prebuilts/jdk/jdk21/linux-x86/bin
echo "=== A. Context.java source has COUNTRY_DETECTOR? ==="
grep -n "COUNTRY_DETECTOR" /root/aosp/frameworks/base/core/java/android/content/Context.java 2>/dev/null | head -3
echo
echo "=== B. prebuilt system/android.jar Context has it? ==="
cd /tmp && rm -rf ctx1 && mkdir ctx1 && cd ctx1
unzip -o -q /root/aosp/prebuilts/sdk/current/system/android.jar android/content/Context.class 2>&1 | head -2
$JDK/javap -constants -cp . android.content.Context 2>/dev/null | grep -i "COUNTRY_DETECTOR\|COUNTRY" | head -5
echo
echo "=== C. framework-minus-apex-headers framework.jar (turbine) has it? ==="
FW=/root/aosp/out/soong/.intermediates/frameworks/base/framework-minus-apex-headers/android_common/jarjar/turbine/framework.jar
ls -la $FW 2>&1 | head -2
cd /tmp && rm -rf ctx2 && mkdir ctx2 && cd ctx2
unzip -o -q $FW android/content/Context.class 2>&1 | head -2
$JDK/javap -constants -cp . android.content.Context 2>/dev/null | grep -i "COUNTRY" | head -5
echo
echo "=== D. the failing turbine used --system core_platform image. Does that image's Context have COUNTRY_DETECTOR? ==="
SYSIMG=/root/aosp/out/soong/.intermediates/build/soong/java/core-libraries/stable-core-platform-api-stubs-system-modules/android_common/system/lib/modules
strings $SYSIMG 2>/dev/null | grep -c "COUNTRY_DETECTOR"
echo
echo "=== E. does the REAL framework.jar (turbine-combined) have COUNTRY_DETECTOR? ==="
FW2=/root/aosp/out/soong/.intermediates/frameworks/base/framework/android_common/turbine-combined/framework.jar
cd /tmp && rm -rf ctx3 && mkdir ctx3 && cd ctx3
unzip -o -q $FW2 android/content/Context.class 2>&1 | head -2
$JDK/javap -constants -cp . android.content.Context 2>/dev/null | grep -i "COUNTRY" | head -5
echo
echo "=== F. what about android_stubs_current (from-source)? ==="
SJ=/root/aosp/out/soong/.intermediates/frameworks/base/api/android_stubs_current/android_common/turbine-combined/android_stubs_current.jar
cd /tmp && rm -rf ctx4 && mkdir ctx4 && cd ctx4
unzip -o -q $SJ android/content/Context.class 2>&1 | head -2
$JDK/javap -constants -cp . android.content.Context 2>/dev/null | grep -i "COUNTRY" | head -5
echo DONE
