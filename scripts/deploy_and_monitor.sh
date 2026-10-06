#!/usr/bin/env bash
set -euo pipefail

# Directory of the project
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Build the release APK
cd "$PROJECT_DIR"
./gradlew assembleRelease

# Locate the generated APK
APK_PATH=$(find "$PROJECT_DIR/app/build/outputs/apk/release" -name "*.apk" | head -n 1)
if [[ -z "$APK_PATH" ]]; then
  echo "[ERROR] APK not found after build"
  exit 1
fi

echo "[INFO] APK built at $APK_PATH"

# Install the APK via adb (assuming device is already connected)
adb install -r "$APK_PATH"

# Function to get load average via SSH to RPi5
function get_load() {
  ssh -p 8022 100.77.45.20 "cat /proc/loadavg" 2>/dev/null || echo "ssh failed"
}

# Capture load before launching app
echo "[INFO] Load before launch:" $(get_load)

# Launch the app (replace with actual package/activity if needed)
adb shell am start -n org.tfv.deskflow/.ui.activities.RootActivity

# Wait a bit for app to settle
sleep 30

# Capture load after launch
echo "[INFO] Load after launch:" $(get_load)

# Capture screenshot
SCREENSHOT_DIR="$HOME/.gemini/tmp/android/screenshots"
mkdir -p "$SCREENSHOT_DIR"
adb exec-out screencap -p > "$SCREENSHOT_DIR/deskflow_after_launch.png"

echo "[INFO] Screenshot saved to $SCREENSHOT_DIR/deskflow_after_launch.png"
