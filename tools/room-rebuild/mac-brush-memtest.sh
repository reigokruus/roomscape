#!/bin/zsh
# Where does the phone's +2.2 GB Brush spike come from? (13 Nov 2026) Runs the phone's recipe on the MAC for 600 steps
# (start + the refines at 200 and 400) under each setting, sampling the process's whole footprint (GPU included,
# /usr/bin/footprint) about 3 times a second, and keeps the memory categories at the peak.
#   tools/room-rebuild/mac-brush-memtest.sh [dataset]      (default: the 17:05 race dataset, the phone's own pictures)
# Needs the main-branch Brush the phone uses, built for macOS once:
#   cd ~/DEV/brush-ios-src/brush && PATH=$HOME/.cargo/bin:$PATH cargo build -p brush-cli --release
# Writes recordings/memtest-<time>/<run>/{samples.txt,peak.txt,brush.log} and summary.txt.
set -u
REPO=${0:A:h:h:h}; cd $REPO
DS=${1:-recordings/handoff/race-2026-11-12_17-05-53/dataset}
MAIN=$HOME/DEV/brush-ios-src/brush/target/release/brush-cli
REL=$REPO/tools/brush-mac/bin/brush-app-aarch64-apple-darwin/brush_app
OUT=recordings/memtest-$(date +%H%M%S); mkdir -p $OUT
[[ -f $DS/transforms.json ]] || { echo "!!! no dataset at $DS"; exit 1; }
LOCK=/tmp/roomscape-heavy.lock
until mkdir $LOCK 2>/dev/null; do echo "waiting for another heavy job…"; sleep 10; done
trap 'rmdir $LOCK 2>/dev/null' EXIT INT TERM
COMMON=(--max-splats 200000 --max-resolution 720 --sh-degree 1 --refine-every 200)

run() {  # name, env assignments…, then -- binary steps-flag
  local name=$1; shift
  local envs=(); while [[ $1 != -- ]]; do envs+=$1; shift; done; shift
  local bin=$1 stepsflag=$2
  [[ -x $bin ]] || { echo "skip $name: no $bin" | tee -a $OUT/summary.txt; return; }
  local d=$OUT/$name; mkdir -p $d
  echo "== $name: ${envs[*]} ${bin:t}"
  env ${envs[@]} $bin $DS $stepsflag 600 $COMMON --export-every 600 --export-path $d --export-name 'splat_{iter}.ply' > $d/brush.log 2>&1 &
  local pid=$! t0=$(perl -MTime::HiRes=time -e 'printf "%.2f", time') peak=0
  while kill -0 $pid 2>/dev/null; do
    local now=$(perl -MTime::HiRes=time -e 'printf "%.2f", time')
    local fp=$(/usr/bin/footprint -p $pid 2>/dev/null | awk '/Footprint:/ {for (i = 1; i < NF; i++) if ($i == "Footprint:") {v = $(i+1); u = $(i+2)}; if (u == "KB") v /= 1024; else if (u == "GB") v *= 1024; printf "%d", v; exit}')
    [[ -z $fp ]] && continue
    echo "$(printf '%.1f' $((now - t0))) $fp" >> $d/samples.txt
    if (( fp > peak )); then peak=$fp; /usr/bin/footprint -p $pid > $d/peak.txt 2>/dev/null; fi
  done
  wait $pid
  local secs=$(tail -1 $d/samples.txt | awk '{print $1}')
  local cats=$(awk '/Category/ {on=1; next} on && NF>=5 {print}' $d/peak.txt | sort -k1 -h -r | head -3 | awk '{printf "%s %s %s; ", $1, $2, $NF}')
  echo "$name: peak ${peak} MB, ${secs} s; at the peak: $cats" | tee -a $OUT/summary.txt
}

run v030-release -- $REL --total-steps
run main-exclusive -- $MAIN --total-train-iters
run main-direct BRUSH_DIRECT_POOL=1 -- $MAIN --total-train-iters
run main-exclusive-tune-min CUBECL_AUTOTUNE_LEVEL=minimal -- $MAIN --total-train-iters
run main-direct-tune-min BRUSH_DIRECT_POOL=1 CUBECL_AUTOTUNE_LEVEL=minimal -- $MAIN --total-train-iters
echo; cat $OUT/summary.txt
