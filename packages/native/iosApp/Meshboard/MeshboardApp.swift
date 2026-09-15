import SwiftUI
import MeshboardShared

@main
struct MeshboardApp: App {
    var body: some Scene {
        WindowGroup {
            BoardView().ignoresSafeArea()
        }
    }
}

private struct BoardView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
