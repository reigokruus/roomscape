# Android catalog and non-destructive scanned-item planning

## Scope and architecture

This is native Java/ARCore/OpenGL UI in the existing app, not HTML. It continues `testable-app`, retains the previous Swift planning integration, and incorporates the team changes from main `8601ea2` (including iOS live discs/capture and Android background processing) without a merge commit or rewriting published history. No workflow/release automation was changed.

Existing flow: `ScanRenderer` owns the processed scan and `RoomModel` fits floor/walls and approximate furniture `Box` objects from occupancy/point geometry. `ObjectNamer` can give those boxes on-device photo labels. These are **not a fully segmented fused mesh**, and a label such as “chair” does not identify an exact manufacturer model. `Lighting.Prop` already supplies native selectable/draggable/rotatable added objects. `ScanStore` replaces its scan directory on autosave; therefore planning sidecars live separately to avoid that replacement losing edits.

Two designs were considered: SQLite plus arbitrary product-page scraping, or a small explicit sourced JSON feed with an atomic app-private cache, integrated into existing prop selection and a stable detected-object overlay. The latter is the chosen narrow vertical slice: no backend signup, paid extraction, fragile page-axis guessing, or new rendering framework. The pure model/validation layer is exercised by JVM tests; Android UI/storage/hardware still needs a phone. No unavailable multi-model/cloud runner was used; this is the explicitly authorized local CLI workstream.

Ownership:
- `ItemCatalog`: SI normalization, source records, JSON validation, search, deterministic model-ID merge.
- `CatalogStore`: offline cache, atomic import, bounded HTTPS feed download on a worker thread.
- `RoomEdits`: stable detected-shape snapshot, hidden IDs, 30-operation session undo, catalog placement/provenance serialization.
- `RoomEditStore`: atomic per-scan planning sidecar outside the raw-scan autosave directory.
- `NativePlanningUi`: native browsing/search/import preview, placement and scanned-item controls; room changes use the existing GL owner.
- `ScanRenderer`/`Lighting`: real-size bounding proxies using the existing drawing and selection paths. `ScanExport`: embeds `planning.json` alongside the unchanged original points/photos.

## Catalog: actual supported data

Manufacturer/product pages were opened live on **2026-11-07**. These are factual size records, not recommendations, prices or guaranteed fit/safety measurements.

| Exact model | Listed item dimensions / interpretation | Source |
|---|---|---|
| Canon EOS R5 body | W 138.5 × H 97.5 × D 88 mm; no lens/cage/tripod | https://www.canon-europe.com/cameras/eos-r5/specifications/ |
| Aputure MC | 93 × 61 × 17 mm body. Source does not name axes; the planning orientation explicitly maps long face edge to X, short edge to Y and thickness to Z. No stand/ball head included. | https://www.aputure.com/products/mc/ |
| Manfrotto 1052BAC | 109 cm deployed footprint, max height 237 cm (min 101 cm, folded length 86 cm). Proxy uses a 109 × 109 cm enclosing square at max extension, not folded dimensions or an exact tripod mesh. | https://www.manfrotto.com/global-en/compact-photo-stand-air-cushioned-and-portable-1052bac/ |
| IKEA LACK white coffee table 904.499.05 | Assembled length 35 3/8 in × width 21 5/8 in × height 17 3/4 in. Length→X, width→Z, height→Y; exact inch-to-metre conversion of the published rounded figures. Packaging dimensions explicitly ignored. | https://www.ikea.com/us/en/p/lack-side-table-white-90449905/ |
| Blackmagic Pocket Cinema Camera 6K Pro | Exact model confirmed; accessible page text did not give body dimensions. W/H/D stay **unknown**; neither sensor nor screen size was used as body size. Only explicitly manual size permits placement. | https://www.blackmagicdesign.com/products/blackmagicpocketcinemacamera/techspecs/W-CIN-16 |

The Sony pages tried during research returned 403; that model is **not** included using search snippets as if they were a successfully opened spec sheet. No dimension was invented to fill a gap.

### Use

1. Home → **Browse item catalog (offline)**, or open a saved/processed scan → **Catalog**.
2. Search brand/model/category. Open a record to see dimensions, source URL/date, quoted specification, axis mapping and exclusions.
3. **Place sourced size** is disabled for incomplete measurements. **Manual size** records a separate per-placement override in metres and never changes the source fact. Base height above the floor is a placement setting, not a product dimension.
4. Placement makes a native physical-size box near the view centre. Open **Lighting** to select/drag it; **Type** turns it 45°; **Placed details** allows numeric position/base height/yaw edits and shows provenance. **Delete** removes the selected added object, not a scanned object. New light/camera catalog entries are size proxies only, not calibrated emitters/lens simulations. Collision/clearance is not automatically verified.
5. Positions and original source snapshots survive reopening; later catalog refreshes do **not** silently resize already placed objects. Source and manual dimensions stay separately exported.

### Internet update/import — explicit and real, not an arbitrary scraper

**Update from internet JSON feed** performs an actual HTTPS GET, then previews the validated records before a user confirms merge into the persistent cache. Default:

`https://raw.githubusercontent.com/reigokruus/shootlocal/testable-app/android/app/src/main/assets/equipment-catalog.json`

This is a maintained/curated spec feed. Downloading it is **not** a live re-scrape or new verification of manufacturer pages; each row retains its original manufacturer-retrieval date. Supported network URLs are JSON files under `raw.githubusercontent.com/reigokruus/shootlocal/` only. No credentials, cookies, arbitrary private-network endpoints, product-page automatic extraction, redirects, prices or paid API calls are involved. The app also imports explicitly selected local JSON files through Android's document picker. No cloud catalog server was deployed.

Schema-1 example is the actual bundled `android/app/src/main/assets/equipment-catalog.json`:

- `schemaVersion: 1`, `items: [...]` (1–200 rows).
- Row: stable lowercase `id`, `manufacturer`, exact `model`, `category` (`camera`, `light`, `stand`, `furniture`), `dimensionKind` (`body`, `deployed`, `assembled`), `unit` (`mm`, `cm`, `m`, `in`), numeric-or-null `width`, `height`, `depth`, HTTPS `sourceUrl`, `retrievedDate` (`YYYY-MM-DD`), `sourceQuote`, `axisNotes`.
- **Package** or ambiguous dimension kinds are rejected. Partial measurements are retained as unknown, not zero or an estimate. SI dimensions must be finite and 1 mm–20 m; null/missing dimensions disable sourced-size placement.
- A feed is at most 256 KiB / 12 JSON nesting levels. Text fields, dates, model IDs, units and numeric types are bounded/validated; invalid UTF-8, duplicate row IDs, future/invalid dates and malformed JSON fail before cache replacement. HTTPS requests have connect/read timeouts and refuse redirects. Offline/download failures keep the previous cache.
- Import merges by stable ID. Same ID with a different manufacturer/model or an older retrieval date is rejected atomically; unrelated cached models remain. No account/cloud sync. Reimporting the same records is idempotent.
- Imported rows are **source claims supplied by that feed**, not independently authenticated manufacturer facts. The preview/UI explicitly says they are not reverified. Manually review source quotes/axis mapping before importing a new row.

To update manufacturer facts, a maintainer reopens the factual page, records exactly what it says (or null if unavailable), verifies body/deployed/packaging scope and orientation, advances the retrieval date only after that check, and updates the JSON. That is the supported explicit ingestion path, not a claim of general web extraction.

## Remove detected scanned items from the digital planning room

1. Open a saved processed room → **Scanned items**. This captures the actual `RoomModel.Box` furniture detections, NOT just newly added catalog objects. If no detections exist, the UI says so; it does not create pretend removable furniture.
2. Select a detected item by its label/dimensions. Its shape is highlighted and the view focuses on it. **Hide from plan** removes that shape from the schematic digital planning room.
3. Use the same list to **Restore shape**, **Restore all**, or **Undo this session** (30 operations). Hidden state and the source shape snapshot are persisted atomically and reopen consistently. Undo history itself resets after reopening.
4. **Show original scan / Show planning room** compares the representations. **Original fused points, mesh, photographs, floor and walls are never deleted or rewritten by Hide.** The original scan still contains the furniture. No inpainting, mesh surgery or reconstruction of unseen surfaces is claimed.
5. Planning view deliberately draws detected shapes plus fitted room walls/floor rather than layering the unchanged photographic mesh over a hidden box. Walls/floor/ceilings are not selectable for removal. A detected envelope is approximate and may group multiple physical objects; this is not perfect segmentation.
6. Share ZIP includes the same `planning.json` used for local overlay serialization: detected-shape snapshots, hidden IDs, placed model snapshots/manual overrides and positions. `points.ply` and photos intentionally remain original, so an external viewer must read the overlay to reproduce hidden planning shapes. No data is sent automatically.

The detected snapshot is stable after first non-empty capture; rebuilding the scan's occupancy analysis does not accidentally restore a hidden item by changing array indices. It is not a live continuously re-segmented furniture inventory. A scan with >200 detected shapes fails explicitly instead of silently omitting some. Catalog placements are bounded to 100. Sidecars are app-private at `files/room-plans/<scan_id>.json`; catalog cache is `files/equipment-catalog.json`. Corrupt overlay files block edits rather than being silently overwritten, while the original scan remains accessible. Corrupt-sidecar recovery requires developer inspection; the app does not silently reset the file.

## Build and validation

Use the existing `scripts/build-android-test.sh` (JDK 17, Android SDK/build-tools 35). Build numbers are explicit to avoid accidental downgrade of an installed test app. Added behavioral tests are `CatalogAndRoomEditsTest`; existing `NativePlanningTest` and portable Swift planning-core tests remain. The APK verification also checks that the catalog asset and five native feature classes actually exist in the packaged DEX/ZIP.

Optional real-network check (after this branch is pushed): `cd android && ./gradlew verifyCatalogFeed`. It exercises the same production HTTPS downloader and schema validator on the JVM, without writing a catalog cache. It is not part of offline tests, a manufacturer re-scrape, or an on-phone network/UI test.

Required real-phone acceptance (not performed here):
- Install on an ARCore-supported ARM64 Android phone; camera permission and Google Play Services for AR. Prefer a depth-capable device for scan quality. No paid/Claude API key is needed; leave optional external-AI integrations off.
- Browse/search all 5 records offline; inspect exact quotes/axes and the Blackmagic unknown state. Import the network feed; confirm preview and successful offline reload. Reject a malformed/duplicate-ID/oversized feed without losing cache.
- Place Canon and IKEA using sourced size; compare dimensions/scale with physical references. Place an explicitly MANUAL Blackmagic proxy. Rotate/drag/change base height; reopen app; inspect sources and manual overrides. Verify catalog lights do not claim calibrated output.
- Scan a room with recognisable furniture; open Scanned items, select/hide a detected item, compare planning vs original scan. Restore, hide again, undo, force-close/reopen. Original scan still shows that furniture; planning view must respect saved hidden state.
- Share ZIP locally, inspect `planning.json` and confirm original PLY/photos remain intact. Check no walls/floor entered the removable list. Repeat after improving a scan. Test keyboard/small-screen/dialog scrolling and failed-storage errors.

**Platform distinction:** these two new features are in Android. Existing Swift storyboard source remains separate; this task does not claim a compiled iOS catalog or matching iOS removal feature. No iOS binary, simulator run, physical-phone launch/render/scan, GL shader runtime validation or production-readiness claim is made. The recorded compiler/tests establish a testable package, not those hardware results.
