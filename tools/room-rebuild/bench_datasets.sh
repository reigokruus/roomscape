#!/bin/zsh
# The two datasets of the three-computer Brush benchmark (Mac M3 8 GB, Legion 4070, 5060 PC), made once on the Mac
# from an iPhone handoff folder (tools/pull-latest-ios-scan.sh) so every computer trains on the same pictures:
#   tools/room-rebuild/bench_datasets.sh recordings/handoff/<name>        [A|B|AB, default AB]
# Writes <handoff>/bench/datasetA (300 frames at 960x720, 200k start points: below Brush's --max-splats 400k cap,
# which only holds if the start is under it) and datasetB (up to 1,000 frames at 1920x1440, 1 M start points), plus
# bench/SHA256SUMS.txt. Reads the scan only. Runs through tools/heavy-queue.sh (one heavy job at a time).
set -e
H=${1:?handoff folder}; WHICH=${2:-AB}
REPO=${0:A:h:h:h}
CAP=$(ls -d $H/scan-*/*.capture | head -1)
[[ -d $CAP/recording ]] || { echo "!!! no recording in $H"; exit 1; }
OUT=$H/bench; mkdir -p $OUT
PY=${PYTHON:-python3}   # needs numpy and opencv-python (PYTHON=/path/to/venv/bin/python)
mk() {  # name frames scale points
  [[ -f $OUT/$1/transforms.json ]] && { echo "$1 already made"; return; }
  rm -rf $OUT/$1.partial
  $REPO/tools/heavy-queue.sh $PY $REPO/tools/room-rebuild/ios_to_nerfstudio.py $CAP $OUT/$1.partial \
      --max-frames $2 --scale $3 --max-points $4
  mv $OUT/$1.partial $OUT/$1
  echo "$1: $(ls $OUT/$1/images | wc -l | tr -d ' ') pictures, $(du -sh $OUT/$1 | cut -f1)"
}
[[ $WHICH == *A* ]] && mk datasetA 300 0.5 200000
[[ $WHICH == *B* ]] && mk datasetB 1000 1.0 1000000
(cd $OUT && find dataset* -type f ! -name '.*' | sort | xargs shasum -a 256 > SHA256SUMS.txt)
echo "hashes: $OUT/SHA256SUMS.txt ($(wc -l < $OUT/SHA256SUMS.txt | tr -d ' ') files)"
