#!/bin/zsh
# Local room viewer (rk 12 Nov): serves this repo folder on 127.0.0.1 only (not reachable from other machines) and opens
# the viewer. The rooms are read from recordings/rebuild/ on this Mac; nothing is uploaded.
#   tools/room-viewer/serve.sh          then http://127.0.0.1:8765/tools/room-viewer/
PORT=${PORT:-8765}; ROOT=${0:A:h:h:h}
if ! curl -s -o /dev/null http://127.0.0.1:$PORT/tools/room-viewer/; then
  nohup python3 -m http.server $PORT --bind 127.0.0.1 --directory $ROOT > /tmp/room-viewer.log 2>&1 &
  sleep 1
fi
open http://127.0.0.1:$PORT/tools/room-viewer/
echo "Viewer: http://127.0.0.1:$PORT/tools/room-viewer/"
