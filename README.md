# SnapBudget

SnapBudget is an offline-first Android expense tracker. The launcher supports manual expense entry and user-selected receipt-image import, review before save, all-time totals, and explicit deletion. Receipt import currently supports eligible PhonePe receipts and completed outgoing Google Pay UPI receipts through separate provider parser strategies. Confirmed expenses are stored locally. Receipt images are read transiently for on-device OCR and are not retained. See [docs/testing.md](docs/testing.md) for coverage and limitations.

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

Device tests are local-only and reject CI. The selected device must already be offline (airplane mode enabled and Wi-Fi disabled); the runner never changes device networking or boots/manipulates devices. Date parsing is covered by JVM assertions, including strict calendar validation. Latest verified results: full API 37 instrumentation 34/34, focused OCR/retry instrumentation 14/14, and host runner tests 20/20; offline JVM, debug/test/release build, and lint checks are green. A separate approved comparison on two local inputs matched in the production reader using only whitelisted boolean/enum output. These OCR/device checks use synthetic fixtures except for that separate comparison; no private OCR values, identifiers, names, or images are documented. API 36 runtime validation remains pending. See the [test guide](docs/testing.md) for coverage and limitations.

For optional read-only Gemini Android Studio Agent Mode setup and safety boundaries, see [docs/gemini-agent-mode.md](docs/gemini-agent-mode.md).
