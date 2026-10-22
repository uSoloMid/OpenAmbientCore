#!/bin/sh
# Compila Probe.java a dex y lo sube a la TV. Uso: ./build.sh [ip:puerto]
set -e
export MSYS_NO_PATHCONV=1
JAVA_HOME="${JAVA_HOME:-$(ls -d "/c/Program Files/Eclipse Adoptium"/jdk-17* | head -1)}"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
TV="${1:-192.168.101.6:5555}"
cd "$(dirname "$0")"
rm -rf out && mkdir out
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -Xlint:-options -cp "$SDK/platforms/android-30/android.jar" -d out src/Probe.java
"$JAVA_HOME/bin/java" -cp "$SDK/build-tools/34.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 28 --output out/probe.jar out/*.class
adb -s "$TV" push out/probe.jar /data/local/tmp/probe.jar >/dev/null
echo "OK: /data/local/tmp/probe.jar"
