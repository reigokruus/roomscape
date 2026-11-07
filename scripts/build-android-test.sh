#!/usr/bin/env bash
# Build the real ARCore Android app; no publishing, device install or API key is needed.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
: "${BUILD_NUMBER:?Set BUILD_NUMBER to the desired positive Android versionCode, e.g. BUILD_NUMBER=67}"
[[ "$BUILD_NUMBER" =~ ^[1-9][0-9]*$ ]] && (( BUILD_NUMBER <= 2100000000 )) || { echo 'BUILD_NUMBER must be a positive integer <= 2100000000' >&2; exit 2; }
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK containing platform 35 and build-tools 35.0.0}"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
command -v java >/dev/null || { echo 'Install JDK 17 and set JAVA_HOME.' >&2; exit 2; }
java -version
[[ -f "$ANDROID_HOME/platforms/android-35/android.jar" ]] || { echo 'Install SDK platforms;android-35.' >&2; exit 2; }
TOOLS="$ANDROID_HOME/build-tools/35.0.0"
[[ -x "$TOOLS/apksigner" && -x "$TOOLS/aapt" ]] || { echo 'Install SDK build-tools;35.0.0.' >&2; exit 2; }
OUT="${OUT_DIR:-$ROOT/out/native}"
mkdir -p "$OUT"
cd "$ROOT/android"
./gradlew assembleDebug testDebugUnitTest lintDebug -PbuildNumber="$BUILD_NUMBER" --no-daemon --max-workers=2 --console=plain 2>&1 | tee "$OUT/gradle.log"
APK="$OUT/CineX-android-local-0.1.$BUILD_NUMBER.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$APK"
"$TOOLS/apksigner" verify --verbose --print-certs "$APK" > "$OUT/apk-signature.txt"
"$TOOLS/aapt" dump badging "$APK" > "$OUT/apk-badging.txt"
python3 - "$APK" "$ROOT/android/app/build/test-results/testDebugUnitTest" "$ROOT" <<'PY'
import hashlib,json,pathlib,subprocess,sys,zipfile,xml.etree.ElementTree as ET
p = pathlib.Path(sys.argv[1])
reports = list(pathlib.Path(sys.argv[2]).glob('TEST-*.xml'))
assert reports, 'No Android unit-test results were produced'
tests = failures = errors = skipped = 0
for report in reports:
    suite = ET.parse(report).getroot()
    tests += int(suite.get('tests','0')); failures += int(suite.get('failures','0'))
    errors += int(suite.get('errors','0')); skipped += int(suite.get('skipped','0'))
assert tests > 0 and failures == 0 and errors == 0 and skipped == 0, 'Android unit tests did not all pass'
print(f'Android JVM behavioral tests: {tests} passed; no physical AR session was mocked or exercised')
with zipfile.ZipFile(p) as z:
    assert z.testzip() is None, 'APK archive is corrupt'
    for name in ['AndroidManifest.xml','classes.dex','assets/depth_anything_v2_small.tflite']:
        assert name in z.namelist(), f'Missing native app component: {name}'
    assert z.getinfo('assets/depth_anything_v2_small.tflite').compress_type == zipfile.ZIP_STORED, 'Depth model must be uncompressed for memory-mapping'
    assert any(n.startswith('lib/arm64-v8a/') for n in z.namelist()), 'Missing native ARM64 libraries'
    catalog = json.loads(z.read('assets/equipment-catalog.json'))
    assert catalog['schemaVersion'] == 1 and 1 <= len(catalog['items']) <= 200
    assert len({row['id'] for row in catalog['items']}) == len(catalog['items'])
    dex_files = [z.read(n) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex')]
    for name in ('ItemCatalog','CatalogStore','RoomEdits','RoomEditStore','NativePlanningUi'):
        assert any(('Lcom/hackathon/setscan/'+name+';').encode() in dex for dex in dex_files), 'Missing packaged native feature: '+name
    print(f'Bundled catalog: {len(catalog["items"])} source records; native catalog/removal classes found in DEX')
digest = hashlib.sha256(p.read_bytes()).hexdigest()
p.with_suffix('.apk.sha256').write_text(digest+'  '+p.name+'\n')
p.with_suffix('.verification.json').write_text(json.dumps({'apk':p.name,'sourceCommit':subprocess.check_output(['git','-C',sys.argv[3],'rev-parse','HEAD'],text=True).strip(),'sourceDirty':bool(subprocess.check_output(['git','-C',sys.argv[3],'status','--porcelain'],text=True).strip()),'catalogItems':len(catalog['items']),'bytes':p.stat().st_size,'sha256':digest,'androidJvmTests':tests,'failures':failures,'errors':errors,'physicalDeviceTested':False},indent=2)+'\n')
print('Native APK verified:',p)
print('No physical-device run or room-scanning validation has been performed by this script.')
PY
printf '\nSource commit: '; git -C "$ROOT" rev-parse HEAD
printf 'Artifacts and build evidence: %s\n' "$OUT"
