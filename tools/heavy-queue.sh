#!/bin/zsh
# Runs one heavy job at a time on this 8 GB Mac (builds, simulator, emulator, Blender, benchmarks, downloads):
#   tools/heavy-queue.sh <command> [args…]
# Waits for the lock and for memory to be calm, runs the job at low priority, then shuts simulators, emulators,
# Gradle and Blender down. Activity is logged to tools/heavy-queue.log.
LOCK=/tmp/roomscape-heavy.lock
LOG=${0:A:h}/heavy-queue.log
log() { echo "$(date '+%F %T') $*" >> $LOG; }

until mkdir $LOCK 2>/dev/null; do sleep 5; done
trap 'rmdir $LOCK 2>/dev/null' EXIT INT TERM
log "queued job starting: $*"

# Memory: wait while free memory is low (memory_pressure's "free percentage" under 20%).
for i in {1..60}; do
  free=$(memory_pressure -Q 2>/dev/null | awk -F': ' '/free percentage/ {gsub("%","",$2); print $2}')
  [[ -z $free || $free -ge 20 ]] && break
  [[ $i == 1 ]] && log "  waiting: free memory ${free}%"
  sleep 10
done

start=$(date +%s)
nice -n 10 "$@"
rc=$?
log "  finished ($rc) in $(( $(date +%s) - start )) s: $*"

# Shut down what heavy jobs leave behind.
xcrun simctl shutdown all >/dev/null 2>&1
osascript -e 'quit app "Simulator"' >/dev/null 2>&1
~/Library/Android/sdk/platform-tools/adb emu kill >/dev/null 2>&1
(cd ${0:A:h:h}/android && ./gradlew --stop -q >/dev/null 2>&1)
pkill -x Blender >/dev/null 2>&1
exit $rc
