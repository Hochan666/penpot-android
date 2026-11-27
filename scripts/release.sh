#!/usr/bin/env bash
# 生成用于 GitHub Release 的产物（APK + 校验和）
# Produce artifacts for a GitHub Release (APK + checksums).
set -e
cd "$(dirname "$0")/.."
[ -f penpot.apk ] || { echo "先运行 bash build.sh"; exit 1; }

VER=$(grep -oP 'android:versionName="\K[^"]+' AndroidManifest.xml)
OUT="release/penpot-android-v${VER}"
mkdir -p "$OUT"

cp penpot.apk "$OUT/Penpot-apk-v${VER}.apk"
( cd "$OUT" && sha256sum *.apk > SHA256SUMS.txt )
cp README.md LICENSE CHANGELOG.md "$OUT/"

echo "=== 产物 ==="
ls -lh "$OUT"
echo
echo "上传到 GitHub Release 后，README 里的下载链接即可生效。"
