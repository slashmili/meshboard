import SwiftUI
import MeshboardShared

@main
struct MeshboardApp: App {
    #if DEBUG && targetEnvironment(simulator)
    private static let interop: DebugInterop? = CommandLine.arguments.contains("--meshboard-interop") ? try! DebugInterop() : nil
    init() { _ = Self.interop }
    #endif
    var body: some Scene {
        WindowGroup {
            BoardView().ignoresSafeArea()
        }
    }
}

private struct BoardView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let scanner = NativeQrScanner()
        let controller = MainViewControllerKt.MainViewController(network: NativeAppleNetwork(), origin: {
            // MESHBOARD_APP_ORIGIN build setting; see Config/Local.xcconfig.example.
            let configured = (Bundle.main.object(forInfoDictionaryKey: "MeshboardAppOrigin") as? String ?? "")
                .trimmingCharacters(in: .whitespaces)
            if !configured.isEmpty { return configured.hasSuffix("/") ? String(configured.dropLast()) : configured }
            #if targetEnvironment(simulator)
            return "http://127.0.0.1:5173"
            #else
            return "https://"
            #endif
        }(), scanner: scanner)
        scanner.host = controller
        return controller
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
