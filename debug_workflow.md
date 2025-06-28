# 🛠️ Complete Android Development & Debugging Workflow (macOS + VSCode + Android Studio)

## **Phase 1: Initial Setup (One-time) ✅**

### **1.1 Environment Verification**
```bash
# Verify ADB is working
adb version

# Check connected devices
adb devices

# Enable developer options on your phone:
# Settings → About Phone → Tap "Build Number" 7 times
# Settings → Developer Options → Enable "USB Debugging"
```

### **1.2 VSCode Extensions (Install these)**
```bash
# Open VSCode and install:
# 1. Android iOS Emulator
# 2. Android Studio Integration
# 3. Java Extension Pack
# 4. Gradle for Java
# 5. Git Lens (for git history)
```

---

## **Phase 2: Daily Development Workflow**

### **🔄 Standard Development Cycle**

#### **Step 1: Code in VSCode**
```bash
# Open project in VSCode
code /Users/a16zeeter/ai-assisted-dev/Dictate

# Make your changes in VSCode (faster editing)
# Use VSCode for:
# - Code editing
# - Git operations
# - File navigation
# - Search & replace
```

#### **Step 2: Build & Test Cycle**
```bash
# Navigate to project directory
cd /Users/a16zeeter/ai-assisted-dev/Dictate

# Clean build (when you have major changes)
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Build release APK (for testing performance)
./gradlew assembleRelease
```

#### **Step 3: Install & Test on Phone**
```bash
# Connect phone via USB and enable USB debugging

# Check device connection
adb devices

# Install debug APK directly
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or for release version
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## **Phase 3: Advanced Debugging Techniques**

### **🐛 Debugging Methods**

#### **Method 1: Logcat Debugging (Primary)**
```bash
# Clear logs and start monitoring
adb logcat -c
adb logcat | grep -i dictate

# Filter for specific components
adb logcat | grep -E "(DictateInputMethodService|AndroidRuntime|System.err)"

# Save logs to file for analysis
adb logcat > debug_logs.txt

# View only error logs
adb logcat *:E
```

#### **Method 2: Android Studio Debugging (For complex issues)**
```bash
# Open project in Android Studio
open -a "Android Studio" /Users/a16zeeter/ai-assisted-dev/Dictate

# Use Android Studio for:
# - Setting breakpoints
# - Memory profiling
# - Layout inspector
# - APK analyzer
```

#### **Method 3: Crash Analysis**
```bash
# Get crash logs immediately after crash
adb logcat -d | grep -A 50 -B 10 "FATAL"

# Get system crash logs
adb shell ls /data/tombstones/
adb shell cat /data/tombstones/tombstone_XX
```

---

## **Phase 4: Testing Specific Features**

### **🎯 Keyboard Testing Workflow**

#### **Testing Dictate Keyboard**
```bash
# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Open any text app (Notes, Messages, etc.)
# Switch to Dictate keyboard:
# 1. Tap text field
# 2. Tap keyboard switcher (bottom right)
# 3. Select "Dictate"

# Monitor logs while testing
adb logcat | grep -i dictate
```

#### **Onboarding Testing**
```bash
# Clear app data to test onboarding again
adb shell pm clear net.devemperor.dictate

# Reinstall and test onboarding flow
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### **🔊 Audio & API Testing**
```bash
# Test microphone permissions
adb shell dumpsys audio | grep -i "record"

# Test network connectivity
adb shell ping google.com

# Monitor API calls
adb logcat | grep -E "(HTTP|API|OpenAI|Groq)"
```

---

## **Phase 5: Performance & Release Testing**

### **📊 Performance Analysis**
```bash
# Monitor memory usage
adb shell dumpsys meminfo net.devemperor.dictate

# Monitor CPU usage
adb shell top | grep dictate

# Battery usage analysis
adb shell dumpsys batterystats net.devemperor.dictate
```

### **🚀 Release Build Testing**
```bash
# Build signed release APK
./gradlew assembleRelease

# Test release build (important - different behavior than debug)
adb install -r app/build/outputs/apk/release/app-release.apk

# Verify no debug logs in release
adb logcat | grep -i debug
```

---

## **Phase 6: Efficient Development Shortcuts**

### **⚡ Quick Commands (Add to ~/.zshrc)**
```bash
# Add these aliases for faster development
alias dictate-build="cd /Users/a16zeeter/ai-assisted-dev/Dictate && ./gradlew assembleDebug"
alias dictate-install="adb install -r /Users/a16zeeter/ai-assisted-dev/Dictate/app/build/outputs/apk/debug/app-debug.apk"
alias dictate-logs="adb logcat | grep -i dictate"
alias dictate-clear="adb shell pm clear net.devemperor.dictate"
alias dictate-devices="adb devices"

# Usage:
# dictate-build && dictate-install && dictate-logs
```

### **📱 Multiple Device Testing**
```bash
# If you have multiple devices/emulators
adb devices

# Target specific device
adb -s DEVICE_ID install app-debug.apk
adb -s DEVICE_ID logcat | grep dictate
```

---

## **Phase 7: Troubleshooting Common Issues**

### **🚨 Common Problems & Solutions**

#### **App Won't Install**
```bash
# Check if app is already installed
adb shell pm list packages | grep dictate

# Force uninstall
adb uninstall net.devemperor.dictate

# Reinstall
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### **Keyboard Not Showing**
```bash
# Check if keyboard is enabled
adb shell ime list -s

# Enable Dictate keyboard
adb shell ime enable net.devemperor.dictate/.core.DictateInputMethodService

# Set as default (for testing)
adb shell ime set net.devemperor.dictate/.core.DictateInputMethodService
```

#### **Crash on Startup**
```bash
# Get immediate crash logs
adb logcat -c
# Launch app, let it crash
adb logcat -d | grep -A 30 "AndroidRuntime"

# Check for missing permissions
adb shell dumpsys package net.devemperor.dictate | grep -A 10 "permissions"
```

---

## **Phase 8: Automated Testing Scripts**

### **🤖 One-Command Workflow**
```bash
# Create this script: ~/dictate_deploy.sh
#!/bin/bash
cd /Users/a16zeeter/ai-assisted-dev/Dictate
echo "🔨 Building APK..."
./gradlew assembleDebug
if [ $? -eq 0 ]; then
    echo "📱 Installing on device..."
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    echo "📋 Starting logs..."
    adb logcat | grep -i dictate
else
    echo "❌ Build failed!"
fi

# Make executable and use:
chmod +x ~/dictate_deploy.sh
~/dictate_deploy.sh
```

---

## **🎯 Recommended Daily Workflow**

### **For Feature Development:**
1. **Code in VSCode** (fast editing, git operations)
2. **Build with terminal** (`./gradlew assembleDebug`)
3. **Install with ADB** (`adb install -r app-debug.apk`)
4. **Test on phone** with live logging (`adb logcat | grep dictate`)

### **For Complex Debugging:**
1. **Use Android Studio** (breakpoints, profiler)
2. **Analyze with Logcat** (detailed logs)
3. **Test edge cases** (clear data, fresh install)

### **For Release Testing:**
1. **Build release APK** (`./gradlew assembleRelease`)
2. **Test on multiple devices**
3. **Performance analysis** (memory, battery)

This workflow gives you the **speed of VSCode** for development and the **power of Android Studio** for debugging! 🚀 