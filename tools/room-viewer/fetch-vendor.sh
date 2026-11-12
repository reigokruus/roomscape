#!/bin/zsh
# Downloads the three.js files the viewer uses (MIT; kept out of git) into vendor/.
cd ${0:A:h}; B=https://cdn.jsdelivr.net/npm/three@0.160.0; mkdir -p vendor/jsm/controls vendor/jsm/loaders vendor/jsm/utils
for f in build/three.module.js:three.module.js examples/jsm/controls/OrbitControls.js:jsm/controls/OrbitControls.js examples/jsm/loaders/GLTFLoader.js:jsm/loaders/GLTFLoader.js examples/jsm/utils/BufferGeometryUtils.js:jsm/utils/BufferGeometryUtils.js; do curl -sfL $B/${f%%:*} -o vendor/${f#*:}; done
