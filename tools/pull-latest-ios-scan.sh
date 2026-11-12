#!/bin/zsh
# Copies the newest iPhone scan (RoomScape, rk's iPhone 15 Pro on USB) to this Mac and packs it for a PC rebuild:
#   tools/pull-latest-ios-scan.sh [Scan_YYYY-MM-DD_HH-MM-SS]
# Read-only on the phone: lists and copies files from the app's container, never installs, launches or deletes.
# Refuses when the phone isn't on the cable or a scan is being recorded. Then:
#   recordings/ios/<scan>/                the scan as the app saved it (recording, dots, mesh; no photos or screen video)
#   recordings/rebuild/ios-<stamp>/frames rebuild frames (ios_frames.py), if the Python tools are set up
#   recordings/handoff/ios-<stamp>/       the package for the PC (scan + frames + code + README + SHA256SUMS.txt)
#   recordings/handoff/serve-ios-<stamp>.sh  the LAN server for it (rk runs it; LAN only, random path, 60 min)
# Scans show a real home: recordings/ is never committed.
set -e
REPO=${0:A:h:h}
# the phone: the iPhone that's connected; the app: the bundle ID in this Mac's own (uncommitted) Package.swift
DEV=$(xcrun devicectl list devices 2>/dev/null | awk '/connected|available/ && /iPhone/ && !/simulat/ {print $3; exit}')
BUNDLE=$(sed -n 's/.*bundleIdentifier: "\(.*\)".*/\1/p' $REPO/CineX.swiftpm/Package.swift | head -1)
[ -n "$DEV" ] && [ -n "$BUNDLE" ] || { echo "!!! No iPhone found (or no bundle ID in Package.swift)."; exit 1; }
DC=(xcrun devicectl device)
TMP=$(mktemp -d)
trap 'rm -rf $TMP' EXIT

# 1. wired, and not recording (the app records into tmp/recording-<id>/ while scanning)
$DC info details --device $DEV 2>/dev/null | grep -q "Transport Type: wired" || { echo "!!! The iPhone isn't on the USB cable (or not reachable): plug it in and try again."; exit 1; }
$DC info files --device $DEV --domain-type appDataContainer --domain-identifier $BUNDLE --subdirectory tmp --json-output $TMP/tmp.json >/dev/null 2>&1 || true
if [ -f $TMP/tmp.json ] && python3 - $TMP/tmp.json <<'EOF'
import json, sys, datetime
fs = json.load(open(sys.argv[1]))['result']['files']
now = datetime.datetime.now(datetime.timezone.utc)
busy = [f for f in fs if f['relativePath'].startswith('recording-') and f['relativePath'].endswith('frames.bin')
        and (now - datetime.datetime.fromisoformat(f['metadata']['lastModDate'].replace('Z', '+00:00'))).total_seconds() < 120]
sys.exit(0 if busy else 1)
EOF
then echo "!!! A scan is being recorded on the iPhone right now: finish it (Done) first."; exit 1; fi

# 2. which scan: the newest (or the one named)
$DC info files --device $DEV --domain-type appDataContainer --domain-identifier $BUNDLE --subdirectory Documents --json-output $TMP/docs.json >/dev/null
SCAN=${1:-$(python3 -c "
import json; fs=json.load(open('$TMP/docs.json'))['result']['files']
print(sorted({f['relativePath'].split('/')[0][:-8] for f in fs if f['relativePath'].split('/')[0].endswith('.capture')})[-1])")}
echo "Scan: $SCAN"
python3 - $TMP/docs.json $SCAN > $TMP/list.txt <<'EOF'
import json, sys
fs = json.load(open(sys.argv[1]))['result']['files']; scan = sys.argv[2]
skip = ('/photos/', '/screen.mp4', '/before-rebuild/', '/decals/', '/processing-', '/splat/dataset/')
for f in fs:
    p = f['relativePath']
    if f['resources']['isDirectory']: continue
    if (p.startswith(scan + '.capture/') and not any(s in p for s in skip)) or p == scan + '.json':
        print(p)
EOF
grep -q "recording/frames.bin" $TMP/list.txt || { echo "!!! $SCAN has no raw recording (recording/frames.bin): it can't be rebuilt."; exit 1; }
OUT=$REPO/recordings/ios/$SCAN
echo "Copying $(wc -l < $TMP/list.txt | tr -d ' ') files to $OUT (read-only on the phone)…"
while read -r p; do
  mkdir -p "$OUT/${p:h}"
  [ -s "$OUT/$p" ] || $DC copy from --device $DEV --domain-type appDataContainer --domain-identifier $BUNDLE --source "Documents/$p" --destination "$OUT/$p" >/dev/null
done < $TMP/list.txt
REC=$OUT/$SCAN.capture/recording
for f in video.mov frames.bin depth.bin; do [ -s $REC/$f ] || { echo "!!! $f missing in the copy"; exit 1; }; done
echo "Recording: $(( $(stat -f %z $REC/frames.bin) / 120 )) frames, $(du -sh $REC | cut -f1)"

# 3. rebuild frames (if the Python tools are set up on this Mac)
STAMP=$(echo $SCAN | sed -E 's/Scan_2026-([0-9]+)-([0-9]+)_([0-9]+)-([0-9]+).*/\1\2-\3\4/')
PY=$(ls -d /private/tmp/claude-501/*/*/scratchpad/uwvenv/bin/python 2>/dev/null | head -1)
FR=$REPO/recordings/rebuild/ios-$STAMP/frames
if [ -n "$PY" ]; then nice -n 10 $PY -u $REPO/tools/room-rebuild/ios_frames.py $REC $FR --every 0.4 | tail -1
else echo "(No Python tools here: the PC makes the rebuild frames with tools/room-rebuild/ios_frames.py.)"; fi

# 4. the package for the PC
H=$REPO/recordings/handoff/ios-$STAMP
mkdir -p $H/code
cp -R $OUT $H/scan-$SCAN
[ -d $FR ] && cp -R $FR $H/rebuild-frames
(cd $REPO && tar cf $H/code/tools-room-rebuild-and-viewer.tar tools/room-rebuild tools/room-viewer)
cp $REPO/CineX.swiftpm/RawRecorder.swift $REPO/CineX.swiftpm/ScanCapture.swift $H/code/
sed "s/10 Nov, 20:09/$SCAN/; s/Scan_2026-11-10_20-09-19/$SCAN/g" $REPO/recordings/handoff/ios-2009/README.md > $H/README.md 2>/dev/null || true
cp $REPO/docs/pc-splat-rebuild.md $H/PC-STEPS.md
(cd $H && find . -type f ! -name SHA256SUMS.txt | sort | xargs -I{} shasum -a 256 "{}" > SHA256SUMS.txt)
sed "s/ios-2009/ios-$STAMP/g; s/47814/47815/" $REPO/recordings/handoff/serve-ios-2009.sh > $REPO/recordings/handoff/serve-ios-$STAMP.sh
chmod +x $REPO/recordings/handoff/serve-ios-$STAMP.sh
echo
echo "Package: $H ($(du -sh $H | cut -f1), $(wc -l < $H/SHA256SUMS.txt | tr -d ' ') files, SHA256SUMS.txt inside)"
echo "To send it to the PC over the home Wi-Fi, run:"
echo "  $REPO/recordings/handoff/serve-ios-$STAMP.sh"
