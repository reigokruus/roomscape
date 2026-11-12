#!/bin/zsh
SDK=~/Library/Android/sdk; ADB=$SDK/platform-tools/adb
$SDK/emulator/emulator -avd Medium_Phone_API_37.0 -no-window -no-audio -no-snapshot -memory 2048 -gpu swiftshader_indirect >/tmp/emu.log 2>&1 &
for i in $(seq 1 90); do sleep 2; [ "$($ADB -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; done
echo "boot: $($ADB -s emulator-5554 shell getprop sys.boot_completed)"
$ADB -s emulator-5554 install -r ~/DEV/shootlocal/android/app/build/outputs/apk/debug/app-debug.apk | tail -1
for k in 1 2; do
  $ADB -s emulator-5554 shell am force-stop com.hackathon.setscan
  $ADB -s emulator-5554 logcat -c
  $ADB -s emulator-5554 shell am start -n com.hackathon.setscan/.MainActivity >/dev/null; sleep 12
  $ADB -s emulator-5554 logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (java|Process)" | head -5
  $ADB -s emulator-5554 shell pidof com.hackathon.setscan && echo "running ok"
done
