#!/bin/zsh
# Brush v0.3.0 on this MAC with given options, timed (wall time, steps/s, peak memory, heat), one heavy job at a time:
#   tools/room-rebuild/mac-brush-run.sh <dataset dir> <out dir> <Brush options…>
#   e.g. the phone race: tools/room-rebuild/mac-brush-run.sh <race>/dataset <race>/mac $(cat <race>/brush-args.txt)
# Writes <out>/result.json, monitor.log, brush.log, time.txt and splat_<steps>.ply. Stops Brush if memory gets critical.
set -u
DS=${1:?dataset}; OUT=${2:?out dir}; shift 2
REPO=${0:A:h:h:h}
BRUSH=${BRUSH:-$REPO/tools/brush-mac/bin/brush-app-aarch64-apple-darwin/brush_app}
STEPS=$(echo "$@" | sed -n 's/.*--total-steps \([0-9]*\).*/\1/p'); STEPS=${STEPS:-0}
[[ -f $DS/transforms.json && -x $BRUSH ]] || { echo "!!! need $DS/transforms.json and Brush at $BRUSH"; exit 1; }
rm -rf $OUT; mkdir -p $OUT
THERM=/tmp/roomscape-thermal
[[ -x $THERM ]] || { echo 'import Foundation; print(ProcessInfo.processInfo.thermalState.rawValue)' > $THERM.swift && swiftc -O -o $THERM $THERM.swift 2>/dev/null; }
LOCK=/tmp/roomscape-heavy.lock
until mkdir $LOCK 2>/dev/null; do echo "waiting for another heavy job…"; sleep 10; done
trap 'rmdir $LOCK 2>/dev/null' EXIT INT TERM
xcrun simctl shutdown all 2>/dev/null
echo "Brush $* on $(ls $DS/images | wc -l | tr -d ' ') pictures → $OUT"
START=$(date +%s)
/usr/bin/time -l $BRUSH $DS "$@" --export-every $STEPS --export-path $OUT --export-name 'splat_{iter}.ply' > $OUT/brush.log 2> $OUT/time.txt &
PID=$!; STOPPED=""
while kill -0 $PID 2>/dev/null; do
  free=$(memory_pressure -Q 2>/dev/null | awk -F': ' '/free percentage/ {gsub("%","",$2); print $2}')
  echo "$(( $(date +%s) - START )) s  free ${free}%  thermal $($THERM 2>/dev/null)" >> $OUT/monitor.log
  if [[ -n $free && $free -lt 5 ]]; then STOPPED="memory critical"; pkill -P $PID; kill $PID 2>/dev/null; break; fi
  sleep 5
done
wait $PID; CODE=$?; SECS=$(( $(date +%s) - START ))
FOOT=$(awk '/peak memory footprint/ {print int($1/1048576)}' $OUT/time.txt)
THERMS=$(awk '{print $NF}' $OUT/monitor.log | sort -u | tr '\n' ' ')
python3 -c "
import json; s=$SECS; st=$STEPS
r=dict(computer='MAC (M3 Air 8 GB)', exit=$CODE, stopped='$STOPPED' or None, seconds=s, steps=st,
       steps_per_s=round(st/s,1) if s and st else None, peak_footprint_mb=int('${FOOT:-0}'), thermal_seen='$THERMS'.split(), options='$*')
json.dump(r, open('$OUT/result.json','w'), indent=1); print(json.dumps(r, indent=1))"
