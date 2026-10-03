# Local Android test foundation

## Execution contract

The checked-in wrapper uses the official Gradle 9.6.0 distribution and pins `distributionSha256Sum` to its published SHA-256: `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`. The project targets Android 16/API 36 (`compileSdk`/`targetSdk` 36) with AGP 9.4.1. Google's [AGP 9.4 compatibility notes](https://developer.android.com/build/releases/gradle-plugin) support Gradle 9.6.0, Build-Tools 36.0.0, and max API 37; see [Android 16 SDK setup](https://developer.android.com/about/versions/16/setup-sdk). Built-in Kotlin is used (AGP's KGP 2.2.10 runtime); the Compose compiler plugin is 2.2.10, KSP is 2.3.12, and Java/Kotlin bytecode target remains 17.

- Use `scripts/test-local.sh {doctor|host|unit|device|all}`. `host` runs standard-library runner protocol tests and needs no Android SDK/JDK/device. `unit` runs those and JVM parser tests; it requires JDK 25 and SDK platform 36/build-tools 36.0.0, but not adb. `device`/`all` additionally require Python 3, platform-tools, and `--serial SERIAL` unless exactly one online adb device exists. An explicit, online serial is always selected before installation/execution. The script rejects CI, and the Gradle connected-test task also has a CI guard.
- Device execution requires Android airplane mode on **and Wi-Fi off**. The script reads those settings and fails closed; it never toggles networking, boots an emulator, clears app data, or targets an unselected device. Check that no other network path is active yourself. Do not run instrumented tests with private data on a physical device.
- ADB device/settings reads are bounded to 15 seconds; boot wait is bounded to 120 seconds; each APK install to 120 seconds; instrumentation to 600 seconds; ML Kit to 20 seconds; Room flow reads to 3 seconds. On instrumentation timeout, the runner saves partial output and requests `am force-stop dev.snapbudget` only on the explicitly selected serial; it never clears data. If force-stop cannot be confirmed, it reports that manual recovery is needed on that serial. `all` is fail-fast. Logs/reports are under `app/build/reports/local-tests/` (ignored build output). Build dependency downloads may need network access. Runtime OCR and persistence tests run only once the selected device is offline.
- Each storage test creates uniquely named synthetic databases and deletes its database/WAL/SHM files. The runner does not clear user app storage. Instrumentation assertions check both packaged target and test APKs for `INTERNET`, and check app backup is disabled; database tests check the private app data path.

## Prerequisites and emulator setup

For this host, use the verified local tools for a command (no global environment/profile changes): `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; export ANDROID_HOME="$HOME/Library/Android/sdk"; export ANDROID_SDK_ROOT="$ANDROID_HOME"; export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"`. On other machines, install/use JDK 25 and set `JAVA_HOME`, `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), and `PATH` to SDK command-line tools. Verify `"$JAVA_HOME/bin/java" -version`; the doctor intentionally rejects a mismatched runtime. This host has a user-local Temurin 21 installation from earlier setup, but uses Android Studio JBR 25.0.3 for Gradle 9.6.0. Install SDK command-line tools separately from Android Studio if needed. `doctor` and JVM tests require SDK Platform 36 (Android 16) and Build-Tools 36.0.0; only device mode additionally requires platform-tools/adb. Required packages:

```sh
"$HOME/.local/bin/android" --no-metrics sdk install platforms/android-36
# Optional API 36 ARM64 emulator image (Apple Silicon); not installed by this setup:
# "$HOME/.local/bin/android" --no-metrics sdk install system-images/android-36/google_apis/arm64-v8a
# On x86_64 hosts, use this image instead:
# "$HOME/.local/bin/android" --no-metrics sdk install system-images/android-36/google_apis/x86_64
# Build-Tools 36.0.0 and platform-tools are also required by this repo/runner.
```

Review and accept SDK licenses yourself (for example, `sdkmanager --licenses`); the runner never accepts licenses. For an Android 16 (API 36) test emulator, install the matching system image explicitly if needed, create the AVD, and boot it manually:

```sh
avdmanager create avd -n snapbudget-api36 -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6
emulator -avd snapbudget-api36 -no-snapshot -no-audio
adb devices -l
```

Use `x86_64` in the AVD package on x86_64. An existing API 37 AVD is not the primary API 36 phone baseline. For an actual Android 16 phone, list and explicitly select its adb serial instead; this guide does not boot a device or alter network state. After boot completes, use the emulator UI to enable airplane mode and ensure Wi-Fi is off; verify with `adb shell settings get global airplane_mode_on` (must be `1`) and `adb shell settings get global wifi_on` (must be `0`). Then run `scripts/test-local.sh device --serial <listed-serial>`. There is no automatic emulator creation, boot, or device network modification.

## Coverage and honest status

| Layer | Real coverage here | Limitations/status |
|---|---|---|
| JVM parser | Synthetic committed receipt fixture: amount, merchant, status/direction, transaction ID, paise precision, Indian grouping, zero/negative/overflow/malformed inputs, and desired date assertion | `parsesReceiptDate` is expected to fail today: parser uppercases the month token and parses with a case-sensitive `MMM` formatter. This is intentionally not weakened to a null assertion, and production parser is unchanged. |
| Instrumentation OCR | Bundled ML Kit Latin recognizer processes a deterministic, locally drawn synthetic receipt; parsed amount/merchant/id/status and Room insertion are checked before the date assertion | The known date parsing failure is also surfaced at the end of this independent OCR pipeline test, after OCR and preceding parsed/persisted checks. OCR rendering/recognition can vary by runtime; no screenshot or real receipt is used. |
| Room instrumentation | Separate unique file DB test: transaction-ID and image-hash uniqueness, close/reopen persistence, isolation, time-bounded Flow reads, cleanup/private path | Uses the current Room DAO/database as-is; no production APIs are added. |
| Compose harness | Test-only `ComponentActivity` from Compose's debug test manifest; semantics identity/text and bounded host geometry | Test harness only—not an app screen, launcher test, or product E2E. No missing production `MainActivity` is supplied or disguised as implemented. |
| Privacy | Instrumentation assertions inspect both packaged target and test APKs, backup metadata, and private DB path | APK inspection after compile shows the target APK currently merges `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` from transitive dependencies (the test APK does not). The manifest test intentionally detects this as a failure; no production manifest/dependency change was made. OCR tests still require the selected device to be offline. |

Host runner regressions are exercised without Android SDK/device using `scripts/test-local.sh host` (Python standard library only). They cover success, JUnit failure with adb exit 0, crash, error result, empty/zero/missing terminal summaries, nonzero exit, timeout and safe cleanup, CI/Jenkins flags, device ambiguity/explicit selection/offline checks, bounded adb calls, serial-scoped installs, and a real copied `all` runner invocation in a clean fake SDK/JDK scaffold that verifies report-directory creation and propagation of a synthetic Gradle RED exit. With JDK 25/API36 installed, Android/Kotlin and instrumentation test sources compile and the instrumentation APK assembles. The JVM suite reports 5 tests, 4 passing and the known date assertion failing. Lint reports the known missing `dev.snapbudget.MainActivity` manifest reference (1 error, 17 warnings). No device was connected, so instrumentation was not run. No CI workflows or cloud/device test integrations exist. Unit and instrumentation source is not ignored or silently skipped. A red date assertion is a known baseline product defect, not a green test result.

### Future product journeys (not covered yet)

Once the actual screens and state transitions exist, add local-only deterministic Compose coverage for: image picker/share entry → receipt review and user corrections → confirmed save → totals → duplicate import prevention → process/app restart persistence → delete. Those are planned journeys, not current test coverage; this scaffold does not claim product behavior or E2E completion.
