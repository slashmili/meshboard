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
        MainViewControllerKt.MainViewController(network: NativeAppleNetwork(), origin: {
            #if targetEnvironment(simulator)
            return AppleCrdtModeKt.appleCrdtPreviewEnabled() ? "http://127.0.0.1:5174" : "http://127.0.0.1:5173"
            #else
            return "https://"
            #endif
        }())
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
