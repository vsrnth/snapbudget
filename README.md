# SnapBudget

SnapBudget is an offline-first Android expense tracker. The sidebar provides all-expense and current-calendar-month views, each with exact INR totals and counts. Saved expenses can be edited or explicitly deleted. Confirmed merchant categories are remembered on-device and suggested for matching names, ignoring case and repeated spaces; choosing a category in the editor overrides that suggestion. Manual entry and user-selected receipt-image import support editable OCR suggestions with an explicit save action. Receipt import currently supports eligible PhonePe receipts and completed outgoing Google Pay UPI receipts through separate provider parser strategies. The parser identifies the origin app from its own transaction ID labels; recipient mentions of another payment app do not change provider selection, while conflicting origin ID labels are rejected. Expenses are stored locally only after the user chooses Save expense or Save changes. Receipt images are read transiently for on-device OCR and are not retained. See [docs/testing.md](docs/testing.md) for coverage and limitations.

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

Device tests are local-only and reject CI. The selected device must already be offline (airplane mode enabled and Wi-Fi disabled); the runner never changes device networking or boots/manipulates devices. Date parsing is covered by JVM assertions, including strict calendar validation. Latest verified results: full API 37 instrumentation 76/76, JVM tests 51/51, host runner tests 20/20, and offline debug app/test APK builds plus lint checks passed. A separate temporary on-device check matched amount, merchant, date/time, and transaction identity across three local fixtures using only whitelisted boolean/enum output; its probe and expected values were removed. No private receipt contents are documented. API 36 runtime validation remains pending. See the [test guide](docs/testing.md) for coverage and limitations.

For optional read-only Gemini Android Studio Agent Mode setup and safety boundaries, see [docs/gemini-agent-mode.md](docs/gemini-agent-mode.md).
