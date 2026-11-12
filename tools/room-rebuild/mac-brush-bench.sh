#!/bin/zsh
# Brush v0.3.0 on this Mac (M3 Air, 8 GB) with the three-computer benchmark settings, timed:
#   tools/room-rebuild/mac-brush-bench.sh <handoff folder> A|B
# Uses <handoff>/bench/datasetA|B (tools/room-rebuild/bench_datasets.sh). Writes <handoff>/bench/mac-run<A|B>/:
# splat_<steps>.ply, eval_<steps>/ (held-out renders), <name>-light.splat (browser copy), result.json (wall-clock
# training time, steps/s, peak memory, thermal states seen, PSNR) and monitor.log (memory and heat every 10 s).
# Stops Brush if macOS memory gets critical (free under 5%): a valid result for run B on 8 GB.
# Brush: the official release in tools/brush-mac (git-ignored), or BRUSH=/path/to/brush_app.
set -u
H=${1:?handoff folder}; RUN=${2:?A or B}
REPO=${0:A:h:h:h}
BRUSH=${BRUSH:-$REPO/tools/brush-mac/bin/brush-app-aarch64-apple-darwin/brush_app}
DS=$H/bench/dataset$RUN; OUT=$H/bench/mac-run$RUN
[[ -f $DS/transforms.json ]] || { echo "!!! no $DS: run tools/room-rebuild/bench_datasets.sh $H first"; exit 1; }
[[ -x $BRUSH ]] || { echo "!!! Brush not found at $BRUSH"; exit 1; }
if [[ $RUN == A ]]; then STEPS=7000; EXTRA=(--max-splats 400000 --growth-stop-iter 3500)
else STEPS=30000; EXTRA=(); fi
rm -rf $OUT; mkdir -p $OUT

# Heat helper (ProcessInfo thermal state: 0 nominal, 1 fair, 2 serious, 3 critical), as tools/heavy-queue.sh makes it
THERM=/tmp/roomscape-thermal
[[ -x $THERM ]] || { echo 'import Foundation; print(ProcessInfo.processInfo.thermalState.rawValue)' > $THERM.swift && swiftc -O -o $THERM $THERM.swift 2>/dev/null; }

# One heavy job at a time (same lock as tools/heavy-queue.sh); simulators off
LOCK=/tmp/roomscape-heavy.lock
until mkdir $LOCK 2>/dev/null; do echo "waiting for another heavy job…"; sleep 10; done
trap 'rmdir $LOCK 2>/dev/null' EXIT INT TERM
xcrun simctl shutdown all 2>/dev/null

echo "Brush run $RUN: $STEPS steps on $(ls $DS/images | wc -l | tr -d ' ') pictures → $OUT"
START=$(date +%s)
/usr/bin/time -l $BRUSH $DS --total-steps $STEPS $EXTRA --eval-split-every 8 --eval-every $STEPS --eval-save-to-disk \
    --export-every $STEPS --export-path $OUT --export-name 'splat_{iter}.ply' > $OUT/brush.log 2> $OUT/time.txt &
PID=$!
# Watch: memory and heat every 10 s; stop Brush when memory is critical
STOPPED=""
while kill -0 $PID 2>/dev/null; do
  free=$(memory_pressure -Q 2>/dev/null | awk -F': ' '/free percentage/ {gsub("%","",$2); print $2}')
  bp=$(pgrep -P $PID | head -1); rss=$(ps -o rss= -p ${bp:-$PID} 2>/dev/null | tr -d ' ')
  echo "$(( $(date +%s) - START )) s  free ${free}%  brushRSS $(( ${rss:-0} / 1024 )) MB  thermal $($THERM 2>/dev/null)  $(pmset -g therm 2>/dev/null | awk -F'= ' '/CPU_Speed_Limit/ {print "cpuLimit "$2}')" >> $OUT/monitor.log
  if [[ -n $free && $free -lt 5 ]]; then
    STOPPED="memory critical (free ${free}%) after $(( $(date +%s) - START )) s"; echo "!!! $STOPPED: stopping Brush"
    pkill -P $PID; kill $PID 2>/dev/null; break
  fi
  sleep 10
done
wait $PID; CODE=$?
SECS=$(( $(date +%s) - START ))
PEAK_RSS=$(awk '/maximum resident set size/ {print int($1/1048576)}' $OUT/time.txt)
PEAK_FOOT=$(awk '/peak memory footprint/ {print int($1/1048576)}' $OUT/time.txt)
THERMS=$(awk '{for(i=1;i<=NF;i++) if($i=="thermal") print $(i+1)}' $OUT/monitor.log | sort -u | tr '\n' ' ')
MINFREE=$(awk '{for(i=1;i<=NF;i++) if($i=="free") {gsub("%","",$(i+1)); print $(i+1)}}' $OUT/monitor.log | sort -n | head -1)
CPULIM=$(awk '{for(i=1;i<=NF;i++) if($i=="cpuLimit") print $(i+1)}' $OUT/monitor.log | sort -n | head -1)
PLY=$(ls $OUT/splat_*.ply 2>/dev/null | tail -1)
SCORE='{}'
if [[ -n $PLY ]]; then
  ${PYTHON:-python3} $REPO/tools/room-rebuild/score_eval.py $DS $OUT --json $OUT/score.json && SCORE=$(cat $OUT/score.json)
  ${PYTHON:-python3} $REPO/tools/room-viewer/ply2splat.py $PLY $OUT/$(basename $H)-mac$RUN-light.splat --max 1500000 >/dev/null
fi
python3 - $OUT/result.json <<EOF
import json, sys
s = $SECS; steps = $STEPS
r = dict(computer="MacBook Air M3 8 GB", run="$RUN", steps=steps, exit=$CODE, stopped="$STOPPED" or None,
         seconds=s, minutes=round(s / 60, 1), steps_per_s=round(steps / s, 2) if not "$STOPPED" and s else None,
         peak_rss_mb=int("${PEAK_RSS:-0}"), peak_footprint_mb=int("${PEAK_FOOT:-0}"), min_free_pct="$MINFREE",
         thermal_states_seen="$THERMS".split(), min_cpu_speed_limit="$CPULIM" or None, ply="$PLY" or None,
         score=json.loads('''$SCORE'''))
json.dump(r, open(sys.argv[1], 'w'), indent=1); print(json.dumps(r, indent=1))
EOF
