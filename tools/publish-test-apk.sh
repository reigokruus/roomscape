#!/bin/zsh
# Publishes the teammate test APK as a public GitHub pre-release on reigokruus/roomscape (only the APK: no code is
# pushed, the tag points at the current remote main). Run by rk:  ~/DEV/shootlocal/tools/publish-test-apk.sh
set -e
# ~/DEV/shootlocal/tools/publish-test-apk.sh [build] [date]   (default: 263142312 2026-11-10)
V=${1:-263142312}; DAY=${2:-2026-11-10}
APK=~/Downloads/RoomScape-android-$V-$DAY.apk
[ -f $APK ] || { echo "no $APK"; exit 1; }
if [ "$V" = 263142312 ]; then WANT=519a8be89203ff5d678dd1cef9284d34e90ce4f308a38e1275e1e34f2a94bd30; else WANT=$(cat $APK.sha256 2>/dev/null); fi
[ -n "$WANT" ] || { echo "no recorded hash ($APK.sha256)"; exit 1; }
[ "$(shasum -a 256 $APK | cut -d' ' -f1)" = "$WANT" ] || { echo "APK hash differs, not uploading"; exit 1; }
TOK=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | sed -n 's/^password=//p')
API=https://api.github.com/repos/reigokruus/roomscape
NOTES="RoomScape for Android, test build $V. Test build, tried on a Galaxy S24 and a Galaxy S23 Ultra so far.

Install: open this page on the phone, download RoomScape-android-$V.apk, open it and allow installing from this source once (on Samsung, turn off Auto Blocker for the install). The app needs Google Play Services for AR (ARCore); the Play Store installs it if missing.

Scan: move slowly, within about 3 m of the walls, and sweep each wall from the floor to above head height. Tap Done.
Result: the size and floor area in m². With more than one room, tap a room for its own area. Drag the amber wall lines to correct a wall; Reset walls undoes it. Share sends the scan as a .rmsc file."
BODY=$(python3 -c 'import json,sys;v,d,n=sys.argv[1:4];print(json.dumps({"tag_name":"android-test-"+v,"target_commitish":"main","name":"RoomScape Android test build %s (%s)"%(v,d),"body":n,"prerelease":True,"make_latest":"false"}))' "$V" "$DAY" "$NOTES")
# the release may exist already (a first run created it, then stopped): use it
EXIST=$(curl -s -H "Authorization: token $TOK" $API/releases/tags/android-test-$V)
ID=$(print -r -- "$EXIST" | python3 -c 'import json,sys;print(json.loads(sys.stdin.read(), strict=False).get("id") or "")')
[ -n "$DRY_RUN" ] && { echo "dry run: hash ok, release ${ID:-not there yet}; stopping before anything is created"; exit 0; }
if [ -z "$ID" ]; then
  echo "creating the release…"
  REL=$(curl -sS --fail-with-body -X POST -H "Authorization: token $TOK" -H "Accept: application/vnd.github+json" $API/releases -d "$BODY")
  ID=$(print -r -- "$REL" | python3 -c 'import json,sys;print(json.loads(sys.stdin.read(), strict=False)["id"])')
else echo "the release is there already (id $ID)"; fi
# a half-finished upload from an earlier try (GitHub keeps it as "starter"): remove it first
OLD=$(curl -s -H "Authorization: token $TOK" $API/releases/$ID/assets | python3 -c 'import json,sys;print(" ".join(str(a["id"]) for a in json.loads(sys.stdin.read(),strict=False) if a["name"].endswith(".apk")))')
for A in ${=OLD}; do echo "removing an earlier unfinished upload"; curl -sS -X DELETE -H "Authorization: token $TOK" $API/releases/assets/$A; done
# the upload can time out on a slow line (GitHub answers 408): up to 3 tries
for TRY in 1 2 3; do
echo "uploading the APK (69 MB, try $TRY)…"; curl -sS --fail-with-body --max-time 900 -X POST -H "Authorization: token $TOK" -H "Content-Type: application/vnd.android.package-archive" --data-binary @$APK \
  "https://uploads.github.com/repos/reigokruus/roomscape/releases/$ID/assets?name=RoomScape-android-$V.apk" >/dev/null && break
  echo "the upload didn't finish; trying again"; sleep 5
done
SIZE=$(stat -f %z $APK)
STATE=$(curl -s -H "Authorization: token $TOK" $API/releases/$ID/assets | python3 -c 'import json,sys;s=int(sys.argv[1]);a=[x for x in json.loads(sys.stdin.read(),strict=False) if x["name"].endswith(".apk")];print("ok" if a and a[0]["state"]=="uploaded" and a[0]["size"]==s else "not finished")' $SIZE)
[ "$STATE" = ok ] || { echo "the APK isn't fully on GitHub yet: run this again"; exit 1; }
URL=https://github.com/reigokruus/roomscape/releases/download/android-test-$V/RoomScape-android-$V.apk
echo "release page: https://github.com/reigokruus/roomscape/releases/tag/android-test-$V"
echo "direct download: $URL"
GOT=$(curl -sL $URL | shasum -a 256 | cut -d' ' -f1)
[ "$GOT" = "$WANT" ] && echo "checked: downloads without login, hash matches" || echo "WARNING: the downloaded file's hash differs ($GOT)"
