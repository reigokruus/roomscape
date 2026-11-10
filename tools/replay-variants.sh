#!/bin/zsh
# Replays two saved S24 scans through the app with each depth-fusion test variant (rk 10 Nov "replay ok").
# Each replay is a NEW scan named "Replay <scan> v<variant> #<n>"; existing scans are only read (their recording.mp4 is
# copied to files/replay-in/, outside recordings/, so the app never adopts it into another scan).
#   tools/replay-variants.sh            all runs (about 10-15 minutes; leave the phone alone, screen on)
set -e
A=~/Library/Android/sdk/platform-tools/adb
P=com.hackathon.setscan
RUNS=(
  "scan_1794312510786 1409 0 1"
  "scan_1794312510786 1409 0 2"
  "scan_1794312510786 1409 1 1"
  "scan_1794312510786 1409 2 1"
  "scan_1794310864338 1341 0 1"
  "scan_1794310864338 1341 1 1"
  "scan_1794310864338 1341 2 1"
)
$A shell run-as $P mkdir -p files/replay-in
for r in $RUNS; do
  set -- ${=r}; scan=$1; tag=$2; var=$3; n=$4
  $A shell run-as $P sh -c "'[ -f files/replay-in/r$tag.mp4 ] || cp files/scans/$scan/recording.mp4 files/replay-in/r$tag.mp4'"
  before=$($A shell run-as $P ls files/scans | tr -d '\r' | sort)
  name="Replay $tag v$var #$n"
  echo "$(date +%H:%M:%S) $name"
  $A shell am force-stop $P
  $A shell am start -n $P/.MainActivity --es playback /data/user/0/$P/files/replay-in/r$tag.mp4 --ei fuseVariant $var --es replayName "'$name'" >/dev/null
  # wait for the new scan's log (written about a minute after Done), at most 10 minutes
  for i in $(seq 1 120); do
    sleep 5
    new=$(comm -13 <(echo "$before") <($A shell run-as $P ls files/scans | tr -d '\r' | sort) | head -1)
    [ -n "$new" ] && $A shell run-as $P ls files/scans/$new | grep -q scan-log.txt && break
  done
  echo "  -> $new"
done
$A shell am force-stop $P
$A shell run-as $P rm -r files/replay-in
echo "done"
