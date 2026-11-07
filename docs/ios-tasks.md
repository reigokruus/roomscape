# iOS tasks: CineX iPhone app

Task list for whoever works on the iPhone version (`CineX.swiftpm`) on a Mac with Xcode and a LiDAR iPhone. To use it with Claude Code, open this repo and say: "Read docs/ios-tasks.md and work through it."

The spec is `docs/android-to-ios-handoff.md`. Read it in full first. Section 1 lists the owner's product decisions.

## Ground rules
- The Claude API key is typed into the app by the user only. It is never put in chat, in code, or in git.
- Don't draw guessed objects such as cartoon furniture or labelled boxes. The owner removed these on purpose.
- Walls, floor and ceiling come from RoomPlan, not from user taps.
- Portrait only. The app targets iOS 17+ on LiDAR iPhones.
- `android/` belongs to the Android work. Leave it unchanged.
- Each task goes on its own branch with a PR into `main`, and the PR says what was tested on the phone. Zeynep merges.

## Tasks, in order
1. **Build it.** Build `CineX.swiftpm` for the attached iPhone and fix the compile errors. The merged Swift code (gear inventory, shoot planner, mesh overlay, frame check) has never been compiled together. Then run a RoomPlan scan and check that the saved scan opens.
2. **Live discs while scanning.**
   - From RoomPlan's `RoomCaptureSession.arSession` frames (`sceneDepth` + `confidenceMap`), build a 2 cm voxel point cloud with colour and a per-spot seen counter. Count once per depth frame, and call a spot solid after about 1 s of sightings.
   - Draw each spot as a disc over the camera. The rim goes red → yellow → green, and solid spots shrink to faint dots.
   - Show a status line: "Detail here: N% solid".
   - Details are in handoff section 2, under "Live capture", "Seen counter" and "Live discs".
3. **Save points and photos with the scan.**
   - Store the point cloud and keyframes (photo + pose + intrinsics, about 1 per 20 cm moved or 15° turned, at most 80) next to the RoomPlan usdz and json, in `ScanStore.swift`.
   - Show thumbnails on the home screen.
4. **Splat view.** In the result view, show the points as surfels: PCA normals, a radius from point density, and snapping to planes. Each one is drawn as a disc lying on its surface, together with RoomPlan's walls and floor. Port `Surfels.java`.
5. **Lighting preview.** Port `Lighting.java` and its shader, using the numbers in handoff section 3.
   - Gear lights, people, a table and chairs.
   - The room's own light stays in the image.
   - Exposure is set for the face, with an f-stop readout.
   - The panel can be closed.
6. **If time allows:**
   - Splat training with a live preview and a Pause button. Port `Trainer.java` to Metal compute.
   - Improve scan using ARWorldMap relocalisation.

After each task, summarise in two or three plain sentences what changed and what to try on the phone.
