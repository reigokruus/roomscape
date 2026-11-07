# Test the real CineX app (not the browser prototype)

## Two native platforms, different requirements

- **Android:** this repository already has a native Java/ARCore app and a Gradle build. The APK is a real installable Android package. Android is the available binary-build route on this Linux machine; no web-wrapper substitution is used.
- **iPhone:** `CineX.swiftpm` is SwiftUI/RoomPlan source. Building it needs Apple's iOS SDK/Xcode on a Mac. Room capture requires a **LiDAR-equipped Pro iPhone** and iOS 17+. An ordinary **iPhone 13 is not a LiDAR model**; the app's `RoomCaptureSession.isSupported` check will disable capture. Testing an APK on an iPhone is impossible; they are different native platforms.

The native Swift storyboard changes from `b5a4f21` are preserved, but **are not included in the Android APK**. Android and iOS currently have different feature sets. This handoff is about making Reigo's existing native Android code testable, not claiming feature parity or a compiled iOS build.

## Fast Android install

The existing team CI publishes APKs here:

https://github.com/reigokruus/shootlocal/releases/latest/download/CineX-android.apk

That address is intentionally moving. For reproducible testing choose a specific tag from the evidence report; the locally built APK has its own filename and SHA-256. These are debug-signed test builds, not store releases or production-security guarantees. The shared debug signing identity is intentionally already in the team's project; no production signing secret or paid service is required.

1. Use a **64-bit ARM Android phone**, Android 7.0/API 24 or later, supported by ARCore/Google Play Services for AR. A depth-capable model such as the team's Galaxy S24 is the appropriate scan-validation device. Basic ARCore support alone is not proof that raw depth works; the source negotiates RAW_DEPTH_ONLY, AUTOMATIC and finally DISABLED, so quality/features vary by phone.
2. Download the intended APK on that Android phone. When Android asks, allow this browser/file manager to install unknown apps, then tap Install. You can disable that permission again afterward. Do not disable Play Protect or other security checks.
3. Open **CineX**. Grant Camera permission and install/update Google Play Services for AR if Android requests it. Use a well-lit, textured room and move the phone gently rather than holding it perfectly still.
4. If a developer is using a USB cable, enable USB debugging and authorize their computer on the phone, then run `adb install -r /path/to/CineX-android-local-0.1.<number>.apk`. This is a manual command for the tester; the build script does not install or touch a device.
5. If Android rejects an update, inspect the precise error. `INSTALL_FAILED_VERSION_DOWNGRADE` means use a build number at least as high as the installed app. A signature mismatch needs the correct team-signed build; **do not uninstall automatically**, because that can delete saved scans. Export needed data before deliberately removing an old installation.

No API key is needed for the core scan/save/view/lighting test. Do not enable Ask Claude or live-Claude features for this test: those are optional external API calls, may send room photos, and can incur provider charges. No keys are included or requested by this handoff.

## Five-minute real-phone acceptance test (not performed on this box)

1. Home → **New scan**. Confirm camera opens without a crash, portrait controls fit, and guidance/detail/room progress changes while moving.
2. Scan a small room with identifiable furniture. Tap **Done** and wait for the progress overlay. Confirm the room is visible and the app remains responsive through depth processing/clean-up/training. Scanning quality is not proven by a successful compiler run.
3. Orbit/pinch the saved view; use **Lighting**, move/change an example light and close its panel. Compare it with the actual room: this is an approximate preview, not photorealistic ground truth.
4. Tap **Save**, give the scan a name, return **Home** and reopen it. Force-close/reopen the app and verify persistence. Autosave does not replace this check.
5. Tap **Share** and inspect the ZIP export in Downloads/CineX or the share sheet. Keep it local unless you explicitly intend to send the room photos/layout elsewhere. Verify a non-empty ZIP; do not send automatically.
6. Start a second scan only after saving the first. Deny camera once to check recovery; test missing AR Services/unsupported depth on a spare device if available. Record phone model, Android version, APK version/hash, steps and any crash log.

No physical Android device was connected here, so installation, first launch, real depth capture, rendering, saved-scan reopen and thermal/performance results are **unverified until this checklist is run**.

## Rebuild the actual APK

Install JDK 17 and the Android SDK using the official tools. Required SDK packages are `platform-tools`, `platforms;android-35`, and `build-tools;35.0.0`. Accept the SDK licences locally. The Gradle wrapper/AGP versions come from the repository. Initial builds download dependencies and the Depth Anything model; the APK includes that model afterward.

From the repository root:

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
BUILD_NUMBER=67 scripts/build-android-test.sh
```

Pick a versionCode appropriate for your test phone; 67 is the tested snapshot, not a promise it will remain the newest published version. The script runs the real `assembleDebug`, `testDebugUnitTest` and `lintDebug` tasks; verifies the APK signature, manifest, ZIP/DEX/ARM64/model contents; and copies the APK plus hash and evidence into ignored `out/native/`. **The initial repository had no JVM tests; this branch adds 11 behavioral tests of its actual lighting/planning engines. A NO-SOURCE unit-test task is not a passing Android test suite.** See the evidence report for the actual task results.

The initial full check found a missing explicit legacy-camera hardware declaration; the manifest now marks legacy camera/autofocus optional while keeping ARCore required. Lint finishes with 0 reported errors and 51 existing warnings. Its bundled analyzer also prints a third-party LiteRT Kotlin-metadata compatibility diagnostic, so this is not a claim that static analysis is entirely clean. No lint checks were disabled or baselined.

The script builds only: it never publishes a release, pushes a branch, merges a PR, installs on a phone or sends a scan. Existing `.github/workflows/android-release.yml` is unchanged. It publishes only on matching main-branch pushes or an explicitly dispatched workflow; no workflow was dispatched by this work.

## iOS: exact remaining gate

Checks found no `xcodebuild`/Apple SDK on this Linux box, no connected Mac in the owner's registered machines, and only the Android workflow in the repository. Listing repository self-hosted runners returned HTTP 403, so **no assertion is made that Reigo has no Mac runner**; its availability could not be verified with current access. No paid cloud Mac or new CI service was started.

Smallest real next requirement: an authorized Mac with Xcode 15+ (or a sanctioned existing Mac runner with build permission) to compile `CineX.swiftpm`. To test actual capture, add a LiDAR Pro iPhone on iOS 17+ and select a signing team locally. A paid Apple developer membership is not required for a local free-team device build; public TestFlight/App Store distribution is a separate signing/distribution task and was not attempted. Use [the exact native compile/device gate](native-planning.md). An ordinary iPhone 13 can exercise supported non-capture UI after a correctly signed build, but cannot validate RoomPlan scanning.

Official references: https://developer.apple.com/augmented-reality/roomplan/ ; https://developer.android.com/tools/sdkmanager and https://developers.google.com/ar/devices . Public Grok Bot build-method research also points to running Xcode on an actual Mac, not turning a browser page into an IPA: https://botpages.co/use-cases/rebuild-your-android-app-for-ios-with-grok-bot .
