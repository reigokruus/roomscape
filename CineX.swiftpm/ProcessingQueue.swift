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
        guard RecordingProcessor.hasRecording(scan) else { return }
        add(scan, Self.recordThenProcess ? .rebuild : .text)
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

    /// Runs what's waiting, if nothing is running (also at launch and when the app comes back).
    func start() {
        guard runner == nil, items.contains(where: { !held.contains($0.scan) }) else { return }
        if #available(iOS 26.0, *), UIApplication.shared.applicationState == .active, submitContinued() { return }
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
        task.expirationHandler = { [weak self] in
            MainActor.assumeIsolated {
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
        while let item = items.first(where: { !held.contains($0.scan) }) {
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
            perf.start(scan.captureURL.appendingPathComponent("perf-processing-\(item.job.rawValue).jsonl"), stage: "starting")
            PerfLog.processing = perf
            defer { perf.stop(); PerfLog.processing = nil }
            // Full speed (performance cores) while the app is open; background work goes to the efficiency cores.
            let priority: TaskPriority = UIApplication.shared.applicationState == .active ? .userInitiated : .utility
            log.info("priority: \(priority == .userInitiated ? "userInitiated (app open)" : "utility (in the background)")")
            let job = Task.detached(priority: priority) {
                try await RecordingProcessor.run(item.job, on: scan) { p in
                    Task { @MainActor in ProcessingQueue.shared.update(p) }
                }
            }
            inner = job
            do {
                try await withTaskCancellationHandler { try await job.value } onCancel: { job.cancel() }
                items.removeAll { $0 == item }
                finished = Finished(scan: item.scan, job: item.job, error: nil)
                log.info("processed \(item.scan)")
            } catch is CancellationError {
                return stopped()
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
        log.info("stopped part-way: \(current?.scan ?? "?") resumes from its last finished stage")
        inner = nil
        current = nil
        progress = nil
        return false
    }

    private func update(_ p: RecordingProcessor.Progress) {
        guard current != nil else { return }
        if p.fraction < 0, p.note != progress?.note { log.info("note: \(p.note ?? "continuing")") }
        if p.fraction >= 0, !p.stage.isEmpty, p.stage != progress?.stage { log.info("stage: \(p.stage)"); PerfLog.processing?.stage(p.stage) }
        if p.fraction < 0 { progress?.note = p.note } else { progress = .init(stage: p.stage, fraction: p.fraction, note: progress?.note) }
        report()
    }

    /// The system's progress view (iOS 26+ continued processing).
    private func report() {
        guard #available(iOS 26.0, *), let task = systemTask as? BGContinuedProcessingTask, let progress else { return }
        task.progress.completedUnitCount = Int64(max(0, min(1, progress.fraction)) * 1000)
        let waiting = items.count > 1 ? " · \(items.count - 1) more waiting" : ""
        task.updateTitle(current?.job == .rebuild ? "Building room scan" : "Finding text in room scan",
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
                Text(current.job == .rebuild ? "Building from the recording" : "Finding text in the scan").font(.headline)
                if scan == nil { Text(current.scan).font(.caption).foregroundStyle(.secondary) }
                ProgressView(value: max(0, min(1, p.fraction))) {
                    Text(p.stage.isEmpty ? "Working…" : p.stage).font(.footnote)
                }
                if let note = p.note {
                    Label(note, systemImage: "thermometer.medium").font(.footnote).foregroundStyle(.orange)
                }
                Text(queue.items.count > 1 ? "\(queue.items.count - 1) more waiting. Keeps going if you leave the app." : "Keeps going if you leave the app.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// Home: the running job, and the "Build scans from the recording" test switch.
struct ProcessingSection: View {
    @ObservedObject private var queue = ProcessingQueue.shared
    @State private var fromRecording = ProcessingQueue.recordThenProcess

    var body: some View {
        Section {
            if queue.current != nil { ProcessingCard() }
            Toggle("Build scans from the recording (test)", isOn: $fromRecording)
                .onChange(of: fromRecording) { _, on in ProcessingQueue.recordThenProcess = on }
        } footer: {
            Text(fromRecording
                 ? "After Done, the scan is built again from its recording, in the background. The live scan is kept in the scan's before-rebuild folder."
                 : "After Done, sharp text patches are made from the scan's recording, in the background. The live scan stays as it is.")
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
                guard let f, f.scan == scan, f.job == .rebuild else { return }
                queue.finished = nil
                done = f.error.map { "Couldn't rebuild: \($0)" }
                    ?? "Go back and open the room again to see it. The previous version is kept in the scan's before-rebuild folder."
            }
    }
}
