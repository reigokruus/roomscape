import SwiftUI

@main
struct CineXApp: App {
    init() {
        // Processing after the scan: the background-resume handler must be registered before launch finishes.
        ProcessingQueue.registerBackgroundTasks()
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
        }
    }
}
