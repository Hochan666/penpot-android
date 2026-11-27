#!/usr/bin/env bash
# 把构建好的 APK 安装到已连接的 Android 设备（使用 adb）
# Install the built APK to a connected Android device via adb.
set -e
APK="${1:-penpot.apk}"
[ -f "$APK" ] || { echo "找不到 $APK，请先运行 build.sh"; exit 1; }

echo "=== 安装 $APK ==="
adb install -r -t "$APK"
echo "=== 启动 ==="
adb shell am start -n com.penpot.android/.MainActivity
echo "=== 实时日志（Ctrl+C 退出）==="
adb logcat -s PenpotService:D AndroidRuntime:E
