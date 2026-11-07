# Shootlocal — 3-minute pitch

Approximately 400 spoken words across 9 slides. Aim for 140 words per minute, leaving time for the live SetScan demo. Slide timings include a brief, rehearsed iPhone demo; competitors stay to two or three sentences. Team names must be filled in before presenting. Final pitch: IDA Hub Jõhvi, Sunday 8 November 2026, 14:00.

## Slide 1 — Scan the room, plan the shot (0:00–0:18)

Imagine walking into a location where the room is already scanned: walls, windows for natural light, doors, furniture — and the crew shares one plan. That is Shootlocal. SetScan, our room-scanning module, works today on a LiDAR iPhone. Plan the shot before you arrive.

## Slide 2 — The problem (0:18–0:40)

Location photos are useful, but they don’t give you a measurable space. Where are the windows? Does the sofa block the camera? Different people imagine different shots. Our hypothesis: resolving those questions earlier — from a real scan — can reduce setup time and rework. We still need to measure that.

## Slide 3 — Who it’s for (0:40–0:55)

We start with indie filmmakers and small crews, where one person often handles several jobs. Photographers and video creators face similar challenges. The first user is the person planning the setup; the potential buyer is the production lead. We want to serve them without a full 3D design suite.

## Slide 4 — Solution and live demo (0:55–1:40)

Here is SetScan — live on the iPhone. Tap New room scan, walk the space slowly, Done. RoomPlan builds the model. Open the 3D view: windows tinted for natural light, doors and furniture detected, approximate size in the summary. You can save scans and share the USDZ.

That works today. The next layer — placing lights and cameras, trying twenty-four, thirty-five and fifty millimetres, sharing a shot plan — is shown in our web prototype as a concept, not shipping code yet.

[Demo: SetScan on iPhone — New room scan → Done → Open 3D view → briefly show Top / summary. Optional: open ui_prototype.html for place / lens / share concept. Return to deck.]

## Slide 5 — How it works (1:40–1:58)

Tech is honest and native: Swift, Apple RoomPlan, LiDAR. Requires iPhone 12 Pro or newer Pro, iOS 17, Xcode 15. SetScan exports a USDZ plus RoomPlan JSON, shows SceneKit 3D and top views, and lists walls, windows, doors and furniture. The planning layer on top of that scan is the roadmap — we are not claiming photorealistic lighting or automatic light placement.

## Slide 6 — Competitors (1:58–2:12)

Everyone owns a slice — Previs Pro for full 3D previs, Cadrage and Artemis for lens FOV, Polycam for capture, Shot Designer for blocking diagrams. Nobody lightly stitches a real RoomPlan scan into place, lens check and a crew plan. That is our wedge: SetScan already scans; the rest is next.

## Slide 7 — Business model (2:12–2:26)

Starting hypothesis: freemium — one active project free, Pro around nineteen euros per team per month for recurring productions. That price is to test, not proven demand. We will validate willingness to pay alongside hosting and support costs.

## Slide 8 — Why us (2:26–2:40)

I’m Marje Kikas, an indie PWA builder with a film, multimedia and design background. That connects the creator’s workflow with lightweight tools. Our hackathon teammates bring native iOS and product work, which we will name in the final pitch.

[Before presenting: replace the last sentence and deck placeholder with confirmed names and contributions.]

## Slide 9 — Ask and next steps (2:40–3:00)

We are looking for three pilot crews and one real location. We will demo SetScan, interview creators, plan one shot from a scan, compare it on location, and measure planning effort, setup rework and crew clarity. We have a working room scan and a validation plan — not customer results yet. Help us turn a scanned room into a shared, usable shot plan. That’s Shootlocal.
