#!/usr/bin/env bash
# APK derleme (Gradle gerektirmez): aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$ROOT/../tools"
OUT="$ROOT/build"
APK="$ROOT/JARVIS.apk"

# Yerelde tools/ altındaki JDK+SDK, CI'da ortam değişkenleri kullanılır.
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
  BT="$ANDROID_HOME/build-tools/34.0.0"
  PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
else
  BT="$TOOLS/sdk/build-tools/34.0.0"
  PLATFORM="$TOOLS/sdk/platforms/android-34/android.jar"
  export JAVA_HOME="$TOOLS/jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ ! -x "$BT/aapt2" ]; then
  echo "HATA: Android build-tools bulunamadı ($BT)" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

# ikon repoda tutulmuyor: yoksa üret (node gerekir, CI'da hazır)
ICON="$ROOT/res/mipmap-xxhdpi/ic_launcher.png"
if [ ! -f "$ICON" ]; then
  echo "[0/7] ikon üretiliyor"
  mkdir -p "$(dirname "$ICON")"
  if [ -f "$ROOT/../public/icons/icon-512.png" ]; then
    cp "$ROOT/../public/icons/icon-512.png" "$ICON"
  else
    (cd "$ROOT/.." && node scripts/gen-icons.mjs > /dev/null)
    cp "$ROOT/../public/icons/icon-512.png" "$ICON"
  fi
fi

echo "[1/7] kaynaklar derleniyor (aapt2 compile)"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "[2/7] manifest + kaynaklar bağlanıyor (aapt2 link)"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[3/7] java derleniyor (javac)"
javac --release 11 -nowarn \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  $(find "$ROOT/src" "$OUT/gen" -name '*.java')

echo "[4/7] dex üretiliyor (d8)"
"$BT/d8" --lib "$PLATFORM" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[5/7] apk paketleniyor"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex)

echo "[6/7] hizalanıyor (zipalign)"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] imzalanıyor (apksigner)"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$APK" | head -4

ls -lh "$APK"
echo "OK: $APK"
