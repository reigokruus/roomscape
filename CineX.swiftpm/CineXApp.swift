import SwiftUI

@main
struct CineXApp: App {
    init() {
        // Processing after the scan: the background-resume handler must be registered before launch finishes.
        ProcessingQueue.registerBackgroundTasks()
        // A scan killed mid-way (no scan is running at launch): keep its recording safe from temporary-file clean-up.
        RawRecorder.keepInterrupted()
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            // The scan screen without a phone: -virtualScan <recording folder> [-virtualScanAt <s>] (VirtualScan.swift).
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-virtualScan"), i + 1 < args.count {
                let at = args.firstIndex(of: "-virtualScanAt").flatMap { $0 + 1 < args.count ? Double(args[$0 + 1]) : nil } ?? 0
                VirtualScanScreen(folder: URL(fileURLWithPath: args[i + 1]), startAt: at)
            } else {
                HomeView()
            }
            #else
            HomeView()
            #endif
        }
    }
}
