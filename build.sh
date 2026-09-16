#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$ROOT/tools/android33.jar}"
KEYSTORE="${KEYSTORE:-$ROOT/tools/debug.keystore}"
OUT="$ROOT/build"
MIN_SDK=24
TARGET_SDK=33

if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /data/data/com.termux/files/usr/lib/jvm/java-21-openjdk \
                   /usr/lib/jvm/java-21-openjdk; do
    [ -x "$candidate/bin/javac" ] && JAVA_HOME="$candidate" && break
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "JDK 21 bulunamadi. JAVA_HOME ayarla veya Termux'ta: pkg install openjdk-21" >&2
  exit 1
fi

for tool in aapt2 d8 zipalign apksigner zip; do
  command -v "$tool" >/dev/null || {
    echo "$tool bulunamadi. Termux'ta: pkg install aapt2 d8 apksigner zip" >&2
    exit 1
  }
done

if [ ! -f "$ANDROID_JAR" ]; then
  cat >&2 <<MSG
Android platform jar bulunamadi:
  $ANDROID_JAR
Google lisansli bir ikili oldugu icin depoya girmez. Android SDK kurulumundan
(platforms/android-33/android.jar) kopyala ya da disaridan ver:
  ANDROID_JAR=/yol/android.jar ./build.sh
MSG
  exit 1
fi

if [ ! -f "$KEYSTORE" ]; then
  echo ">> debug keystore uretiliyor"
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KEYSTORE" \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

# Adres, paylasilan sir ve sunucu sertifikasi tek bir kuruluma aittir; depoda durmazlar.
# Yoksa ornek surumleri konur: derleme gecer, yalnizca yardimci sunucu calismaz.
if [ ! -f "$ROOT/src/com/nota/data/Backend.java" ]; then
  echo ">> Backend.java sablondan olusturuluyor (ornek adres)"
  cp "$ROOT/tools/Backend.java.template" "$ROOT/src/com/nota/data/Backend.java"
fi
if [ ! -f "$ROOT/res/xml/network_security_config.xml" ]; then
  echo ">> network_security_config.xml sablondan olusturuluyor (ornek adres)"
  mkdir -p "$ROOT/res/xml"
  cp "$ROOT/tools/network_security_config.xml.template" \
    "$ROOT/res/xml/network_security_config.xml"
fi
if [ ! -f "$ROOT/res/raw/notastream.pem" ]; then
  echo ">> ornek sertifika uretiliyor (gercegini sunucudan kopyala)"
  mkdir -p "$ROOT/res/raw"
  tmp="$OUT.placeholder.jks"
  rm -f "$tmp"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$tmp" -storepass changeit \
    -keypass changeit -alias notastream -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=example.invalid" >/dev/null
  "$JAVA_HOME/bin/keytool" -exportcert -rfc -keystore "$tmp" -storepass changeit \
    -alias notastream > "$ROOT/res/raw/notastream.pem"
  rm -f "$tmp"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo ">> aapt2 compile"
aapt2 compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo ">> aapt2 link"
aapt2 link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --auto-add-overlay

echo ">> javac"
find "$ROOT/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# -parameters sart: onsuz javac 21, enum ve ic sinif kuruculari icin MethodParameters
# girdisini isimsiz yazar ve d8 3.3.20 bunu okurken NPE atar.
"$JAVA_HOME/bin/javac" -parameters -source 8 -target 8 -encoding UTF-8 \
  -nowarn -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" "@$OUT/sources.txt"

echo ">> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
d8 --lib "$ANDROID_JAR" --min-api $MIN_SDK --output "$OUT/dex" "@$OUT/classes.txt"

echo ">> paketleme"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes*.dex)

echo ">> zipalign"
zipalign -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo ">> imzalama"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version $MIN_SDK \
  --out "$OUT/nota.apk" "$OUT/aligned.apk"

apksigner verify --min-sdk-version $MIN_SDK "$OUT/nota.apk"
echo ""
echo "OK -> $OUT/nota.apk ($(du -h "$OUT/nota.apk" | cut -f1))"
