import SwiftUI

/// The whole app while the photo-real room is made (rk 13 Nov 08:54: "force the user to be on the one spot in the app
/// where there is just the photoreal window"): progress, the warm / keep-open note and Stop. Nothing else is reachable,
/// so no room, room list or scanner holds memory meanwhile. When it's done it opens the result.
struct PhotoRealScreen: View {
    let scanName: String
    @ObservedObject private var queue = ProcessingQueue.shared
    @State private var stopped = false
    @State private var lookingAround = false

    private var scan: SavedScan? { ScanStore.list().first { $0.name == scanName } }
    private var running: Bool { queue.current?.scan == scanName && queue.current?.job == .splat }
    private var result: ProcessingQueue.Finished? {
        queue.finished.flatMap { $0.scan == scanName && $0.job == .splat ? $0 : nil }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Spacer()
                Image(systemName: "cube.transparent").font(.system(size: 56)).foregroundStyle(.secondary)
                Text(scan?.displayName ?? "Photo-real room").font(.title2.weight(.semibold))
                if let result, result.error == nil, let scan, SplatPreview.hasResult(scan) {
                    Text("The photo-real room is ready.").font(.headline)
                    Button { lookingAround = true } label: { Label("Look around", systemImage: "eye") }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                    Button("Back to the rooms") { leave() }
                } else if let result, let error = result.error {
                    Text("It couldn't be made: \(error)").multilineTextAlignment(.center).foregroundStyle(.secondary)
                    Button("Back to the rooms") { leave() }.buttonStyle(.borderedProminent)
                } else if stopped {
                    Text("Stopped. Make photo-real carries on from here later.").multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                    Button("Back to the rooms") { leave() }.buttonStyle(.borderedProminent)
                } else {
                    if running, let p = queue.progress {
                        ProgressView(value: max(0, min(1, p.fraction))) {
                            Text(p.stage.isEmpty ? "Working…" : p.stage).font(.callout)
                        }
                        if let note = p.note { Text(note).font(.footnote).foregroundStyle(.secondary) }
                    } else {
                        ProgressView()
                        Text("Getting ready (the scan's own processing finishes first)").font(.callout).foregroundStyle(.secondary)
                    }
                    Text("This takes a few minutes. The phone gets warm; keep RoomScape open on this screen.")
                        .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    Button(role: .destructive) {
                        queue.stopPhotoReal(scanName)
                        stopped = true
                    } label: { Label("Stop", systemImage: "stop.circle") }
                    .buttonStyle(.bordered)
                }
                Spacer()
            }
            .padding(24)
            .navigationTitle("Photo-real room")
            .navigationBarTitleDisplayMode(.inline)
        }
        .fullScreenCover(isPresented: $lookingAround) {
            if let scan { SplatPreviewSheet(scan: scan) }
        }
        .onAppear { ProcessingQueue.logMemory("photo-real screen shown") }
    }

    private func leave() {
        if queue.finished?.scan == scanName { queue.finished = nil }
        queue.photoRealScan = nil
    }
}
