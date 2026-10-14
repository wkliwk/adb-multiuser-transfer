#!/bin/bash
# Build app/ into adb-multiuser-transfer.apk with the Android SDK command-line tools (no Gradle).
# Needs ANDROID_HOME with build-tools/<ver> and a platform android.jar (API 28 or newer) android.jar.
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)/android.jar"
OUT=build; rm -rf "$OUT"; mkdir -p "$OUT/classes"
javac --release 8 -cp "$JAR" -d "$OUT/classes" $(find app/src -name '*.java')
"$BT/d8" --release --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest app/AndroidManifest.xml
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -alias d -keypass android -dname CN=adb-multiuser-transfer -keyalg RSA -validity 3650 >/dev/null 2>&1
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out adb-multiuser-transfer.apk "$OUT/aligned.apk"
sha256sum adb-multiuser-transfer.apk 2>/dev/null || shasum -a 256 adb-multiuser-transfer.apk
