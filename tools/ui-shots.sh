#!/bin/zsh
# UI screenshots of the RoomScape viewer, without anyone's phone (docs/test-harness.md).
#   tools/ui-shots.sh ios [room.rmsc]      iPhone 15 Pro simulator: home, the four views, Objects, Lighting
# Out: tools/ui-shots/<date>/*.png. Debug builds only (the launch options are compiled out of release).
set -e
REPO=${0:A:h:h}
OUT=$REPO/tools/ui-shots/$(date +%Y%m%d-%H%M%S)
mkdir -p $OUT
ROOM=${2:-$REPO/tools/harness/harness-out/synthetic-chest.rmsc}
if [[ ! -f $ROOM ]]; then
  (cd $REPO/tools/harness && swift build -c release >/dev/null && ./.build/release/Harness rmsc chest $ROOM >/dev/null)
fi

case ${1:-ios} in
ios)
  SIM=$(xcrun simctl list devices | awk -F'[()]' '/RS iPhone 15 Pro/ {print $2; exit}')
  [[ -z $SIM ]] && SIM=$(xcrun simctl create "RS iPhone 15 Pro" com.apple.CoreSimulator.SimDeviceType.iPhone-15-Pro com.apple.CoreSimulator.SimRuntime.iOS-27-2)
  xcrun simctl boot $SIM 2>/dev/null || true
  DD=${DD:-/tmp/roomscape-sim}
  (cd $REPO/CineX.swiftpm && xcodebuild -jobs 4 -scheme RoomScape -destination "id=$SIM" -configuration Debug -derivedDataPath $DD build 2>&1 | grep -E "error:|\*\* BUILD")
  APP=$(ls -d $DD/Build/Products/Debug-iphonesimulator/*.app | head -1)
  BUNDLE=$(/usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" $APP/Info.plist)
  xcrun simctl uninstall $SIM $BUNDLE 2>/dev/null || true
  xcrun simctl install $SIM $APP
  shot() { # name, seconds to wait, launch options...
    local name=$1 wait=$2; shift 2
    xcrun simctl launch --terminate-running-process $SIM $BUNDLE "$@" >/dev/null
    sleep $wait
    xcrun simctl io $SIM screenshot $OUT/$name.png >/dev/null 2>&1 && echo "  $name.png"
  }
  echo "iPhone simulator ($SIM), room $ROOM:"
  shot 1-home 4
  shot 2-viewer-photo 60 -importRmsc $ROOM  # the first open builds the photo room and splats
  shot 3-viewer-splats 25 -openLatestScan -style splats
  shot 4-viewer-points 25 -openLatestScan -style points
  shot 5-viewer-mesh 25 -openLatestScan -style mesh
  shot 6-objects-selected 25 -openLatestScan -selectObject chest
  shot 7-lighting 25 -openLatestScan -openPanel lighting
  # The scan screen from a raw recording (VirtualScan.swift): REC=<recording folder> (default: the newest in
  # recordings/ios; those show someone's home, so the shots stay in tools/ui-shots/, never committed).
  REC=${REC:-$(ls -d $REPO/recordings/ios/*/frames.bin(N) 2>/dev/null | tail -1 | xargs -I{} dirname {})}
  if [[ -n $REC && -d $REC ]]; then
    shot 8-scan-start 12 -virtualScan $REC -virtualScanAt 2
    shot 9-scan-middle 12 -virtualScan $REC -virtualScanAt 35
    # A screen video of the scan screen playing the recording.
    xcrun simctl launch --terminate-running-process $SIM $BUNDLE -virtualScan $REC >/dev/null
    sleep 6
    xcrun simctl io $SIM recordVideo --codec h264 $OUT/scan-run.mp4 >/dev/null 2>&1 & VID=$!
    sleep 20; kill -INT $VID; wait $VID 2>/dev/null; echo "  scan-run.mp4"
  fi
  xcrun simctl shutdown $SIM  # the 8 GB Mac: one simulator, shut down right after
  ;;
esac
echo "Screenshots in $OUT"
