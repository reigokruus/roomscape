#!/bin/zsh
# The phone-vs-LEGION-vs-MAC race (rk 12 Nov): after the phone made its preview, rebuild EXACTLY its job off the phone.
#   tools/room-rebuild/race_prep.sh [Scan_…]        (default: the newest scan; phone wired, not scanning)
# 1. pulls the scan read-only (tools/pull-latest-ios-scan.sh) and the phone's own preview files (plan check, times);
# 2. makes the dataset with the phone's plan (phone_recipe.py) and checks its picked pictures against the phone's
#    transforms.json; 3. zips it in the quick layout (transforms.json, images/, points.ply at the root) with SHA-256,
#    plan.json and brush-args.txt; 4. writes a LAN serve script for the LEGION (rk runs it).
set -e
REPO=${0:A:h:h:h}; cd $REPO
PY=${PYTHON:-python3}
tools/pull-latest-ios-scan.sh ${1:-} | tee /tmp/race-pull.log
SCAN=$(sed -n 's/^Scan: //p' /tmp/race-pull.log | head -1)
CAP=$(ls -d recordings/handoff/ios-*/scan-$SCAN/$SCAN.capture | tail -1)
STAMP=${SCAN#Scan_}; R=recordings/handoff/race-$STAMP; mkdir -p $R/phone
# the phone's own files (read-only copies): its transforms.json (which pictures it used), its times, its log
DEV=$(xcrun devicectl list devices 2>/dev/null | awk '/connected|available/ && /iPhone/ && !/simulat/ {print $3; exit}')
B=$(sed -n 's/.*bundleIdentifier: "\(.*\)".*/\1/p' CineX.swiftpm/Package.swift | head -1)
for f in splat/dataset/transforms.json splat/train-times.jsonl splat/preview.ply perf-processing-splat.summary.json; do
  xcrun devicectl device copy from --device $DEV --domain-type appDataContainer --domain-identifier $B \
    --source Documents/$SCAN.capture/$f --destination $R/phone/${f:t} >/dev/null 2>&1 || true
done
xcrun devicectl device copy from --device $DEV --domain-type appDataContainer --domain-identifier $B \
  --source Documents/processing-log.txt --destination $R/phone/processing-log.txt >/dev/null 2>&1 || true
PYTHON=$PY $PY tools/room-rebuild/phone_recipe.py $CAP $R/dataset
python3 - $R <<'PYEOF'
import json, sys, os
r = sys.argv[1]
mine = sorted(f['file_path'] for f in json.load(open(f'{r}/dataset/transforms.json'))['frames'])
try: phone = sorted(f['file_path'] for f in json.load(open(f'{r}/phone/transforms.json'))['frames'])
except Exception: phone = None
print(f'pictures: {len(mine)}' + ('' if phone is None else f'; the phone used {len(phone)}; same set: {mine == phone}'
      + ('' if mine == phone else f' (only here {len(set(mine)-set(phone))}, only on the phone {len(set(phone)-set(mine))})')))
PYEOF
mv $R/dataset/plan.json $R/dataset/brush-args.txt $R/
(cd $R/dataset && rm -f ../dataset.zip && zip -q -0 -r ../dataset.zip transforms.json points.ply images)
(cd $R && shasum -a 256 dataset.zip plan.json brush-args.txt > SHA256SUMS.txt && cat SHA256SUMS.txt)
sed "s/ios-2009/race-$STAMP/g; s/47814/47816/" recordings/handoff/serve-ios-2009.sh > recordings/handoff/serve-race-$STAMP.sh
chmod +x recordings/handoff/serve-race-$STAMP.sh
echo; echo "plan: $(cat $R/plan.json | tr -d '\n ')"; echo "Brush options: $(cat $R/brush-args.txt)"
echo "MAC run:   tools/room-rebuild/mac-brush-run.sh $R/dataset $R/mac \$(cat $R/brush-args.txt)"
echo "LEGION:    rk runs recordings/handoff/serve-race-$STAMP.sh; the LEGION downloads dataset.zip and checks SHA256SUMS.txt"
