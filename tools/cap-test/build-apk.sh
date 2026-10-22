#!/bin/sh
# Compila e instala la APK de prueba de captura. Uso: ./build-apk.sh [ip:puerto]
# No usa Gradle: aapt2 + javac + d8 + zipalign + apksigner (todo offline).
# Las herramientas son binarios Windows: los argumentos con ruta absoluta van en
# formato Windows (cygpath -w); las rutas relativas (out/, src/) van tal cual.
set -e
export MSYS_NO_PATHCONV=1
JAVA_HOME="${JAVA_HOME:-$(ls -d "/c/Program Files/Eclipse Adoptium"/jdk-17* | head -1)}"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
TV="${1:-192.168.101.6:5555}"
cd "$(dirname "$0")"
PKG=com.oac.captest
KS="$HOME/.android/debug.keystore"

AJARW=$(cygpath -w "$SDK/platforms/android-30/android.jar")
KSW=$(cygpath -w "$KS")
D8JARW=$(cygpath -w "$BT/lib/d8.jar")

rm -rf out && mkdir -p out/classes
mkdir -p "$HOME/.android"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KSW" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

# 1) empaquetar manifiesto (sin recursos) -> base.apk
"$BT/aapt2" link -I "$AJARW" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 30 -o out/base.apk

# 2) compilar java
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -Xlint:-options \
  -cp "$AJARW" -d out/classes $(find src -name '*.java')

# 3) dex
"$JAVA_HOME/bin/java" -cp "$D8JARW" com.android.tools.r8.D8 \
  --min-api 28 --lib "$AJARW" --output out $(find out/classes -name '*.class')

# 4) meter classes.dex dentro de la apk (jar del JDK; zip no existe en Git Bash)
(cd out && "$JAVA_HOME/bin/jar" uf base.apk classes.dex)

# 5) alinear y firmar
"$BT/zipalign" -f 4 out/base.apk out/aligned.apk
"$JAVA_HOME/bin/java" -jar "$(cygpath -w "$BT/lib/apksigner.jar")" sign \
  --ks "$KSW" --ks-pass pass:android --key-pass pass:android \
  --v2-signing-enabled true out/aligned.apk

# 6) instalar
adb -s "$TV" install -r out/aligned.apk
echo "OK instalada: $PKG"
