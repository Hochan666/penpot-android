#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

JDK=/data/user/0/app.dsh.mobile/files/engine/extensions/openjdk-17/lib/jvm/java-17-openjdk
BT=/data/user/0/app.dsh.mobile/files/engine/extensions/android-buildtools/bin
AJAR="$HOME/android.jar"
ZIP=/data/user/0/app.dsh.mobile/files/engine/extensions/archivers/bin/zip
UNZIP=/data/user/0/app.dsh.mobile/files/engine/extensions/archivers/bin/unzip
PKG=com/penpot/android

echo "=== 1. 编译 Java ==="
rm -rf classes gen; mkdir -p classes gen
"$JDK/bin/javac" -source 8 -target 8 -cp "$AJAR" -d classes \
    src/com/penpot/android/*.java 2>&1 | grep -v "bootstrap class path" || true
test -f classes/com/penpot/android/MainActivity.class || { echo "编译失败"; exit 1; }

echo "=== 2. D8 -> dex（全部 class，含匿名内部类）==="
"$BT/d8" --lib "$AJAR" --output classes \
    $(find classes/com/penpot/android -name '*.class')

echo "=== 3. aapt 打包（manifest + res 资源 + assets）==="
rm -f unsigned.apk
"$BT/aapt" package -f -M AndroidManifest.xml -S res -I /system/framework/framework-res.apk \
    -A assets -F unsigned.apk

echo "=== 4. 加 dex ==="
TMP=$(mktemp -d); cp classes/classes.dex "$TMP/"
( cd "$TMP" && "$ZIP" -q "$OLDPWD/unsigned.apk" classes.dex )
rm -rf "$TMP"

echo "=== 5. zipalign + 签名 ==="
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk
if [ ! -f penpot.keystore ]; then
  "$JDK/bin/keytool" -genkeypair -keystore penpot.keystore -alias pockey \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass pocpass123 -keypass pocpass123 \
    -dname "CN=Penpot Android, OU=Penpot, O=Penpot, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks penpot.keystore --ks-key-alias pockey \
  --ks-pass pass:pocpass123 --key-pass pass:pocpass123 --out penpot.apk aligned.apk

echo "=== 6. 校验 ==="
"$BT/apksigner" verify penpot.apk
"$UNZIP" -l penpot.apk | grep -E 'classes.dex' || true
ls -la penpot.apk
echo "BUILD DONE"
