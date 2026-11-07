# Native planning verification evidence

Checked 2026-11-07 09:53 EET (Europe/Tallinn).

## Actually executed

- Official Swift 6.0.3 compiler, x86_64 Linux; the shared Foundation-only Core source is used unchanged by the iOS app target.
- `swift test`: **36 XCTest behavioral tests passed, 0 failures**.
- `swift test -c release`: **the same 36 tests passed, 0 failures** (not 72 distinct tests).
- `swiftc -frontend -parse` over every app Swift source except the manifest: exit 0. This is syntax parsing ONLY, not iOS framework type-checking.
- `git diff --check`: exit 0.

Test coverage: full-frame FOV, wide/long lenses and turned cameras, full-target bounds, wall/item obstruction and own-camera-rig treatment, open/disconnected/self-crossing/concave/rotated room geometry, bounded quick placement and no-space errors, 10 cm snapping, view independence and target-ID remapping, day/rate allocations, equipment-only preset replacement/relocation/height, all starter kits, versioned JSON round-trips, invalid/oversized imports and count/range limits, cross-room rejection, append without overwrite, corrupt-file visibility, safe UUID filenames, atomic save rejection, bounded undo/redo, bundled synthetic fixture and the edit→preset→storyboard→export→import→save→reload→frame-check vertical slice.

## Not executed — mandatory before merge/demo

- No Xcode, Apple iOS SDK, signing identity or connected LiDAR iPhone was available.
- **No native app build or binary was produced.** SwiftUI/SceneKit/RoomPlan API type correctness and runtime interactions remain unverified.
- No scan, drag gesture, SceneKit camera projection, Files provider UI, memory/performance, accessibility or relaunch test was observed on iOS.
- No Android edits, app deployment, production rollout, team message or PR merge was performed.
- Use the exact compile/device checklist in [native-planning.md](native-planning.md).

## Local reproduction on this box

The compiler was initially absent. An official toolchain and its missing public Debian ncurses runtime were installed/extracted outside the repository. They contain no repository credentials. APT package-index fetching failed, so no iOS platform support is implied by the Linux setup.

```sh
cd /workspace/shootlocal-docs
export LD_LIBRARY_PATH=/workspace/swift-deps/usr/lib/x86_64-linux-gnu
/workspace/swift-toolchain/usr/bin/swift test
/workspace/swift-toolchain/usr/bin/swift test -c release
/workspace/swift-toolchain/usr/bin/swiftc -frontend -parse $(rg --files CineX.swiftpm -g '*.swift' -g '!Package.swift')
```

Full local logs: `/workspace/shootlocal-native-verification/debug.log` and `release.log`. On a normal Mac/Linux Swift installation, plain `swift test` from the root is sufficient.

## Shared-source digests at verification

| File | SHA-256 |
| --- | --- |
| `CineX.swiftpm/Planning/Core/PlacementAndFrame.swift` | `d95c2163f937b40c6ab564db8f2d3bc945e12450e998e9d7188ae2e6a4d0752e` |
| `CineX.swiftpm/Planning/Core/PlanFiles.swift` | `a7e254d5e0c08440c1c9b8b087c4867f05e21589b18289c786fbba05808a16e3` |
| `CineX.swiftpm/Planning/Core/PlanModels.swift` | `ddb8b35352149c3953abe3a29472857ed2527ca9fba63e18d20105cfe8e9992e` |
| `Tests/Fixtures/synthetic-room.json` | `069d28a9e9d9931f71f63f743c930670cc980bae96f2f65846b0c033ffdf9bf4` |
| `Tests/PlanningCoreTests/PlanningCoreTests.swift` | `6a770657e1d06fd9f8fcda6422eebc53038d2c5d6463611df7fef378a8ca774c` |
