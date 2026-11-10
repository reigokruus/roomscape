#!/bin/zsh
# Installs the current debug APK on the S24 (for rk to run). USB if plugged in, else Wi-Fi; one phone can show up
# twice over Wi-Fi, so one entry is picked. Never uninstalls, never downgrades, never starts the app.
#   ~/DEV/shootlocal/tools/install-s24.sh
set -e
ADB=~/Library/Android/sdk/platform-tools/adb
AAPT=$(ls -d ~/Library/Android/sdk/build-tools/*/aapt | tail -1)
APK=${0:A:h:h}/android/app/build/outputs/apk/debug/app-debug.apk
P=com.hackathon.setscan
[ -f $APK ] || { echo "no APK at $APK"; exit 1; }
# S24 entries (model SM_S921B), USB first (serials without ':' or '._adb-tls'), emulators ignored
devs=$($ADB devices -l | awk 'NR>1 && $2=="device" && /model:SM_S921/ {print $1}')
dev=$(echo "$devs" | grep -v -e ':' -e '_adb-tls' | head -1)
[ -z "$dev" ] && dev=$(echo "$devs" | grep ':' | head -1)
[ -z "$dev" ] && dev=$(echo "$devs" | head -1)
[ -z "$dev" ] && { echo "S24 not found (plug it in or connect Wireless debugging)"; exit 1; }
new=$($AAPT dump badging $APK | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
old=$($ADB -s $dev shell dumpsys package $P | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
echo "phone: $dev"
echo "APK version $new, installed ${old:-none}"
if [ -n "$old" ] && [ "$new" -lt "$old" ]; then echo "the APK is older than the installed app: not installing"; exit 1; fi
$ADB -s $dev install -r $APK
echo "installed: $($ADB -s $dev shell dumpsys package $P | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)"
