#!/bin/zsh
# Replays a recorded S24 session through the whole ARCore pipeline on the phone (only when rk says "phone free"):
#   tools/replay-s24.sh list                     recordings on the phone
#   tools/replay-s24.sh pull                     copy them to recordings/ on this Mac (never committed)
#   tools/replay-s24.sh play <name.mp4> [secs]   open the app replaying it; start a scan on the phone (New scan),
#                                                a screen video is saved to recordings/replays/
#   tools/replay-s24.sh rebuild <scan_id>        replay that scan's own recording.mp4 (read in place, never changed)
#                                                as a NEW scan named "Replay <scan_id>"; on 263160917+ it saves
#                                                rebuild-frames/ (pictures + poses + lens + raw depth). Ends by itself.
set -e
A=~/Library/Android/sdk/platform-tools/adb
DIR=/sdcard/Android/data/com.hackathon.setscan/files/recordings
REPO=${0:A:h:h}
case $1 in
list) $A shell ls -l $DIR ;;
pull) mkdir -p $REPO/recordings/s24 && $A pull $DIR/. $REPO/recordings/s24/ ;;
play)
  $A shell am force-stop com.hackathon.setscan
  $A shell am start -n com.hackathon.setscan/.MainActivity --es playback $DIR/$2 >/dev/null
  mkdir -p $REPO/recordings/replays
  out=replay-$(date +%H%M%S).mp4
  $A shell screenrecord --time-limit ${3:-60} /sdcard/$out
  $A pull /sdcard/$out $REPO/recordings/replays/$out >/dev/null && $A shell rm /sdcard/$out
  echo "Screen video: recordings/replays/$out"
  ;;
rebuild)
  [ -n "$2" ] || { echo "usage: replay-s24.sh rebuild scan_<id>"; exit 1; }
  $A shell run-as com.hackathon.setscan ls files/scans/$2/recording.mp4 >/dev/null || { echo "no recording.mp4 in $2"; exit 1; }
  $A shell am force-stop com.hackathon.setscan
  $A shell am start -n com.hackathon.setscan/.MainActivity --es playback /data/user/0/com.hackathon.setscan/files/scans/$2/recording.mp4 --es replayName "Replay $2" >/dev/null
  echo "Replaying $2 as a new scan (the original is not touched). Leave the phone still on the table until it shows the result."
  ;;
esac
