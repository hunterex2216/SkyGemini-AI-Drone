#!/usr/bin/env bash
# ==============================================================================
# SkyGemini AI Drone - Automated Android Build Script for Termux
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "🛸 Building SkyGemini AI Drone APK..."

FRAMEWORK_RES="/system/framework/framework-res.apk"
ANDROID_JAR="/data/user/0/com.termux/files/usr/share/java/android.jar"
OUT_APK="/data/user/0/com.termux/files/home/SkyGemini-AI-Drone.apk"

# 1. Clean previous build
rm -rf bin obj
mkdir -p bin obj

# 2. Package resources and generate R.java
echo "📦 Packaging resources (aapt)..."
aapt package -f -m -J src -M AndroidManifest.xml -S res -I "$FRAMEWORK_RES"

# 3. Compile Java sources
echo "☕ Compiling Java sources (javac)..."
javac -classpath "$ANDROID_JAR" -d obj $(find src -name "*.java")

# 4. Dex bytecode (d8)
echo "⚡ Converting to Dalvik Executable (d8)..."
d8 --lib "$ANDROID_JAR" --output bin/ $(find obj -name "*.class")

# 5. Package APK
echo "📦 Packaging unsigned APK (aapt)..."
aapt package -f -M AndroidManifest.xml -S res -I "$FRAMEWORK_RES" -F bin/SkyGemini.unsigned.apk bin

# 6. Generate debug keystore if missing
if [ ! -f "debug.keystore" ]; then
    echo "🔑 Generating debug.keystore..."
    keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US"
fi

# 7. Sign APK with apksigner (v2 & v3 schemes)
echo "✍️ Signing APK (apksigner)..."
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT_APK" bin/SkyGemini.unsigned.apk

# 8. Verify
apksigner verify -v "$OUT_APK"

echo "✅ Build complete! APK generated at: $OUT_APK"
ls -lh "$OUT_APK"
