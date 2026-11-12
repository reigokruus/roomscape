import SwiftUI
import UniformTypeIdentifiers
import WebKit

/// Shows a scan's photo-real preview (SplatPreview: splat/preview.ply) in a web view with the same splat viewer as
/// the Mac's (GaussianSplats3D on three.js, MIT; bundled in SplatViewer/, licences in SplatViewer/LICENSES.txt).
/// Offline: the page, its scripts and the scan's splat are served by the app itself under roomscape-splat://.
struct SplatViewerView: UIViewRepresentable {
    let splat: URL
    /// Where the camera starts (splat/view.json), if known.
    var view: (cam: [Float], look: [Float])?

    static let scheme = "roomscape-splat"

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(Handler(splat: splat), forURLScheme: Self.scheme)
        let web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        var url = "\(Self.scheme)://local/index.html"
        if let view {
            let f = { (v: [Float]) in v.map { String(format: "%.3f", $0) }.joined(separator: ",") }
            url += "?cam=\(f(view.cam))&look=\(f(view.look))"
        }
        web.load(URLRequest(url: URL(string: url)!))
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    /// Serves the bundled viewer files and the scan's splat (as scene.ply).
    final class Handler: NSObject, WKURLSchemeHandler {
        let splat: URL
        init(splat: URL) { self.splat = splat }

        func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
            guard let url = task.request.url else { return }
            let name = url.lastPathComponent
            let file: URL? = name == "scene.ply" ? splat : Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "SplatViewer")
            guard let file, let data = try? Data(contentsOf: file, options: .mappedIfSafe) else {
                task.didFailWithError(URLError(.fileDoesNotExist)); return
            }
            let type = name.hasSuffix(".html") ? "text/html" : name.hasSuffix(".js") ? "text/javascript" : "application/octet-stream"
            task.didReceive(HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                            headerFields: ["Content-Type": type, "Content-Length": "\(data.count)"])!)
            task.didReceive(data)
            task.didFinish()
        }

        func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}
    }
}

/// The preview as a full-screen sheet with a close button.
struct SplatPreviewSheet: View {
    let scan: SavedScan
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            SplatViewerView(splat: SplatPreview.result(scan), view: Self.startView(scan))
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle("Photo-real preview")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    static func startView(_ scan: SavedScan) -> (cam: [Float], look: [Float])? {
        guard let data = try? Data(contentsOf: SplatPreview.folder(scan).appendingPathComponent("view.json")),
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: [Double]],
              let cam = j["cam"], let look = j["look"], cam.count == 3, look.count == 3 else { return nil }
        return (cam.map(Float.init), look.map(Float.init))
    }
}

/// The licences of what the preview uses (three.js, GaussianSplats3D, Brush).
struct SplatLicencesView: View {
    var body: some View {
        ScrollView {
            Text((Bundle.main.url(forResource: "LICENSES", withExtension: "txt", subdirectory: "SplatViewer"))
                    .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "")
                .font(.caption.monospaced())
                .padding()
        }
        .navigationTitle("Licences")
    }
}
