import SwiftUI
import ComposeApp

/// The entire bridge to the shared code: `MainViewController()` is the Kotlin function in
/// `composeApp/src/iosMain/.../MainViewController.kt`, and everything below it is Compose.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard) // the Typst editor is a text field
    }
}
