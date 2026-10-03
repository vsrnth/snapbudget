#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
MODE="${1:-}"
shift || true
SERIAL=""
while (($#)); do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || { echo "ERROR: --serial needs an adb serial" >&2; exit 2; }; SERIAL="$2"; shift 2 ;;
    *) echo "Usage: $0 {doctor|host|unit|device|all} [--serial SERIAL]" >&2; exit 2 ;;
  esac
done
case "$MODE" in doctor|host|unit|device|all) ;; *) echo "Usage: $0 {doctor|host|unit|device|all} [--serial SERIAL]" >&2; exit 2 ;; esac
python3 scripts/local_test_tools.py check-ci

java_major() { "$1" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/'; }
check_tools() {
  local purpose="$1"
  [[ -x ./gradlew ]] || { echo "ERROR: Gradle wrapper missing or not executable." >&2; return 1; }
  local java_bin="${JAVA_HOME:-}/bin/java"
  if [[ -z "${JAVA_HOME:-}" ]]; then java_bin="$(command -v java || true)"; fi
  [[ -x "$java_bin" ]] || { echo "ERROR: Java is required (JDK 25); set JAVA_HOME to a JDK 25 installation." >&2; return 1; }
  local major; major="$(java_major "$java_bin")"
  [[ "$major" == 25 ]] || { echo "ERROR: JDK 25 required; found Java ${major:-unknown}. Set JAVA_HOME to JDK 25 (the Android Studio JBR is suitable)." >&2; return 1; }
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [[ -n "$sdk" && -d "$sdk" ]] || { echo "ERROR: Android SDK root missing or invalid. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2; return 1; }
  [[ -f "$sdk/platforms/android-36/android.jar" ]] || { echo "ERROR: Android SDK Platform 36 missing: $sdk/platforms/android-36/android.jar. Install platforms;android-36." >&2; return 1; }
  [[ -x "$sdk/build-tools/36.0.0/aapt" ]] || { echo "ERROR: Android SDK Build-Tools 36.0.0 missing: $sdk/build-tools/36.0.0/aapt. Install build-tools;36.0.0." >&2; return 1; }
  if [[ "$purpose" == device ]]; then
    command -v python3 >/dev/null || { echo "ERROR: Python 3 is required for bounded adb and report handling." >&2; return 1; }
    if [[ -x "$sdk/platform-tools/adb" ]]; then ADB_BIN="$sdk/platform-tools/adb"; else ADB_BIN="$(command -v adb || true)"; fi
    [[ -n "$ADB_BIN" && -x "$ADB_BIN" ]] || { echo "ERROR: adb missing; install SDK platform-tools." >&2; return 1; }
  fi
}

run_host_tests() {
  python3 scripts/test_runner_tools.py
}
run_device_tests() {
  check_tools device
  local selected
  selected="$(python3 scripts/local_test_tools.py select-device --adb "$ADB_BIN" --serial "$SERIAL")"
  echo "Device: $selected (airplane mode on, Wi-Fi off, boot complete)"
  local build_log="app/build/reports/local-tests/device-gradle.log"
  mkdir -p "$(dirname "$build_log")"
  echo "Building debug app and instrumentation APK; dependency resolution may use the network. Log: $build_log"
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain 2>&1 | tee "$build_log"
  mkdir -p app/build/reports/local-tests
  python3 scripts/local_test_tools.py install --adb "$ADB_BIN" --serial "$selected" \
    --apk app/build/outputs/apk/debug/app-debug.apk --timeout 120
  python3 scripts/local_test_tools.py install --adb "$ADB_BIN" --serial "$selected" \
    --apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk --timeout 120
  local report="app/build/reports/local-tests/instrumentation.txt"
  echo "Running instrumentation on explicit serial $selected. Report: $report"
  python3 scripts/local_test_tools.py instrument --adb "$ADB_BIN" --serial "$selected" --report "$report" --timeout 600
}

case "$MODE" in
  doctor)
    check_tools host
    ./gradlew --version
    echo "Doctor passed. Unit mode needs the same JDK/SDK packages; device mode additionally needs platform-tools, Python 3, and one explicitly selected offline device."
    ;;
  host) run_host_tests ;;
  unit)
    check_tools unit
    run_host_tests
    mkdir -p app/build/reports/local-tests
    ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tee app/build/reports/local-tests/unit-gradle.log
    ;;
  device) run_device_tests ;;
  all)
    run_host_tests
    check_tools unit
    mkdir -p app/build/reports/local-tests
    ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tee app/build/reports/local-tests/unit-gradle.log
    run_device_tests
    ;;
esac
