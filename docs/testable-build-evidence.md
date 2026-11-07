# Actual native test-build evidence

Checked 2026-11-07 11:04 EET (Europe/Tallinn).

## Native Android binary produced here

- APK: `CineX-android-local-0.1.67.apk`
- Size: 68,798,853 bytes.
- SHA-256: `5d07f9c394504ff858ba2381c177eb28bfb319a5de4df027b58ff336dee5ee63`.
- Package: `com.hackathon.setscan`; versionCode 67; versionName 0.1.67.
- Minimum Android API 24; target/compile API 35; native ABI `arm64-v8a`.
- This is the real Java/ARCore app, **not HTML, a WebView wrapper or an iOS binary**.
- Repository Android snapshot: `baf8d29b01180ca87bbae8791c3b621385b12cbe`, plus the minimal camera-feature manifest declarations and test/build handoff. Previous Swift integration from `b5a4f21` is preserved separately, not included in the APK.
- Build tools: Temurin JDK 17.0.21, Gradle wrapper 8.14.3, Android Gradle Plugin 8.13.2, SDK platform/build-tools 35/35.0.0.

## Executed successfully

- `assembleDebug`: native Java compilation, dexing, assets, ARM64 libraries, packaging and debug signing completed.
- `testDebugUnitTest`: **11 tests, 0 failures, 0 errors, 0 skips**, using the actual `Lighting` and `ShotPlanner` Java classes. Coverage: inverse-square lux, disabled/away-facing light, ambient exposure and EV, face exposure, neutral white balance, subject/floor coordinates, default setup, sparse-scan readiness, camera/light plans and product table height.
- `lintDebug`: completed with **0 reported errors, 51 warnings**. Initial manifest error was fixed, not suppressed. A third-party LiteRT Kotlin metadata 2.4-versus-analyzer-2.2 diagnostic is still printed; therefore not all static-analysis compatibility is proven.
- `apksigner verify --verbose --print-certs`: valid APK v2 signature, one signer. Certificate SHA-256: `c57a075f742f6c6b71fc9078af945491f929272498099f19fc65eaea9c09dffe`, matching the team's existing CI APK identity.
- `aapt dump badging`: expected application ID, launch activity, API levels and ARM64 ABI.
- APK ZIP integrity test: no corrupt entries. AndroidManifest, DEX, native ARM64 libraries and bundled 27,733,680-byte depth model present. Model is stored uncompressed for the app's memory-mapped loader. `zipalign -c 4`: exit 0. This is not a comprehensive 16 KB-page-device runtime validation.
- Preserved Swift planning core: **36 tests passed**, 0 failures; this is still not an iOS app compilation.
- `bash -n scripts/build-android-test.sh` and `git diff --check`: passed.

## Independently existing team CI build

The already-running team workflow for `baf8d29` succeeded without being triggered or changed by this task:
https://github.com/reigokruus/shootlocal/actions/runs/42357656938

Team release 0.1.67 was published at **2026-11-07 11:00:34 EET (Europe/Tallinn)**:
https://github.com/reigokruus/shootlocal/releases/tag/android-v0.1.67

Direct existing team binary:
https://github.com/reigokruus/shootlocal/releases/download/android-v0.1.67/CineX-android.apk

Its published SHA-256 is `41ebc54bcfd757cefd40a9dbbfe6923e8c6a226be2f1d5c1af3eae9db05a3207` (68,793,766 bytes). **The local build is not byte-identical to this CI artifact**; its manifest declarations and build environment differ. Use the correct filename/hash when reporting a test.

## Not verified / remaining requirements

- `adb devices -l` found **no attached devices**. No APK installation, launch, AR tracking/depth capture, 3D rendering, persistence after relaunch, Files sharing, permissions flow or thermal/performance behavior was observed here. A compiled/signed package is testable, not proof of hardware functionality.
- The repo initially lacked Java tests; those have now been added. They do not emulate or certify ARCore, Android UI or the neural depth model on a phone.
- The iOS app still has no locally compiled binary. This host is Linux and has no Apple SDK/xcodebuild; the owner's registry shows only a disconnected phone labelled iPhone 13, no Mac. Repository workflows list Android only. The self-hosted runner inventory API returned HTTP 403, so availability of a separate Reigo Mac runner could not be confirmed. No bypass or paid cloud Mac was attempted.
- Actual iOS compilation requires an authorized Mac/Xcode environment; real room capture additionally requires iOS 17+ and a LiDAR Pro iPhone. A normal iPhone 13 lacks LiDAR and cannot install the Android APK. An Apple signing team is required for a local iPhone build.
- No API keys, production signing credentials or paid services were used. The repository's intentionally public shared debug identity was used by its existing build configuration. Optional external-AI features were not called.
- No existing workflow was dispatched; no release, deployment, store submission, PR merge or external message was made.

## Reproduction and local evidence

Run [the native build script](../scripts/build-android-test.sh) with JDK 17, SDK 35 and BUILD_NUMBER=67. The installer/hardware checklist is [testable-app.md](testable-app.md).

Local toolchain paths on this box: `/workspace/android-native-tools/jdk17`, `/workspace/android-native-tools/sdk` and `/workspace/android-native-tools/gradle-cache`. Build evidence/artifact directory: `/workspace/shootlocal-testable/local-build/`. Full initial failed lint run: `/workspace/shootlocal-testable/initial-build-lint-failure.log`. Preserved Swift log: `/workspace/shootlocal-testable/swift-core-tests.log`.
