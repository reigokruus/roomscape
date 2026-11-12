# A rented cloud GPU for a room (RunPod)

Same Brush runs as the LEGION and the MAC (run A 7,000 steps, run B 30,000), on a pod with a big GPU, billed per
second. Private data: the dataset shows a real home. Nothing leaves the MAC until rk sends it.

1. MAC: `tools/room-rebuild/cloud/make_bench_zip.sh recordings/handoff/<name>`: makes datasetA/B if needed and
   `<handoff>/bench/cloud-bench.zip` (A 27 MB + B about 250 MB for a 1-minute scan), prints its SHA-256.
2. RunPod: a GPU pod (e.g. RTX 4090) from an Ubuntu PyTorch/CUDA template, with the environment variable
   `NVIDIA_DRIVER_CAPABILITIES=all` (Vulkan needs the graphics driver parts), 20 GB disk.
3. Send the zip (pick one):
   - **runpodctl** (simplest, nothing to set up in the account): on the MAC `runpodctl send <zip>` prints a one-time
     code; in the pod's terminal `runpodctl receive <code>`. End-to-end encrypted, peer to peer through a relay,
     the code works once. Needs runpodctl on the MAC (`brew install runpod/runpodctl/runpodctl`, or the release
     binary); the pods have it already.
   - scp: needs an SSH public key in the RunPod account settings and the pod's SSH over exposed TCP.
   - Not R2: the share-link storage takes room files only, and a dataset doesn't belong on a public host.
4. Pod terminal: `bash runpod_room.sh cloud-bench.zip <SHA-256>` (copy the script over the same way, or paste it).
   Optional upload of the result to a room's share link: type `export ROOMSCAPE_UPLOAD_TOKEN=…` and
   `export ROOM_ID=…` in the pod's terminal first (never in a file or chat).
5. Get `~/room-run/times.txt` and `runB/splat_30000.ply` back (`runpodctl send` in the pod, `receive` on the MAC),
   then **terminate the pod** (and delete its volume) so the data and the billing stop.
