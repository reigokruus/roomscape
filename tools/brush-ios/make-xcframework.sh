#!/bin/zsh
# Wraps Brush's iOS static library (built per README.md) as CineX.swiftpm/Vendor/BrushC.xcframework (git-ignored: it's
# over a gigabyte). With it there, the iPhone app links Brush in (Package.swift checks for it); without it, the app
# builds as before and the photo-real preview uses its stand-in trainer.
#   tools/brush-ios/make-xcframework.sh <brush checkout>
set -e
HERE=${0:A:h}; LIB=${1:?brush checkout}/target/aarch64-apple-ios/release/libbrush_c.a
OUT=$HERE/../../CineX.swiftpm/Vendor/BrushC.xcframework
[ -f $LIB ] || { echo "no $LIB: build Brush for aarch64-apple-ios first"; exit 1; }
rm -rf $OUT; mkdir -p ${OUT:h}
xcodebuild -create-xcframework -library $LIB -headers $HERE/include -output $OUT
# Xcode caches the package graph and the Swift modules: make it see the new framework (both are only caches)
rm -rf ~/Library/Caches/org.swift.swiftpm/manifests
touch $HERE/../../CineX.swiftpm/BrushTrainer.swift $HERE/../../CineX.swiftpm/SplatPreview.swift
echo "made $OUT (next app build links Brush in: the executable grows by about 90 MB)"
