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
                AppRoot()
            }
            #else
            AppRoot()
            #endif
        }
    }
}

/// The room list, or only the photo-real screen while the photo-real room is being made (rk 13 Nov 08:54: nothing
/// else reachable, so the rooms, scanner and their 3D views are gone and hold no memory).
struct AppRoot: View {
    @ObservedObject private var queue = ProcessingQueue.shared
    var body: some View {
        if let name = queue.photoRealScan {
            PhotoRealScreen(scanName: name)
        } else {
            HomeView()
        }
    }
}
