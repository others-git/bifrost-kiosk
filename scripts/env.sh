#!/usr/bin/env bash
# Source this before any Gradle/SDK command:  source scripts/env.sh
# Sets up the JDK + Android SDK installed for this project (see CLAUDE.md).
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# Keep Gradle's caches/daemon off the slow Windows DrvFs mount (/mnt/d).
export GRADLE_USER_HOME="$HOME/.gradle-bifrost-kiosk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
