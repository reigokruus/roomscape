# Brush for the iPhone (photo-real room preview trainer)

Brush (https://github.com/ArthurBrussee/brush, Apache-2.0) has no iOS build upstream. `brush-ios.patch` (against
Brush commit 1388f74) makes the `brush-c` library (C interface: `train_and_save`) build for `aarch64-apple-ios`:

- `crates/rrfd`: no desktop file dialogs on iOS (`rfd` has no iOS backend; the app passes paths through brush-c).
- `crates/brush-rerun`: iOS uses the existing no-op visualisation tools (as the web build does), so the rerun SDK
  (46 crates of desktop visualisation) isn't built into the app.

Every change is marked "RoomScape change" in the source, as the Apache-2.0 licence asks. The iPhone dependency tree of
brush-c goes from 582 crates to 351 with no desktop-only crates left (checked with `cargo tree`, 12 Nov 2026).

Build (Rust 1.88+, `rustup target add aarch64-apple-ios`):

    git clone https://github.com/ArthurBrussee/brush && cd brush && git checkout 1388f74 && git apply ../brush-ios.patch
    cargo build -p brush-c --release --target aarch64-apple-ios
    # -> target/aarch64-apple-ios/release/libbrush_c.a

Into the iPhone app: `tools/brush-ios/make-xcframework.sh <brush checkout>` makes CineX.swiftpm/Vendor/BrushC.xcframework
(git-ignored); Package.swift links it when it's there, and SplatPreview uses BrushTrainer.swift instead of the stand-in.
Build Brush for the app with `RUSTFLAGS="-C embed-bitcode=no" CARGO_PROFILE_RELEASE_LTO=false` (Apple's tools can't
read Rust's newer LLVM bitcode). brush-c also gets a splat cap, a spherical-harmonics degree and a cancel byte
(BrushTrainOptions in include/brush_c.h). Built and linked 12 Nov 2026 (app executable 3.7 -> 92.5 MB).

Not yet: run on a phone, the memory limit (increased-memory-limit entitlement), a cancel switch in brush-c.
