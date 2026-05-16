#!/bin/bash
# Setup script for OpenCode for Android
# Generates Gradle wrapper and prepares the project for building

set -e

echo "🔧 OpenCode for Android - Setup"
echo "================================"

# Check for Java
if ! command -v java &> /dev/null; then
    echo "❌ Java not found. Please install JDK 17+."
    echo "   Android: pkg install openjdk-17"
    echo "   Ubuntu:  sudo apt install openjdk-17-jdk"
    echo "   macOS:   brew install openjdk@17"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "✅ Java version: $(java -version 2>&1 | head -1)"

# Check for Gradle or download wrapper
if command -v gradle &> /dev/null; then
    echo "✅ Gradle found, generating wrapper..."
    gradle wrapper --gradle-version=8.11.1
else
    echo "📥 Gradle not found. Downloading wrapper..."
    # Download the gradle wrapper jar
    GRADLE_URL="https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
    
    if command -v curl &> /dev/null; then
        curl -L -o /tmp/gradle-wrapper.zip "$GRADLE_URL"
    elif command -v wget &> /dev/null; then
        wget -O /tmp/gradle-wrapper.zip "$GRADLE_URL"
    else
        echo "❌ Need curl or wget to download Gradle. Please install one of them."
        exit 1
    fi
    
    echo "📦 Extracting gradle-wrapper.jar..."
    unzip -o -j /tmp/gradle-wrapper.zip "gradle-8.11.1/lib/gradle-wrapper-*.jar" -d /tmp/
    cp /tmp/gradle-wrapper*.jar gradle/wrapper/gradle-wrapper.jar 2>/dev/null || true
    
    # Write wrapper properties
    cat > gradle/wrapper/gradle-wrapper.properties << 'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF
    
    rm -f /tmp/gradle-wrapper.zip
    echo "✅ Wrapper prepared"
fi

chmod +x gradlew
echo "✅ gradlew is ready"

# Check for ANDROID_HOME
if [ -z "$ANDROID_HOME" ]; then
    echo ""
    echo "⚠️  ANDROID_HOME is not set."
    echo "   Set it to your Android SDK location:"
    echo "   export ANDROID_HOME=\$HOME/Android/Sdk"
    echo "   or add to ~/.bashrc for persistence."
    echo ""
    echo "   If you're on Termux, install Android SDK:"
    echo "   pkg install android-sdk"
    echo "   export ANDROID_HOME=/data/data/com.termux/files/usr/lib/android-sdk"
fi

echo ""
echo "📱 To build the app, run:"
echo "   ./gradlew assembleDebug"
echo ""
echo "📱 To install on connected device:"
echo "   ./gradlew installDebug"
