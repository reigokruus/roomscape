import Darwin
import Foundation
import os
import UIKit

/// How the phone's hardware is used while scanning and while processing after Done (rk 9 Nov), sampled twice a second
/// into a small JSON-lines file, plus a summary at the end. Never synced or shared; copied off the phone to analyse.
///
/// Each line: time, stage, per-core CPU load (P and E cores told apart by sysctl: the A17 Pro has 2 P + 4 E), the
/// app's own CPU time, GPU busy time (summed from command buffers), scan frame rate and long frame gaps, Vision
/// timings, heat, Low Power Mode, memory footprint, battery. The Neural Engine can't be observed directly: Vision and
/// Core ML calls are timed and their requested compute units noted.
final class PerfLog: @unchecked Sendable {
    /// The log of the processing job running now (for timings deep in the pipeline, e.g. Vision in DecalFinder).
    nonisolated(unsafe) static var processing: PerfLog?

    private let lock = OSAllocatedUnfairLock(initialState: State())
    private struct State {
        var file: URL?
        var handle: FileHandle?
        var stage = ""
        var started = Date()
        var lastTicks: [[UInt32]] = []
        var lastCPU: Double = 0
        var lastSample = Date()
        var gpu: Double = 0, frames = 0, gaps = 0
        var notes: [String: [Double]] = [:]
        var battery: (Float, Int) = (-1, 0)
        // Whole-log totals (heat per scan): seconds in each thermal state, battery at the start, CPU and GPU seconds.
        var thermalSeconds = [Double](repeating: 0, count: 4)
        var batteryStart: Float = -1
        var cpuTotal = 0.0, gpuTotal = 0.0, elapsed = 0.0
        // For the summary, per stage.
        var sums: [String: Sums] = [:]
    }
    struct Sums: Codable {
        var samples = 0, seconds = 0.0
        var pLoad = 0.0, eLoad = 0.0, cpuSeconds = 0.0, gpuSeconds = 0.0, frames = 0, gaps = 0
        var maxThermal = 0, peakMemoryMB = 0.0
        var notes: [String: Double] = [:], noteCounts: [String: Int] = [:]
    }

    private var timer: DispatchSourceTimer?
    private var lastFile: URL?
    private let queue = DispatchQueue(label: "CineX.perf", qos: .utility)

    /// P and E core counts (perflevel0 = P, perflevel1 = E), and which CPU indices are which. On Apple chips the
    /// efficiency cores come first in the processor list (as on the M-series Macs); noted as an assumption.
    static let cores: (p: Int, e: Int) = {
        func sys(_ name: String) -> Int {
            var v: Int32 = 0; var size = MemoryLayout<Int32>.size
            return sysctlbyname(name, &v, &size, nil, 0) == 0 ? Int(v) : 0
        }
        return (sys("hw.perflevel0.logicalcpu"), sys("hw.perflevel1.logicalcpu"))
    }()

    /// Starts logging into `file` (a new file), at `stage`.
    func start(_ file: URL, stage: String) {
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: file.path, contents: nil)
        let handle = try? FileHandle(forWritingTo: file)
        lock.withLock { s in
            try? s.handle?.close()
            s = State()
            s.file = file; s.handle = handle; s.stage = stage; s.started = Date()
            s.lastTicks = Self.ticks(); s.lastCPU = Self.processCPU(); s.lastSample = Date()
        }
        UIDevice.current.isBatteryMonitoringEnabled = true
        write(["event": "start", "stage": stage, "pCores": Self.cores.p, "eCores": Self.cores.e,
               "coreOrder": "assumed: efficiency cores first in the processor list",
               "neuralEngine": "not observable; Vision/Core ML calls are timed (compute units: all)"])
        if timer == nil {
            let t = DispatchSource.makeTimerSource(queue: queue)
            t.schedule(deadline: .now() + 0.5, repeating: 0.5)
            t.setEventHandler { [weak self] in self?.sample() }
            t.resume()
            timer = t
        }
    }

    func stage(_ name: String) { lock.withLock { $0.stage = name } }

    /// A moment worth knowing the memory at (scan start, RoomPlan running, …), with any sizes to note (MB).
    func mark(_ name: String, sizes: [String: Double] = [:]) {
        var o: [String: Any] = ["event": "mark", "name": name, "memMB": Self.footprintMB().rounded()]
        if !sizes.isEmpty { o["sizesMB"] = sizes }
        write(o)
    }

    /// The file being written.
    var file: URL? { lock.withLock { $0.file } }

    /// A command buffer's GPU time (seconds).
    func gpu(_ seconds: Double) { lock.withLock { $0.gpu += max(0, seconds) } }
    /// One frame drawn by the scan view; `gap` when the camera frames came late.
    func frame() { lock.withLock { $0.frames += 1 } }
    /// The camera frames came late (more than 0.25 s apart).
    func gap() { lock.withLock { $0.gaps += 1 } }
    /// A timed call (Vision, Core ML, a stage step), milliseconds.
    func note(_ name: String, ms: Double) { lock.withLock { $0.notes[name, default: []].append(ms) } }

    /// Stops and writes the summary next to the log (summary.json, or `<name>.summary.json`).
    func stop() {
        timer?.cancel(); timer = nil
        sample()
        let (file, sums, handle, totals) = lock.withLock { s -> (URL?, [String: Sums], FileHandle?, State) in
            let r = (s.file, s.sums, s.handle, s); s.handle = nil; s.file = nil; return r
        }
        try? handle?.close()
        guard let file else { return }
        lastFile = file
        let summary = file.deletingPathExtension().appendingPathExtension("summary.json")
        var out: [String: Any] = ["pCores": Self.cores.p, "eCores": Self.cores.e]
        // Per-scan totals, to compare heat before/after changes.
        let minutes = max(0.001, totals.elapsed / 60)
        out["total"] = [
            "seconds": totals.elapsed,
            "secondsByThermalState": ["nominal": totals.thermalSeconds[0], "fair": totals.thermalSeconds[1],
                                      "serious": totals.thermalSeconds[2], "critical": totals.thermalSeconds[3]],
            "cpuCoreSeconds": totals.cpuTotal, "gpuBusySeconds": totals.gpuTotal,
            "batteryStartPercent": totals.batteryStart * 100, "batteryEndPercent": totals.battery.0 * 100,
            "batteryDropPercentPerMinute": totals.batteryStart >= 0 && totals.battery.0 >= 0 ? Double(totals.batteryStart - totals.battery.0) * 100 / minutes : -1,
        ]
        for (stage, s) in sums {
            let n = Double(max(1, s.samples))
            var notes: [String: Any] = [:]
            for (k, v) in s.notes { notes[k] = ["count": s.noteCounts[k] ?? 0, "meanMs": v / Double(max(1, s.noteCounts[k] ?? 1))] }
            out[stage] = ["seconds": s.seconds, "pLoadPercent": s.pLoad / n, "eLoadPercent": s.eLoad / n,
                          "appCPUSecondsPerSecond": s.cpuSeconds / max(0.001, s.seconds),
                          "gpuBusyPercent": 100 * s.gpuSeconds / max(0.001, s.seconds),
                          "framesPerSecond": Double(s.frames) / max(0.001, s.seconds), "frameGaps": s.gaps,
                          "maxThermal": s.maxThermal, "peakMemoryMB": s.peakMemoryMB, "timed": notes]
        }
        if let data = try? JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys]) { try? data.write(to: summary) }
    }

    /// Moves the log (and its summary) into a folder, e.g. the scan's capture folder once it's saved.
    func move(_ file: URL, into folder: URL, as name: String) {
        let fm = FileManager.default
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
        // Never over an earlier log (a Continue scan pass used to replace the scan's own): name, name-2, …
        var base = name, n = 2
        while fm.fileExists(atPath: folder.appendingPathComponent(base + ".jsonl").path) { base = "\(name)-\(n)"; n += 1 }
        for (src, dst) in [(file, base + ".jsonl"), (file.deletingPathExtension().appendingPathExtension("summary.json"), base + ".summary.json")] {
            try? fm.moveItem(at: src, to: folder.appendingPathComponent(dst))
        }
    }

    // MARK: Sampling

    private func sample() {
        let ticks = Self.ticks(), cpu = Self.processCPU(), now = Date()
        let thermal = ProcessInfo.processInfo.thermalState.rawValue
        let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        let memory = Self.footprintMB()
        DispatchQueue.main.async { [weak self] in
            let b = (UIDevice.current.batteryLevel, UIDevice.current.batteryState.rawValue)
            self?.lock.withLock { $0.battery = b }
        }
        let battery = lock.withLock { $0.battery }
        var line: [String: Any] = [:]
        lock.withLock { s in
            guard s.handle != nil else { return }
            let dt = now.timeIntervalSince(s.lastSample)
            var loads: [Double] = []
            for (i, t) in ticks.enumerated() where i < s.lastTicks.count {
                let o = s.lastTicks[i]
                let busy = Double(t[0] &- o[0]) + Double(t[1] &- o[1]) + Double(t[3] &- o[3]) // user, system, nice
                let total = busy + Double(t[2] &- o[2])
                loads.append(total > 0 ? (100 * busy / total).rounded() : 0)
            }
            let e = Self.cores.e, p = Self.cores.p
            let eLoads = Array(loads.prefix(e)), pLoads = Array(loads.dropFirst(e).prefix(p))
            let avg = { (a: [Double]) in a.isEmpty ? 0 : a.reduce(0, +) / Double(a.count) }
            let cpuDelta = cpu - s.lastCPU
            var notes: [String: Double] = [:]
            for (k, v) in s.notes where !v.isEmpty { notes[k] = v.reduce(0, +) / Double(v.count) }
            line = ["t": (now.timeIntervalSince(s.started) * 10).rounded() / 10, "stage": s.stage,
                    "coresE": eLoads, "coresP": pLoads,
                    "appCPU": (cpuDelta / max(0.001, dt) * 100).rounded() / 100,
                    "gpuMs": (s.gpu * 1000).rounded(), "frames": s.frames, "gaps": s.gaps,
                    "thermal": thermal, "lowPower": lowPower, "memMB": memory.rounded(),
                    "battery": battery.0, "charging": battery.1 == 2 || battery.1 == 3]
            if !notes.isEmpty { line["timedMs"] = notes }
            var sums = s.sums[s.stage] ?? Sums()
            sums.samples += 1; sums.seconds += dt
            sums.pLoad += avg(pLoads); sums.eLoad += avg(eLoads); sums.cpuSeconds += cpuDelta; sums.gpuSeconds += s.gpu
            sums.frames += s.frames; sums.gaps += s.gaps
            sums.maxThermal = max(sums.maxThermal, thermal); sums.peakMemoryMB = max(sums.peakMemoryMB, memory)
            for (k, v) in s.notes { sums.notes[k, default: 0] += v.reduce(0, +); sums.noteCounts[k, default: 0] += v.count }
            s.sums[s.stage] = sums
            s.thermalSeconds[min(3, max(0, thermal))] += dt
            if s.batteryStart < 0, battery.0 >= 0 { s.batteryStart = battery.0 }
            s.cpuTotal += cpuDelta; s.gpuTotal += s.gpu; s.elapsed += dt
            s.lastTicks = ticks; s.lastCPU = cpu; s.lastSample = now
            s.gpu = 0; s.frames = 0; s.gaps = 0; s.notes = [:]
        }
        if !line.isEmpty { write(line) }
    }

    private func write(_ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return }
        lock.withLock { s in
            try? s.handle?.write(contentsOf: data + Data("\n".utf8))
        }
    }

    /// Per processor: user, system, idle, nice ticks.
    private static func ticks() -> [[UInt32]] {
        var count: natural_t = 0
        var info: processor_info_array_t?
        var infoCount: mach_msg_type_number_t = 0
        guard host_processor_info(mach_host_self(), PROCESSOR_CPU_LOAD_INFO, &count, &info, &infoCount) == KERN_SUCCESS,
              let info else { return [] }
        defer { vm_deallocate(mach_task_self_, vm_address_t(bitPattern: info), vm_size_t(Int(infoCount) * MemoryLayout<integer_t>.stride)) }
        return (0..<Int(count)).map { i in
            (0..<Int(CPU_STATE_MAX)).map { UInt32(bitPattern: info[i * Int(CPU_STATE_MAX) + $0]) }
        }
    }

    /// The app's CPU time so far (user + system, seconds).
    private static func processCPU() -> Double {
        var u = rusage()
        getrusage(RUSAGE_SELF, &u)
        return Double(u.ru_utime.tv_sec) + Double(u.ru_utime.tv_usec) / 1e6 + Double(u.ru_stime.tv_sec) + Double(u.ru_stime.tv_usec) / 1e6
    }

    static func footprintMB() -> Double {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let r = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
        }
        return r == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : 0
    }
}
