#!/bin/zsh
# Live view of the S24 app's log on this Mac, over USB or wireless adb (whichever device is connected), and each
# finished scan copied here automatically (read-only: nothing on the phone is changed, the app isn't started or
# installed). Light: one adb logcat stream.
#   tools/live-watch.sh            runs until Ctrl-C; log in recordings/s24/live/<time>.log, scans in recordings/s24/files/scans/
set -u
ADB=~/Library/Android/sdk/platform-tools/adb
# one phone can show up twice over Wi-Fi (its IP and its mDNS name): always talk to one of them
A() { $ADB -s "$SER" "$@"; }
P=com.hackathon.setscan
REPO=${0:A:h:h}
OUT=$REPO/recordings/s24/live; SCANS=$REPO/recordings/s24/files/scans
mkdir -p $OUT $SCANS
LOG=$OUT/$(date +%Y%m%d-%H%M%S).log
echo "live log: $LOG"

summary() {   # $1 = scan folder on this Mac
  local d=$1 f=$1/scan-log.txt
  echo "== $(basename $d): $(grep -o '"name": "[^"]*"' $d/meta.json | cut -d'"' -f4)"
  echo "   build $(A shell dumpsys package $P | grep -m1 -o 'versionCode=[0-9]*')"
  grep -m1 -o '"seconds": [0-9.]*' $d/perf-scan.summary.json 2>/dev/null | sed 's/"seconds"/   scanning s/'
  grep -m1 -o '"glFramesPerSecond": [0-9.]*' $d/perf-scan.summary.json 2>/dev/null | sed 's/"glFramesPerSecond"/   fps/'
  grep -E "tracking lost mid|back on track|one match is enough|restarting AR|pose jump [0-9]|no new depth|ERROR" $f | cut -c1-150 | sed 's/^/   /'
  grep -E "tracking health|ceiling:|room: |doorway on wall|doorways: [0-9]|screen video:" $f | cut -c1-150 | sed 's/^/   /'
  ls -la $d/screen.mp4 2>/dev/null | awk '{print "   screen.mp4 " int($5/1024) " KB"}'
}

pull_scan() {   # $1 = scan folder name on the phone
  local s=$1
  [ -n "$s" ] || return
  mkdir -p $SCANS/$s
  if A exec-out run-as $P tar cf - -C files/scans $s | tar xf - -C $SCANS; then
    echo "$(date +%H:%M:%S) copied $s"; summary $SCANS/$s
  else echo "$(date +%H:%M:%S) copying $s failed"; fi
}

while true; do
  SER=""
  while [ -z "$SER" ]; do SER=$($ADB devices | awk 'NR>1 && $2=="device" {print $1; exit}'); [ -z "$SER" ] && sleep 3; done
  echo "$(date +%H:%M:%S) phone connected: $SER" | tee -a $LOG
  last=""
  # the app's lines only, from now on; the stream ends when the link drops, then it waits for the phone again
  A logcat -v time -T 1 -s RoomScape:V 2>/dev/null | while IFS= read -r line; do
    print -r -- "$line" >> $LOG
    case $line in
      *"tracking lost"*|*"back on track"*|*"pose jump"*|*"restarting AR"*|*"no new depth"*|*ERROR*|*"Exception"*|*"live doorways: "[1-9]*|*"saved '"*|*"replay"*)
        print -r -- "${line:19}" ;;
    esac
    # remember which scan is being saved; its logs and screen video are in place once "perf log kept" is written
    if [[ $line == *"saved '"*"/files/scans/"* ]]; then last=${line##*/files/scans/}; last=${last%%[[:space:]]*}; fi
    if [[ $line == *"perf log kept with the scan"* ]]; then sleep 3; pull_scan "$last"; fi
  done
  echo "$(date +%H:%M:%S) link dropped, waiting for the phone" | tee -a $LOG
  sleep 2
done
