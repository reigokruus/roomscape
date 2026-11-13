#!/usr/bin/env bash
# Brush for the Android app (photo-real room preview), as tools/brush-ios does it for the iPhone.
#   tools/brush-android/build.sh <brush checkout prepared as in tools/brush-ios/README.md, plus brush-android.patch>
# Needs: rustup target add aarch64-linux-android; the Android NDK (ANDROID_NDK, default: $ANDROID_HOME/ndk/27.2.12479018).
# Writes android/app/src/main/jniLibs/arm64-v8a/libbrush_c.so and libbrushjni.so (git-ignored).
set -euo pipefail
B="$(cd "$1" && pwd)"; HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
NDK="${ANDROID_NDK:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/27.2.12479018}"
T="$NDK/toolchains/llvm/prebuilt/$(uname -s | tr A-Z a-z)-x86_64/bin"
export CC_aarch64_linux_android="$T/aarch64-linux-android29-clang" AR_aarch64_linux_android="$T/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$T/aarch64-linux-android29-clang"
# 16 KB pages (Android 15+ warns otherwise): only the final library's link needs it, so cargo rustc passes it to brush-c alone
(cd "$B" && CARGO_PROFILE_RELEASE_LTO=false cargo rustc -p brush-c --release --target aarch64-linux-android --crate-type cdylib -- -C link-arg=-Wl,-z,max-page-size=16384)
OUT="$REPO/android/app/src/main/jniLibs/arm64-v8a"; mkdir -p "$OUT"
cp "$B/target/aarch64-linux-android/release/libbrush_c.so" "$OUT/"
"$T/llvm-strip" --strip-unneeded "$OUT/libbrush_c.so"
"$T/aarch64-linux-android29-clang" -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -o "$OUT/libbrushjni.so" "$HERE/brushjni.c" -L"$OUT" -lbrush_c
ls -la "$OUT"
