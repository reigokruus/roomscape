import UserNotifications
import BackgroundTasks
import Foundation
import SwiftUI
import UIKit
import os

/// The processing log: the system log, and Documents/processing-log.txt (copied off the phone to check background
/// runs from the Mac; reading the system log from there needs an admin). Kept under ~200 KB.
private enum log {
    static let logger = Logger(subsystem: "RoomScape", category: "Processing")
    private static let lock = NSLock()
    static var file: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("processing-log.txt") }

    static func info(_ s: String) { logger.info("\(s, privacy: .public)"); append(s) }
    static func error(_ s: String) { logger.error("\(s, privacy: .public)"); append("ERROR " + s) }

    private static func append(_ s: String) {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let state = ProcessInfo.processInfo.thermalState.rawValue
        let line = "\(f.string(from: Date())) [thermal \(state)] \(s)\n"
        lock.lock(); defer { lock.unlock() }
        let url = file
        if let h = try? FileHandle(forWritingTo: url) {
            defer { try? h.close() }
            if let size = try? h.seekToEnd(), size > 200_000 {
                try? h.close()
                let old = (try? Data(contentsOf: url)) ?? Data()
                try? (old.suffix(100_000) + Data(line.utf8)).write(to: url)
                return
            }
            try? h.write(contentsOf: Data(line.utf8))
        } else {
            try? Data(line.utf8).write(to: url)
        }
    }
}

/// Processing after the scan (rk 9 Nov: "record raw now, process later"): jobs on scans' raw recordings, one at a
/// time, paced by the phone's heat, carrying on in the background.
///
/// - After Done the scan's text patches are found (`.text`); with "Build scans from the recording" on, the whole
///   scan is built from the recording instead (`.rebuild`). The live result stays the main one until the recording
///   path matches its quality.
/// - In the background: on iOS 26+ as a continued-processing task (the system shows its progress and lets the user
///   stop it); otherwise the app's short background time, then a background-processing task picks it up later.
/// - Resumable: the queue is saved (Documents/processing-queue.json) and every job keeps its finished stages
///   (RecordingProcessor), so a job stopped by the system, by the user or by a restart resumes where it was.
@MainActor final class ProcessingQueue: ObservableObject {
    /// True while a GPU job (the photo-real preview) runs: big 3D views let go of their scenes meanwhile.
    @Published private(set) var gpuJob = false
    /// The photo-real preview needs a fresh start of the app (SplatPreview.NeedsFreshStart): asked once (FreshStartSheet);
    /// until then preview jobs wait. Not saved: after the relaunch the queued preview just runs.
    @Published var freshStartNeeded = false

    /// The app's memory now (footprint, and what iOS still allows), into the processing log.
    nonisolated static func logMemory(_ label: String) {
        let avail = Double(os_proc_available_memory()) / 1_048_576
        log.info("memory \(label): footprint \(Int(PerfLog.footprintMB())) MB, available \(Int(avail)) MB")
    }

    static let shared = ProcessingQueue()

    struct Item: Codable, Hashable { var scan: String; var job: RecordingProcessor.Job }
    struct Finished: Equatable { var scan: String; var job: RecordingProcessor.Job; var error: String? }

    /// Waiting jobs; the first is the one running (or next).
    @Published private(set) var items: [Item] = []
    @Published private(set) var current: Item?
    @Published private(set) var progress: RecordingProcessor.Progress?
    /// The last job that ended (shown once by the screen that cares, then cleared).
    @Published var finished: Finished?

    /// "Build scans from the recording" (test, off by default): after Done the whole scan is rebuilt from the
    /// recording, not just its text patches. Launch argument -recordThenProcess turns it on too.
    static var recordThenProcess: Bool {
        get { UserDefaults.standard.bool(forKey: "recordThenProcess") || ProcessInfo.processInfo.arguments.contains("-recordThenProcess") }
        set { UserDefaults.standard.set(newValue, forKey: "recordThenProcess") }
    }

    /// "Finer surface" (test, off by default): rebuilds from the recording use 1.9 cm voxels, simplified (FineSurface),
    /// so thin things like 3 cm bars come out as rods. Launch argument -fineSurface turns it on too.
    nonisolated static var fineSurface: Bool {
        get { UserDefaults.standard.bool(forKey: "fineSurface") || ProcessInfo.processInfo.arguments.contains("-fineSurface") }
        set { UserDefaults.standard.set(newValue, forKey: "fineSurface") }
    }

    private var runner: Task<Void, Never>?
    private var inner: Task<Void, Error>?
    /// iOS 26+: the continued-processing task this run reports to (BGContinuedProcessingTask).
    private var systemTask: AnyObject?
    private var appBackgroundTask: UIBackgroundTaskIdentifier = .invalid

    private static let bundle = Bundle.main.bundleIdentifier ?? "com.hackathon.roomscape"
    /// The later-resume task (Info.plist BGTaskSchedulerPermittedIdentifiers).
    private static let resumeID = bundle + ".resume"
    private static var file: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("processing-queue.json")
    }

    private init() {
        if let data = try? Data(contentsOf: Self.file), let saved = try? JSONDecoder().decode([Item].self, from: data) { items = saved }
        // Test: launch with -processNewest text|rebuild to queue that job for the newest scan with a recording.
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-processNewest"), i + 1 < args.count, let job = RecordingProcessor.Job(rawValue: args[i + 1]),
           let scan = ScanStore.list().first(where: RecordingProcessor.hasRecording) {
            let item = Item(scan: scan.name, job: job)
            if !items.contains(item) { items.append(item) }
            log.info("test: queued \(job.rawValue) for \(scan.name)")
        }
    }

    /// At launch (before it finishes): the handler for resuming later in the background.
    nonisolated static func registerBackgroundTasks() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: resumeID, using: .main) { task in
            MainActor.assumeIsolated { ProcessingQueue.shared.runInSystemTask(task) }
        }
    }

    // MARK: Adding

    /// After Done: the job the settings ask for, once the recording is in the scan's folder.
    func scanSaved(_ scan: SavedScan) {
        Self.logMemory("scan done")
        guard RecordingProcessor.hasRecording(scan) else { return }
        add(scan, Self.recordThenProcess ? .rebuild : .text)
        // The photo-real preview is no longer started after every scan (rk 12 Nov 22:41: it got in the way of the
        // next scan in the field): it's started from the room screen ("Make photo-real"), for any saved scan.
    }

    func add(_ scan: SavedScan, _ job: RecordingProcessor.Job) {
        let item = Item(scan: scan.name, job: job)
        guard !items.contains(item) else { return }
        // A rebuild finds the text too: it replaces a waiting text job for the same scan.
        if job == .rebuild { items.removeAll { $0.scan == scan.name && $0.job == .text && $0 != current } }
        items.append(item)
        save()
        start()
    }

    func isBusy(_ scan: SavedScan) -> Bool { items.contains { $0.scan == scan.name } }

    /// Scans whose jobs wait (a Continue scan is adding to it and moves its folder about).
    private var held: Set<String> = []

    /// Stops this scan's job if it's running (it resumes from its last finished stage) and runs none until released.
    func hold(_ scan: SavedScan) {
        held.insert(scan.name)
        if current?.scan == scan.name { inner?.cancel() }
    }

    func releaseAll() {
        held.removeAll()
        start()
    }

    /// While a scan is being made the photo-real preview waits (GPU, memory, heat): a running one stops at its last
    /// checkpoint and carries on from there when the scan is done; the scan is never at risk (rk 12 Nov 22:41).
    private var scanning = false
    func scanningStarted() {
        scanning = true
        if current?.job == .splat {
            log.info("a new scan starts: the photo-real preview stops at its last save and carries on afterwards")
            inner?.cancel()
        }
    }
    func scanningEnded() {
        scanning = false
        start()
    }
    private func runnable(_ item: Item) -> Bool {
        !held.contains(item.scan) && !((scanning || freshStartNeeded) && item.job == .splat)
    }
    /// The photo-real preview waiting or running for this scan.
    func splatQueued(_ scan: SavedScan) -> Bool { items.contains { $0.scan == scan.name && $0.job == .splat } }

    /// Runs what's waiting, if nothing is running (also at launch and when the app comes back).
    func start() {
        guard runner == nil, items.contains(where: runnable) else { return }
        // Not only when .active: right after Done the app is often .inactive (the scanner closing), and then the
        // continued task was never used (processing log 9 Nov).
        // The photo-real preview (GPU) can't run in the background, so not as a continued task (rk 14:20: iOS showed
        // "Task failed" when the app was left mid-training): in the app only; it waits and resumes when the app is back.
        let next = items.first(where: runnable)
        if next?.job != .splat, #available(iOS 26.0, *), UIApplication.shared.applicationState != .background, submitContinued() { return }
        runInApp()
    }

    // MARK: Running

    /// iOS 26+: asks the system to run the queue as a continued-processing task, which keeps going when the app is
    /// left (with the system's own progress view). False if it can't start right now.
    @available(iOS 26.0, *)
    private func submitContinued() -> Bool {
        let id = Self.bundle + ".process." + String(UUID().uuidString.prefix(8))
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main) { task in
            MainActor.assumeIsolated { ProcessingQueue.shared.runInSystemTask(task) }
        }
        guard registered else { log.info("continued task: couldn't register"); return false }
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: "Processing room scan", subtitle: "Starting")
        request.strategy = .fail // can't start now: run in the app instead
        do {
            try BGTaskScheduler.shared.submit(request)
            log.info("continued task submitted")
            // Holds the place until the system starts it (right away; the handler takes over). Should it not, the
            // app runs it itself.
            runner = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard let self, !Task.isCancelled, systemTask == nil else { return }
                log.info("continued task didn't start: running in the app")
                runner = nil
                runInApp()
            }
            return true
        } catch {
            log.info("continued task not started: \(error.localizedDescription)")
            return false
        }
    }

    /// The system started a task for the queue (continued processing, or the later resume).
    private func runInSystemTask(_ task: BGTask) {
        log.info("system task started: \(task.identifier) (\(type(of: task)))")
        systemTask = task
        // BackgroundTasks calls this on one of its own queues, not the main thread (crash report 12 Nov 12:44Z:
        // MainActor.assumeIsolated trapped here), so hop to the main actor.
        task.expirationHandler = { [weak self] in
            Task { @MainActor in
                log.info("background time is over: stopping, resumes later")
                self?.runner?.cancel()
                self?.inner?.cancel()
            }
        }
        if #available(iOS 26.0, *), let t = task as? BGContinuedProcessingTask { t.progress.totalUnitCount = 1000 }
        runner?.cancel()
        runner = Task { [weak self] in
            let complete = await self?.drain() ?? false
            task.setTaskCompleted(success: complete)
            self?.systemTask = nil
            self?.runner = nil
            if !complete { self?.scheduleResume() } else { self?.start() }
        }
    }

    /// Without a system task: runs while the app is open, plus the short time iOS gives an app that was just left.
    /// If that runs out, a background-processing task resumes it later.
    private func runInApp() {
        log.info("running in the app (app state \(UIApplication.shared.applicationState.rawValue))")
        appBackgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Processing room scan") { [weak self] in
            MainActor.assumeIsolated {
                self?.runner?.cancel()
                self?.inner?.cancel()
            }
        }
        runner = Task { [weak self] in
            let complete = await self?.drain() ?? false
            guard let self else { return }
            if appBackgroundTask != .invalid { UIApplication.shared.endBackgroundTask(appBackgroundTask); appBackgroundTask = .invalid }
            runner = nil
            if !complete { scheduleResume() }
        }
    }

    private func scheduleResume() {
        guard !items.isEmpty else { return }
        let request = BGProcessingTaskRequest(identifier: Self.resumeID)
        request.requiresExternalPower = false
        request.requiresNetworkConnectivity = false
        do { try BGTaskScheduler.shared.submit(request); log.info("resume scheduled") } catch {
            log.info("resume not scheduled: \(error.localizedDescription) (resumes when the app opens)")
        }
    }

    /// Runs the waiting jobs in turn. False if it was stopped (the jobs stay queued and resume later).
    private func drain() async -> Bool {
        while let item = items.first(where: runnable) {
            if Task.isCancelled { return stopped() }
            guard let scan = ScanStore.list().first(where: { $0.name == item.scan }), RecordingProcessor.hasRecording(scan) else {
                items.removeAll { $0 == item }; save(); continue // deleted meanwhile
            }
            current = item
            progress = RecordingProcessor.Progress(stage: "Starting", fraction: 0, note: nil)
            report()
            log.info("processing \(item.scan): \(item.job.rawValue)")
            // Hardware use while processing (PerfLog), kept with the scan.
            let perf = PerfLog()
            // keep the previous run's samples (a run that the system stopped is the one worth reading, 12 Nov 21:53)
            let perfURL = scan.captureURL.appendingPathComponent("perf-processing-\(item.job.rawValue).jsonl")
            if FileManager.default.fileExists(atPath: perfURL.path) {
                let stamp = Int(((try? FileManager.default.attributesOfItem(atPath: perfURL.path)[.modificationDate]) as? Date ?? Date()).timeIntervalSince1970)
                try? FileManager.default.moveItem(at: perfURL, to: scan.captureURL.appendingPathComponent("perf-processing-\(item.job.rawValue)-\(stamp).jsonl"))
            }
            perf.start(perfURL, stage: "starting")
            PerfLog.processing = perf
            defer { perf.stop(); PerfLog.processing = nil }
            // Full speed (performance cores) while the app is open; background work goes to the efficiency cores.
            let priority: TaskPriority = UIApplication.shared.applicationState != .background ? .userInitiated : .utility
            log.info("priority: \(priority == .userInitiated ? "userInitiated (app open)" : "utility (in the background)")")
            let job = Task.detached(priority: priority) {
                try await RecordingProcessor.run(item.job, on: scan) { p in
                    Task { @MainActor in ProcessingQueue.shared.update(p) }
                }
            }
            inner = job
            // The photo-real preview needs most of the app's memory on the phone: screens holding big 3D scenes let
            // go of them while it runs (ResultView), and load them again after (12 Nov 22:08: a preview right
            // after a scan started with 1.2 GB still held by the room view and died while loading)
            gpuJob = item.job == .splat
            defer { gpuJob = false }
            do {
                try await withTaskCancellationHandler { try await job.value } onCancel: { job.cancel() }
                items.removeAll { $0 == item }
                finished = Finished(scan: item.scan, job: item.job, error: nil)
                if item.job == .splat { SplatPreview.notifyReady(scan) }
                log.info("processed \(item.scan)")
            } catch is CancellationError {
                return stopped()
            } catch is SplatPreview.NeedsFreshStart {
                // kept in the queue (and its saves): it runs after the app is opened again
                log.info("photo-real preview waits for a fresh start of the app")
                freshStartNeeded = true
                inner = nil
                current = nil
                progress = nil
                continue
            } catch {
                // Kept in the scan's processing folder; "Rebuild from recording" tries again.
                log.error("processing failed: \(String(describing: error))")
                items.removeAll { $0 == item }
                finished = Finished(scan: item.scan, job: item.job, error: error.localizedDescription)
            }
            inner = nil
            save()
        }
        current = nil
        progress = nil
        return true
    }

    private func stopped() -> Bool {
        gpuJob = false
        log.info("stopped part-way: \(current?.scan ?? "?") resumes from its last finished stage")
        inner = nil
        current = nil
        progress = nil
        return false
    }

    private func update(_ p: RecordingProcessor.Progress) {
        guard current != nil else { return }
        if p.fraction < 0, p.note != progress?.note { log.info("note: \(p.note ?? "continuing")") }
        // Stage changes go to the processing log and PerfLog, but not every changing number (the photo-real
        // preview's "Step N of 6,000 · about M min left" changes every 5 steps): only when the words change, plus a
        // progress line every 5%.
        if p.fraction >= 0, !p.stage.isEmpty, p.stage != progress?.stage {
            let words = { (s: String) in s.filter { !$0.isNumber } }
            let oldF = progress?.fraction ?? 0
            if words(p.stage) != words(progress?.stage ?? "") { log.info("stage: \(p.stage)"); PerfLog.processing?.stage(words(p.stage)) }
            else if Int(p.fraction * 20) != Int(oldF * 20) { log.info("stage: \(p.stage)") }
            // the photo-real preview's first 500 steps every 50, so a run that dies early is located (12 Nov 17:05)
            else if let n = Int(p.stage.drop(while: { !$0.isNumber }).prefix(while: { $0.isNumber || $0 == "\u{a0}" || $0 == " " || $0 == "," })
                                  .filter(\.isNumber)), p.stage.hasPrefix("Step "), n < 500, n % 50 == 0 {
                log.info("stage: \(p.stage)")
            }
        }
        if p.fraction < 0 { progress?.note = p.note } else { progress = .init(stage: p.stage, fraction: p.fraction, note: progress?.note) }
        report()
    }

    /// The system's progress view (iOS 26+ continued processing).
    private func report() {
        guard #available(iOS 26.0, *), let task = systemTask as? BGContinuedProcessingTask, let progress else { return }
        task.progress.completedUnitCount = Int64(max(0, min(1, progress.fraction)) * 1000)
        let waiting = items.count > 1 ? " · \(items.count - 1) more waiting" : ""
        task.updateTitle(current?.job == .rebuild ? "Building room scan" : current?.job == .splat ? "Making room preview" : "Finding text in room scan",
                         subtitle: (progress.note ?? progress.stage) + waiting)
    }

    private func save() {
        try? JSONEncoder().encode(items).write(to: Self.file, options: .atomic)
    }
}

// MARK: - Views

/// The running job's stage, progress and heat note (Home: any scan; a room: that scan only).
struct ProcessingCard: View {
    @ObservedObject private var queue = ProcessingQueue.shared
    /// Only this scan's jobs, or every scan's.
    var scan: String? = nil

    var body: some View {
        if let current = queue.current, scan == nil || current.scan == scan, let p = queue.progress {
            VStack(alignment: .leading, spacing: 6) {
                Text(current.job == .rebuild ? "Building from the recording" : current.job == .splat ? "Photo-real room preview" : "Finding text in the scan").font(.headline)
                if scan == nil { Text(current.scan).font(.caption).foregroundStyle(.secondary) }
                ProgressView(value: max(0, min(1, p.fraction))) {
                    Text(p.stage.isEmpty ? "Working…" : p.stage).font(.footnote)
                }
                if let note = p.note {
                    Label(note, systemImage: "thermometer.medium").font(.footnote).foregroundStyle(.orange)
                }
                let keeps = current.job == .splat ? "Needs RoomScape open: pauses while you're in another app." : "Keeps going if you leave the app."
                Text(queue.items.count > 1 ? "\(queue.items.count - 1) more waiting. " + keeps : keeps)
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// When this app was built (the executable's date), so it's clear which build a phone has (rk 10 Nov: two builds
/// minutes apart looked the same). Shown under the test switches and written into every scan's hardware log.
enum BuildStamp {
    static let text: String = {
        let url = Bundle.main.executableURL
        let date = (url.flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path)[.modificationDate] as? Date }) ?? Date.distantPast
        let f = DateFormatter(); f.dateFormat = "d MMM HH:mm:ss"; f.locale = Locale(identifier: "en_GB")
        return "Build " + f.string(from: date)
    }()
}

/// Home: the running job, and the "Build scans from the recording" test switch.
struct ProcessingSection: View {
    @ObservedObject private var queue = ProcessingQueue.shared
    @State private var fromRecording = ProcessingQueue.recordThenProcess
    @State private var fine = ProcessingQueue.fineSurface
    @State private var livePoints = LivePreview.livePoints
    @State private var s24Look = LivePreview.s24Look
    @State private var depthConfidence = LivePreview.depthConfidence
    @State private var splat = SplatPreview.enabled
    @State private var flatTiles = FlatTiles.enabled
    @State private var screenVideo = ScreenVideo.enabled

    var body: some View {
        Section {
            if queue.current != nil { ProcessingCard() }
            Toggle("Build scans from the recording (test)", isOn: $fromRecording)
                .onChange(of: fromRecording) { _, on in ProcessingQueue.recordThenProcess = on }
            Toggle("Finer surface for thin things (test)", isOn: $fine)
                .onChange(of: fine) { _, on in ProcessingQueue.fineSurface = on }
            // rk 12 Nov: the S24's scan look is the default; off = the previous iPhone look (hints, mesh, zebra).
            Toggle("S24 scan look (dots over a dim camera, no hints)", isOn: $s24Look)
                .onChange(of: s24Look) { _, on in LivePreview.s24Look = on; livePoints = LivePreview.livePoints }
            Picker("Depth that makes dots", selection: $depthConfidence) {
                Text("Sure only").tag(2)
                Text("Sure + medium").tag(1)
                Text("All").tag(0)
            }
            .onChange(of: depthConfidence) { _, v in LivePreview.depthConfidence = v }
            if !s24Look {
                Toggle("Points-only live view (test)", isOn: $livePoints)
                    .onChange(of: livePoints) { _, on in LivePreview.livePoints = on }
            }
            // (no "Photo-real preview after a scan" switch: it's started per room with "Make photo-real", rk 12 Nov 22:41)
            NavigationLink("Licences (photo-real preview)") { SplatLicencesView() }
            RoomLinkSettings()
            Toggle("Flat tiles (test)", isOn: $flatTiles)
                .onChange(of: flatTiles) { _, on in FlatTiles.enabled = on }
            Toggle("Screen video (test)", isOn: $screenVideo)
                .onChange(of: screenVideo) { _, on in ScreenVideo.enabled = on }
        } footer: {
            Text(BuildStamp.text + "\n" + (fromRecording
                  ? "After Done, the scan is built again from its recording, in the background. The live scan is kept in the scan's before-rebuild folder."
                  : "After Done, sharp text patches are made from the scan's recording, in the background. The live scan stays as it is.")
                 + (fine ? " Rebuilds from the recording use a finer surface (bars and edges sharper, slower, about twice the triangles)." : ""))
        }
    }
}

/// A room: its scan's running job as a card, and the message when a rebuild of it ends (into `done`).
struct ProcessingOverlay: View {
    @ObservedObject private var queue = ProcessingQueue.shared
    let scan: String
    @Binding var done: String?

    var body: some View {
        ProcessingCard(scan: scan)
            .padding()
            .frame(maxWidth: 320)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .opacity(queue.current?.scan == scan ? 1 : 0)
            .allowsHitTesting(false)
            .onChange(of: queue.finished) { _, f in
                guard let f, f.scan == scan, f.job == .rebuild || f.job == .splat else { return }
                queue.finished = nil
                if f.job == .splat {   // the photo-real preview: say where it is, or why there is none
                    done = f.error.map { "Couldn't make the photo-real preview. \($0)" }
                        ?? "The photo-real preview is ready: Share menu (top right) > Photo-real preview."
                    return
                }
                done = f.error.map { "Couldn't rebuild: \($0)" }
                    ?? "Go back and open the room again to see it. The previous version is kept in the scan's before-rebuild folder."
            }
    }
}

/// One calm question when the photo-real preview needs a fresh start (ProcessingQueue.freshStartNeeded): RoomScape
/// closes itself, and a notification a moment later opens it again; the queued preview then runs by itself.
/// rk's own-use build: closing the app from code is acceptable here (13 Nov).
struct FreshStartSheet: ViewModifier {
    @ObservedObject private var queue = ProcessingQueue.shared
    func body(content: Content) -> some View {
        content.alert("RoomScape needs a fresh start to make the photo-real room", isPresented: $queue.freshStartNeeded) {
            Button("Close RoomScape") { Self.closeAndComeBack() }
        } message: {
            Text("It closes now. Tap the notification (or the app) to open it again, and the photo-real room starts by itself.")
        }
    }

    static func closeAndComeBack() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in
            let content = UNMutableNotificationContent()
            content.title = "Tap to make the photo-real room"
            content.body = "RoomScape carries on where it was."
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 2, repeats: false)
            center.add(UNNotificationRequest(identifier: "fresh-start", content: content, trigger: trigger)) { _ in
                ProcessingQueue.logMemory("closing for a fresh start")
                // the scan files are written when they're made; the queue is saved; let the log line land, then go
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { exit(0) }
            }
        }
    }
}
