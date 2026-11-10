#!/bin/zsh
# Installs the committed RoomScape iPhone app on a guest's iPhone plugged into this Mac by cable (rk 10 Nov: Zeynep).
# Builds with rk's local signing (the bundle ID and team in the untracked local Package.swift change, never committed),
# lets Xcode register the new phone, installs as a normal install (an update keeps that phone's scans), never launches
# the app. Refuses with more than one iPhone plugged in, or one that is only on Wi-Fi.
#   ~/DEV/shootlocal/tools/install-ios-guest.sh
set -e
REPO=${0:A:h:h}
PKG=$REPO/CineX.swiftpm/Package.swift
DD=/tmp/roomscape-derived-guest
TMP=$(mktemp -d)
trap 'rm -rf $TMP' EXIT

# 1) the committed app: no changes in CineX.swiftpm except the local signing lines in Package.swift
other=$(cd $REPO && git status --porcelain CineX.swiftpm | grep -v ' CineX.swiftpm/Package.swift$' || true)
if [ -n "$other" ]; then echo "CineX.swiftpm has uncommitted changes, not building:"; echo "$other"; exit 1; fi
BUNDLE=$(sed -n 's/.*bundleIdentifier: "\([^"]*\)".*/\1/p' $PKG | head -1)
TEAM=$(sed -n 's/.*teamIdentifier: "\([^"]*\)".*/\1/p' $PKG | head -1)
if [ -z "$TEAM" ]; then echo "No signing team in $PKG (the local signing setup is missing): open it in Xcode and pick the team first"; exit 1; fi
echo "app: commit $(cd $REPO && git log -1 --format='%h %s' | cut -c1-80)"
echo "signing: $BUNDLE, team $TEAM"

# 2) exactly one real iPhone, on the cable
xcrun devicectl list devices --json-output $TMP/devs.json >/dev/null 2>&1
python3 - $TMP/devs.json > $TMP/pick <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))["result"]["devices"]
phones = [x for x in d if x["hardwareProperties"].get("reality") == "physical" and x["hardwareProperties"].get("deviceType") == "iPhone"]
wired = [x for x in phones if x["connectionProperties"].get("transportType") == "wired"]
for x in phones:
    print("seen:", x["deviceProperties"].get("name"), "-", x["hardwareProperties"].get("marketingName"), "- iOS", x["deviceProperties"].get("osVersionNumber"),
          "-", x["connectionProperties"].get("transportType") or "not connected", file=sys.stderr)
if len(wired) != 1:
    print("ERROR " + ("no iPhone on the cable (Wi-Fi only doesn't count)" if not wired else "more than one iPhone on a cable: unplug all but the guest's"))
else:
    x = wired[0]
    print(x["hardwareProperties"]["udid"], x["connectionProperties"].get("pairingState"), x["deviceProperties"].get("developerModeStatus"),
          x["deviceProperties"].get("osVersionNumber"), x["deviceProperties"].get("name", "").replace(" ", "_"), (x["hardwareProperties"].get("marketingName") or "").replace(" ", "_"))
EOF
read -r UDID PAIRED DEVMODE IOS NAME MODEL < $TMP/pick
if [ "$UDID" = "ERROR" ]; then sed 's/^ERROR //' $TMP/pick; exit 1; fi
echo "phone: ${NAME//_/ } (${MODEL//_/ }), iOS $IOS"
case $MODEL in *Pro*) ;; *) echo "note: scanning needs LiDAR (an iPhone 12 Pro or newer Pro); this one may not have it" ;; esac
SDK=$(xcrun --sdk iphoneos --show-sdk-version)
if [ "$(printf '%s\n%s\n' "$SDK" "$IOS" | sort -V | tail -1)" != "$SDK" ]; then
  echo "note: the phone runs iOS $IOS, newer than this Xcode's iOS $SDK: if the install fails, update Xcode"
fi
if [ "$PAIRED" != "paired" ]; then
  echo "Not paired yet. On the iPhone: unlock it, tap 'Trust This Computer' and enter the passcode, then run this again."; exit 1
fi
if [ "$DEVMODE" != "enabled" ]; then
  echo "Developer Mode is off. On the iPhone: Settings > Privacy & Security > Developer Mode > on, restart when asked,"
  echo "then confirm 'Turn On' after the restart, and run this again."; exit 1
fi

# 3) build for this phone (Xcode registers it and makes a profile that includes it), install, don't launch
cd $REPO/CineX.swiftpm
xcodebuild -jobs 4 -scheme RoomScape -destination "id=$UDID" -configuration Release -allowProvisioningUpdates \
  -derivedDataPath $DD build 2>&1 | grep -E "error:|\*\* BUILD"
APP=$DD/Build/Products/Release-iphoneos/RoomScape.app
[ -d $APP ] || { echo "build failed"; exit 1; }
xcrun devicectl device install app --device $UDID $APP | tail -2
EXP=$(security cms -D -i $APP/embedded.mobileprovision 2>/dev/null | plutil -extract ExpirationDate raw -o - - 2>/dev/null || true)
cat <<EOT

Installed (not started). On the iPhone:
  1. Settings > General > VPN & Device Management > the "Apple Development" entry > Trust (first time only).
  2. Open RoomScape. It starts empty: no scans from this Mac or other phones are in the app.
  3. Test switches on the home screen ("Points-only live view", "Flat tiles") are off; turn them on to try them.
The app's signing runs out on ${EXP:-in about 7 days} (free developer account): after that it won't open until it's
installed again from this Mac (the scans on the phone stay).
EOT
