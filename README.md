# SnapBudget

SnapBudget is an offline-first Android expense-tracking app scaffold. Its product UI is not implemented yet; the launcher activity named in the current manifest does not exist. The local test harness is deliberately separate from product UI.

## Local checks

Targets Android 16 (API 36) with JDK 25, SDK platform 36/build-tools 36.0.0, and Gradle dependencies available for build. For test coverage/setup see [docs/testing.md](docs/testing.md); for the official local Android CLI and repository skills see [docs/android-tooling.md](docs/android-tooling.md).

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
scripts/test-local.sh doctor
scripts/test-local.sh host  # host-only runner regressions; no SDK/device needed
scripts/test-local.sh unit
scripts/test-local.sh device --serial emulator-5554
# or both, stopping at first failure
scripts/test-local.sh all --serial emulator-5554
```

Device tests are local-only and reject CI. The selected device must already be offline (airplane mode enabled and Wi-Fi disabled); the runner never changes device networking or boots/manipulates devices. The date parser test is intentionally RED for a known production parser bug; details and separate infrastructure coverage are in the test guide.

For optional read-only Gemini Android Studio Agent Mode setup and safety boundaries, see [docs/gemini-agent-mode.md](docs/gemini-agent-mode.md).
