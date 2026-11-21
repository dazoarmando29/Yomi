import SwiftUI
import UIKit
import YomiKit

/// Loads cover/page images through the shared `ImagePipeline`
/// (handles `sample://`, `file://` and `http(s)://` URLs).
struct SourceImageView: View {
    @EnvironmentObject private var environment: AppEnvironment
    let urlString: String?
    var contentMode: ContentMode = .fill
    var cornerRadius: CGFloat = 10

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else if failed {
                placeholder(icon: "photo")
            } else {
                placeholder(icon: "book.closed")
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .task(id: urlString) {
            await load()
        }
    }

    private func placeholder(icon: String) -> some View {
        ZStack {
            LinearGradient(
                colors: [YomiTheme.surfaceHigh, YomiTheme.surfaceContainer],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(YomiTheme.outline)
        }
    }

    @MainActor
    private func load() async {
        guard let urlString, !urlString.isEmpty else {
            failed = true
            return
        }
        failed = false
        image = nil
        do {
            let data = try await environment.pipeline.imageData(for: urlString)
            if let decoded = UIImage(data: data) {
                image = decoded
            } else {
                failed = true
            }
        } catch {
            failed = true
        }
    }
}
