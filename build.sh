#!/usr/bin/env bash
# 不依賴 Gradle / Android Studio 的最小建置腳本。
# 需要：JDK 8+、aapt、dx、apksigner、zipalign、API 23+ 的 android.jar
# （Debian/Ubuntu: apt install aapt apksigner zipalign libandroid-23-java android-sdk-build-tools dalvik-exchange）
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
ANDROID_JAR=${ANDROID_JAR:-$SDK/platforms/android-23/android.jar}
BT=${BUILD_TOOLS:-$SDK/build-tools/29.0.3}
DX_JAR=${DX_JAR:-/usr/share/java/com.android.dx.jar}
OUT=build
KEYSTORE=${KEYSTORE:-$HOME/.android/my-cutter.keystore}

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/gen" dist

"$BT/aapt" package -f -m -J "$OUT/gen" -M app/src/main/AndroidManifest.xml \
  -S app/src/main/res -I "$ANDROID_JAR" -F "$OUT/unsigned.apk" --min-sdk-version 26 --target-sdk-version 34

javac --release 8 -Xlint:-options -classpath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find app/src/main/java "$OUT/gen" -name '*.java')

java -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=26 \
  --output="$OUT/classes.dex" "$OUT/classes"

( cd "$OUT" && "$BT/aapt" add -f unsigned.apk classes.dex >/dev/null )
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias cutter -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=my-cutter"
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias cutter --ks-pass pass:android --key-pass pass:android \
  --out dist/my-cutter.apk "$OUT/aligned.apk"
apksigner verify -v dist/my-cutter.apk | head -5
ls -l dist/my-cutter.apk
