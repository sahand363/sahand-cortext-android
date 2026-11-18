#!/bin/bash
# Manual APK build without Gradle (used here because this sandbox blocks
# Java-to-Java TCP, which Gradle's daemon needs). On a normal machine or
# in GitHub Actions, just run:  gradle assembleDebug
#
# Requires: Android SDK (platform 34 + build-tools 34.0.0), JDK 17,
# Kotlin compiler 2.0.0. Set ANDROID_SDK_ROOT, JAVA_HOME, KOTLINC_HOME.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/34.0.0"
AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
KEYTOOL="$JAVA_HOME/bin/keytool"
KOTLINC="${KOTLINC_HOME:-$HOME/android-build/kotlinc}/bin/kotlinc"

export PATH="$JAVA_HOME/bin:$PATH"
OUT="build/manual"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== aapt2 compile resources =="
"$AAPT2" compile --dir app/src/main/res -o "$OUT/compiled.zip"

echo "== aapt2 link (manifest + resources, generates R.java) =="
"$AAPT2" link -I "$ANDROID_JAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --version-code 9 --version-name 1.4.2 \
  -o "$OUT/app-unsigned.apk" \
  --java "$OUT/gen" \
  -R "$OUT/compiled.zip" \
  --auto-add-overlay

echo "== kotlinc (app sources + R.java) =="
export JAVA_OPTS="-Xmx1024m"
"$KOTLINC" -cp "$ANDROID_JAR" -d "$OUT/classes" \
  $(find app/src/main/java -name "*.kt") \
  $(find "$OUT/gen" -name "*.java")

echo "== d8 (dex) =="
"$JAVA_HOME/bin/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --lib "$ANDROID_JAR" --release \
  --output "$OUT/dex" "$OUT/classes.jar" \
  "${KOTLINC_HOME:-$HOME/android-build/kotlinc}/lib/kotlin-stdlib.jar"

echo "== add dex to apk, align, sign =="
(cd "$OUT/dex" && zip -q ../app-unsigned.apk classes*.dex)
"$ZIPALIGN" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
# Keystore lives at the project root (not in build/manual, which is wiped
# every build) so rebuilds keep the same signature and install over the
# old version instead of conflicting with it.
if [ ! -f "debug.keystore" ]; then
  "$KEYTOOL" -genkeypair -keystore "debug.keystore" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$APKSIGNER" sign --ks "debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/app-debug.apk" "$OUT/app-aligned.apk"
"$APKSIGNER" verify "$OUT/app-debug.apk"
"$AAPT2" dump badging "$OUT/app-debug.apk" | head -12
ls -lh "$OUT/app-debug.apk"
