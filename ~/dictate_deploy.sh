#!/bin/bash

# 🚀 Dictate App Development & Deployment Script
# Usage: ~/dictate_deploy.sh [debug|release|clean]

PROJECT_DIR="/Users/a16zeeter/ai-assisted-dev/Dictate"
PACKAGE_NAME="net.devemperor.dictate"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🛠️  Dictate Development Workflow${NC}"
echo "================================="

# Change to project directory
cd "$PROJECT_DIR" || exit 1

# Check if device is connected
echo -e "\n${YELLOW}📱 Checking device connection...${NC}"
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ No Android device found. Please connect your phone and enable USB debugging.${NC}"
    exit 1
fi

DEVICE=$(adb devices | grep "device$" | head -1 | cut -f1)
echo -e "${GREEN}✅ Device connected: $DEVICE${NC}"

# Determine build type
BUILD_TYPE=${1:-debug}

case $BUILD_TYPE in
    "clean")
        echo -e "\n${YELLOW}🧹 Cleaning project...${NC}"
        ./gradlew clean
        echo -e "${GREEN}✅ Clean completed${NC}"
        exit 0
        ;;
    "debug")
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
        GRADLE_TASK="assembleDebug"
        ;;
    "release")
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
        GRADLE_TASK="assembleRelease"
        ;;
    *)
        echo -e "${RED}❌ Invalid build type. Use: debug, release, or clean${NC}"
        exit 1
        ;;
esac

# Build APK
echo -e "\n${YELLOW}🔨 Building $BUILD_TYPE APK...${NC}"
if ./gradlew $GRADLE_TASK; then
    echo -e "${GREEN}✅ Build completed successfully${NC}"
else
    echo -e "${RED}❌ Build failed!${NC}"
    exit 1
fi

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}❌ APK not found at $APK_PATH${NC}"
    exit 1
fi

# Clear app data (optional - uncomment if needed for testing onboarding)
# echo -e "\n${YELLOW}🗑️  Clearing app data...${NC}"
# adb shell pm clear $PACKAGE_NAME

# Install APK
echo -e "\n${YELLOW}📦 Installing APK...${NC}"
if adb install -r "$APK_PATH"; then
    echo -e "${GREEN}✅ Installation completed${NC}"
else
    echo -e "${RED}❌ Installation failed!${NC}"
    exit 1
fi

# Show app info
echo -e "\n${BLUE}📋 App Information:${NC}"
echo "Package: $PACKAGE_NAME"
echo "APK: $APK_PATH"
echo "Device: $DEVICE"

# Ask user if they want to start logging
echo -e "\n${YELLOW}Start live logging? (y/n):${NC}"
read -r response
if [[ "$response" =~ ^[Yy]$ ]]; then
    echo -e "\n${BLUE}📱 Starting live logs (Ctrl+C to stop)...${NC}"
    echo "Filtering for: DictateInputMethodService, AndroidRuntime, System.err"
    adb logcat | grep -E "(DictateInputMethodService|AndroidRuntime|System.err|$PACKAGE_NAME)"
fi 