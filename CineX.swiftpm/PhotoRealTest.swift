import SwiftUI

/// Testing the photo-real preview without scanning a room again (rk 13 Nov 09:11: "im pretty tired of having to do the
/// same scan of the same room over and over"). Home-screen switch "Photo-real memory test (test)", off by default.
/// With it on, a saved room's Share menu has:
/// - "Photo-real memory test": opens the real scanner for 25 s (phone lying still is fine), closes it the normal way
///   (Cancel: nothing is saved, the throwaway recording is deleted), then makes the photo-real room of THIS saved scan
///   from step 0 into a separate test folder (splat-test/), so the real preview and its saves are kept. That is the
///   after-scan memory state of the crashes, with all the memory lines in the processing log.
/// - "Make photo-real again (test)": the same without the scanner run (a fresh-app baseline to compare with).
@MainActor final class PhotoRealTest: ObservableObject {
    static let shared = PhotoRealTest()

    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "photoRealTest") }
        set { UserDefaults.standard.set(newValue, forKey: "photoRealTest") }
    }

    /// The scan whose test waits for the scanner run (HomeView opens the scanner when this is set).
    @Published var scannerRunFor: String?
    /// Set 25 s into the test's scanner run: the scanner closes itself the normal way (Cancel).
    @Published var closeScanner = false

    static let scannerSeconds: Double = 25

    func runWithScanner(_ scan: SavedScan) {
        ProcessingQueue.logMemory("test: opening the scanner for \(Int(Self.scannerSeconds)) s")
        scannerRunFor = scan.name
    }

    /// The scanner of a test run is open: close it after `scannerSeconds`.
    func scannerOpened() {
        guard scannerRunFor != nil else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.scannerSeconds) { [weak self] in
            guard self?.scannerRunFor != nil else { return }
            ProcessingQueue.logMemory("test: closing the scanner")
            self?.closeScanner = true
        }
    }

    /// The scanner closed: if it was a test run, do the scan's "building from the recording" work as after a real
    /// scan (a dry run: nothing goes into the scan), then the test preview of the saved scan. With both, the memory
    /// at the preview's start is close to a real after-scan state (13 Nov: about 940 MB; the scanner alone left 524).
    func scannerClosed() {
        guard let name = scannerRunFor else { return }
        scannerRunFor = nil
        closeScanner = false
        guard let scan = ScanStore.list().first(where: { $0.name == name }) else { return }
        ProcessingQueue.logMemory("test: scanner closed, building from the recording (dry run) next")
        ProcessingQueue.shared.photoRealScan = scan.name
        ProcessingQueue.shared.add(scan, .rebuildTest)
        ProcessingQueue.shared.add(scan, .splatTest)
    }

    func runNow(_ scan: SavedScan) {
        ProcessingQueue.logMemory("test: photo-real test of \(scan.name)")
        ProcessingQueue.shared.photoRealScan = scan.name
        ProcessingQueue.shared.add(scan, .splatTest)
    }
}

/// The two test items for a room's Share menu (only with the switch on).
struct PhotoRealTestMenuItems: View {
    let scan: SavedScan
    var body: some View {
        if PhotoRealTest.enabled, RecordingProcessor.hasRecording(scan) {
            Button { PhotoRealTest.shared.runWithScanner(scan) } label: {
                Label("Photo-real memory test (25 s scanner, then this room)", systemImage: "memorychip")
            }
            Button { PhotoRealTest.shared.runNow(scan) } label: {
                Label("Make photo-real again (test, from step 0)", systemImage: "arrow.counterclockwise")
            }
        }
    }
}
