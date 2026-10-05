# Local Android test foundation

## Execution contract

The checked-in wrapper uses the official Gradle 9.6.0 distribution and pins `distributionSha256Sum` to its published SHA-256: `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`. The project targets Android 16/API 36 (`compileSdk`/`targetSdk` 36) with AGP 9.4.1. Google's [AGP 9.4 compatibility notes](https://developer.android.com/build/releases/gradle-plugin) support Gradle 9.6.0, Build-Tools 36.0.0, and max API 37; see [Android 16 SDK setup](https://developer.android.com/about/versions/16/setup-sdk). Built-in Kotlin is used (AGP's KGP 2.2.10 runtime); the Compose compiler plugin is 2.2.10, KSP is 2.3.12, and Java/Kotlin bytecode target remains 17.

Gradle's configuration cache is enabled by default for local builds. Gradle uses its default fail-on-problems behavior; to opt out for an individual command, pass `--no-configuration-cache`. See [Gradle's configuration cache documentation](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html).

- Use `scripts/test-local.sh {doctor|host|unit|device|all}`. `host` runs standard-library runner protocol tests and needs no Android SDK/JDK/device. `unit` runs those and JVM parser tests; it requires JDK 25 and SDK platform 36/build-tools 36.0.0, but not adb. `device`/`all` additionally require Python 3, platform-tools, and `--serial SERIAL` unless exactly one online adb device exists. An explicit, online serial is always selected before installation/execution. The script rejects CI, and the Gradle connected-test task also has a CI guard.
- Device execution requires Android airplane mode on **and Wi-Fi off**. The script reads those settings and fails closed; it never toggles networking, boots an emulator, clears app data, or targets an unselected device. Check that no other network path is active yourself. Do not run instrumented tests with private data on a physical device.
- ADB device/settings reads are bounded to 15 seconds; boot wait is bounded to 120 seconds; each APK install to 120 seconds; instrumentation to 600 seconds; ML Kit to 20 seconds; Room flow reads to 3 seconds. On instrumentation timeout, the runner saves partial output and requests `am force-stop dev.snapbudget` only on the explicitly selected serial; it never clears data. If force-stop cannot be confirmed, it reports that manual recovery is needed on that serial. `all` is fail-fast. Logs/reports are under `app/build/reports/local-tests/` (ignored build output). Build dependency downloads may need network access. Runtime OCR and persistence tests run only once the selected device is offline.
- Each storage test creates uniquely named synthetic databases and deletes its database/WAL/SHM files. The runner does not clear user app storage. Instrumentation assertions check both packaged target and test APKs for `INTERNET` and `ACCESS_NETWORK_STATE`, and check app backup is disabled; database tests check the private app data path.

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

## Coverage and verified status

| Layer | Coverage | Status and limitations |
|---|---|---|
| JVM parser | Synthetic fixtures cover provider-factory selection, PhonePe and Google Pay amount/merchant/status/direction/date parsing, provider transaction identity, paise precision, Indian grouping, malformed/overflow/nonpositive values, and strict date behavior including leap days. | Fixtures are synthetic and contain no private receipt data. |
| Instrumentation OCR and reader | Bundled ML Kit processes deterministic, locally drawn synthetic PhonePe and Google Pay receipts. Tests cover provider reader selection, parser/layout behavior, bounded decode, cleanup, same-provider retry gating, higher-resolution recovery, cancellation, ambiguity, and preview identity. | Synthetic tests do not by themselves establish accuracy on real receipts. |
| Room and UI | Instrumentation covers local persistence/deduplication, Compose review/confirmation flows, picker contract, and Activity lifecycle behavior. | Included in the passing 34/34 API 37 suite. API 36 runtime validation remains pending. The real system picker/provider journey was not exercised. |
| Host runner | `scripts/test-local.sh host` exercises standard-library runner protocol regressions. | Latest result: 20/20 passed. |
| Privacy | Instrumentation checks packaged APK permissions/backup settings and local persistence behavior. | Latest offline JVM, debug/test/release build, and lint checks were independently verified green. No cloud/device test integration is used. |

A separate approved comparison on two local user inputs matched the expected amount in the production reader. That comparison emitted only whitelisted booleans/enums; no OCR text, monetary values, names, identifiers, hashes, screenshots, or image contents are documented or committed. The source image files were left untouched. The supplied expected value was runtime-only; do not infer that it or the user inputs were removed. Temporary diagnostic instrumentation and cache were removed, and the normal APK was restored. These two-input results are separate from the synthetic full-suite results.

The launcher supports manual entry and a system document picker for user-selected images (`image/*`, openable documents, `EXTRA_LOCAL_ONLY`). The picker does not request broad storage or persistable URI access; the selected URI is passed only to the receipt reader. `EXTRA_LOCAL_ONLY` asks the provider for local content but cannot guarantee that a separately implemented provider will avoid network activity. Choose an on-device image. The reader accepts JPEG, PNG, WebP, HEIC, and HEIF content, limits original bytes to 20 MiB and source dimensions to 12,000 pixels per side / 100 million pixels total, and first decodes to a 2,048-pixel longest side / 4-megapixel cap. If that pass identifies an eligible PhonePe or Google Pay receipt but no amount, the reader may retry once with the same provider strategy at up to a 4,096-pixel longest side / 8-megapixel cap within the shared 20-second OCR budget. The retry supplies only the recovered amount; merchant, date/time, and transaction ID remain from the first parse, and conflicting nonnull transaction IDs retain the initial null-amount preview. An ambiguous, mixed-provider, or ineligible retry does not broaden parser candidate selection.

Bundled OCR uses `ReceiptParserFactory` to select one provider strategy and screen for an eligible PhonePe or completed outgoing Google Pay receipt before suggestions are shown. Mixed-provider text is rejected. Users review/edit the suggested merchant, positive INR amount, local date/time, and category; missing suggestions stay blank and must be completed. Nothing is written until explicit confirmation. Receipt bytes and OCR text are not persisted; only expense fields, original-byte SHA-256 identity, and optional transaction ID are stored. Duplicate protection uses image hash and, when available, transaction ID. Google Pay transaction IDs carry a `GPAY:UPI:` or `GPAY:GOOGLE:` provider prefix; existing PhonePe transaction IDs keep their legacy format. Differently encoded copies without a transaction ID may have different hashes and may not be recognized as duplicates. The list displays local all-time INR total and expense count, and deletion requires confirmation.

The latest complete 34-test API 37 run supersedes the earlier 24-test result. Earlier full-suite attempts were interrupted before completion; the cause was not established, so those interruptions are not attributed to a specific defect. Latest focused OCR/retry, host, JVM, build, and lint results are green. API 36 runtime remains pending. No formatting task is configured. No CI workflows or cloud/device test integrations exist.

### Remaining coverage limits

The real system picker/provider journey remains untested; its contract is tested without launching it. API 36 runtime validation remains pending. Some UI error paths (storage-observation failure/retry, delete failure, and system-back behavior) lack focused coverage. `EXTRA_LOCAL_ONLY` is a provider request, not an enforcement boundary for other apps. General OCR ambiguity remains possible and users can correct suggestions during review. Synthetic full-suite results are not evidence of broad real-receipt accuracy. No user Downloads image or private source file was changed.
