#!/usr/bin/env bash
# Brush on a rented cloud GPU (RunPod pod, Ubuntu + NVIDIA, e.g. an RTX 4090), the same runs as the LEGION and the MAC
# so the times compare directly. Paste into the pod's web terminal, or copy it there and run:
#
#   bash runpod_room.sh <bench zip: local path or https URL> <its SHA-256>
#
# The zip (made on the MAC, tools/room-rebuild/cloud/README.md) holds datasetA/ and datasetB/ (Nerfstudio layout) and
# tools/ (ply2splat.py, upload_room.py). Runs:
#   A: --total-steps 7000 --max-splats 400000 --growth-stop-iter 3500 …   (the 2-minute LEGION comparison)
#   B: --total-steps 30000 …                                                  (the full version)
# Writes ~/room-run/times.txt (GPU, VRAM, seconds, steps/s, peak VRAM), runA/ and runB/ (splat_<steps>.ply, eval
# renders). Optional: with ROOMSCAPE_UPLOAD_TOKEN and ROOM_ID set in this terminal (typed by you, never in a file or
# chat), uploads light copies of run B to https://roomscape.eu/r/$ROOM_ID.
# Private: the dataset shows a real home. Delete the pod (and its volume) when the results are downloaded.
set -euo pipefail
SRC=${1:?bench zip path or URL}; SUM=${2:?SHA-256 of the zip}
BRUSH_URL=https://github.com/ArthurBrussee/brush/releases/download/v0.3.0/brush-app-x86_64-unknown-linux-gnu.tar.xz
BRUSH_SHA=4f0f9a8785d1951c62df26aae247c02c5bba32b00f40b06df4e1c9b867399e20
W=~/room-run; mkdir -p $W; cd $W
log() { echo "$(date -u +%H:%M:%S) $*" | tee -a $W/times.txt; }

# 1. tools: a virtual screen for Brush (it wants a window system even on the command line), the Vulkan loader
log "setup"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq && apt-get install -y -qq xvfb libvulkan1 vulkan-tools xz-utils curl unzip python3-pip >/dev/null
pip install -q numpy
# NVIDIA's Vulkan driver: the container gets the driver libraries; make sure the loader can find them
if [ ! -e /usr/share/vulkan/icd.d/nvidia_icd.json ] && [ ! -e /etc/vulkan/icd.d/nvidia_icd.json ]; then
  mkdir -p /etc/vulkan/icd.d
  echo '{"file_format_version":"1.0.0","ICD":{"library_path":"libGLX_nvidia.so.0","api_version":"1.3"}}' > /etc/vulkan/icd.d/nvidia_icd.json
fi
if ! xvfb-run -a vulkaninfo --summary 2>/dev/null | grep -qi nvidia; then
  echo "!!! Vulkan doesn't see the NVIDIA GPU. The pod needs graphics driver capabilities:"
  echo "    redeploy with the environment variable NVIDIA_DRIVER_CAPABILITIES=all (pod template → Environment)."
  exit 1
fi
GPU=$(nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader)
log "GPU: $GPU"

# 2. Brush v0.3.0, the official Linux release, checked against its published SHA-256
curl -sSL -o brush.tar.xz $BRUSH_URL
echo "$BRUSH_SHA  brush.tar.xz" | sha256sum -c - >/dev/null || { echo "!!! Brush download doesn't match its SHA-256"; exit 1; }
mkdir -p brush && tar xf brush.tar.xz -C brush --strip-components=1
BRUSH=$W/brush/brush_app; chmod +x $BRUSH
log "Brush v0.3.0 ready (SHA-256 ok)"

# 3. the dataset zip, checked
case "$SRC" in http*) curl -sSL -o bench.zip "$SRC" ;; *) cp "$SRC" bench.zip ;; esac
echo "$SUM  bench.zip" | sha256sum -c - >/dev/null || { echo "!!! dataset zip doesn't match the SHA-256 given"; exit 1; }
rm -rf bench && mkdir bench && unzip -q bench.zip -d bench
log "dataset: A $(ls bench/datasetA/images | wc -l) pictures, B $(ls bench/datasetB/images | wc -l) pictures"

# 4. the runs, timed, with peak VRAM
run() {  # name steps args…
  local name=$1 steps=$2; shift 2
  rm -rf $W/run$name; mkdir -p $W/run$name
  nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits -lms 1000 > $W/run$name/vram.log &
  local mon=$!; local t0=$(date +%s.%N)
  xvfb-run -a $BRUSH $W/bench/dataset$name --total-steps $steps "$@" --eval-split-every 8 --eval-every $steps \
      --eval-save-to-disk --export-every $steps --export-path $W/run$name --export-name 'splat_{iter}.ply' \
      > $W/run$name/brush.log 2>&1 || log "run $name: Brush exited with an error (see run$name/brush.log)"
  local t1=$(date +%s.%N); kill $mon 2>/dev/null || true
  local secs=$(python3 -c "print(round($t1 - $t0, 1))")
  local peak=$(sort -n $W/run$name/vram.log | tail -1)
  log "run $name: $steps steps in $secs s ($(python3 -c "print(round($steps / ($t1 - $t0), 1))") steps/s, whole process), peak VRAM $peak MiB, $(ls $W/run$name/*.ply 2>/dev/null | wc -l) splat file(s)"
}
run A 7000 --max-splats 400000 --growth-stop-iter 3500
run B 30000

# 5. optional: light copies of run B to the room's share link
if [ -n "${ROOMSCAPE_UPLOAD_TOKEN:-}" ] && [ -n "${ROOM_ID:-}" ] && [ -f $W/runB/splat_30000.ply ]; then
  python3 bench/tools/room-rebuild/upload_room.py --id "$ROOM_ID" --ply $W/runB/splat_30000.ply && log "uploaded to https://roomscape.eu/r/$ROOM_ID"
fi
log "done: download ~/room-run/times.txt, runA/, runB/ (e.g. runpodctl send ~/room-run/runB/splat_30000.ply)"
