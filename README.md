# SnapBudget

SnapBudget is an offline-first Android expense tracker. The launcher supports manual expense entry and user-selected receipt-image import, review before save, all-time totals, and explicit deletion. Confirmed expenses are stored locally. Receipt images are read transiently for on-device OCR and are not retained. See [docs/testing.md](docs/testing.md) for coverage and limitations.

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

Device tests are local-only and reject CI. The selected device must already be offline (airplane mode enabled and Wi-Fi disabled); the runner never changes device networking or boots/manipulates devices. Date parsing is covered by JVM assertions, including strict calendar validation. An earlier API 37 emulator run completed with 24/24 tests passing. The latest focused OCR retry tests passed 14/14; the latest full 34-test suite remains pending after concurrent instrumentation/deployment activity interrupted the runs. API 36 runtime validation remains pending. See the [test guide](docs/testing.md) for details.

For optional read-only Gemini Android Studio Agent Mode setup and safety boundaries, see [docs/gemini-agent-mode.md](docs/gemini-agent-mode.md).
