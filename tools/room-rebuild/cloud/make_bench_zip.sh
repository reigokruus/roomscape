#!/bin/zsh
# On the MAC: the zip for a cloud GPU run (runpod_room.sh): datasetA + datasetB of a handoff folder and the two upload
# tools. Stays on the MAC; sending it is rk's own step (README.md here).
#   tools/room-rebuild/cloud/make_bench_zip.sh recordings/handoff/<name>
set -e
H=${1:?handoff folder}; REPO=${0:A:h:h:h:h}
$REPO/tools/room-rebuild/bench_datasets.sh $H AB
OUT=${H:A}/bench/cloud-bench.zip; rm -f $OUT
STAGE=$(mktemp -d); mkdir -p $STAGE/tools/room-rebuild $STAGE/tools/room-viewer
cp $REPO/tools/room-rebuild/upload_room.py $STAGE/tools/room-rebuild/; cp $REPO/tools/room-viewer/ply2splat.py $STAGE/tools/room-viewer/
ln -s ${H:A}/bench/datasetA $STAGE/datasetA; ln -s ${H:A}/bench/datasetB $STAGE/datasetB
(cd $STAGE && zip -q -0 -r $OUT datasetA datasetB tools)   # stored: the pictures are JPEGs already
rm -rf $STAGE
echo "$OUT: $(du -h $OUT | cut -f1)"; echo "SHA-256: $(shasum -a 256 $OUT | cut -d' ' -f1)"
