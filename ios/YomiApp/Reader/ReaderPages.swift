import SwiftUI
import YomiKit

// MARK: - Paged reader (left-to-right / right-to-left / vertical paged)

struct PagedReaderView: View {
    @ObservedObject var viewModel: ReaderViewModel
    @EnvironmentObject private var environment: AppEnvironment
    @State private var visiblePageID: Int?

    private var isHorizontal: Bool { viewModel.readingMode.isHorizontal }
    private var isRTL: Bool { viewModel.readingMode == .rightToLeft }

    var body: some View {
        ScrollView(isHorizontal ? .horizontal : .vertical, showsIndicators: false) {
            Group {
                if isHorizontal {
                    LazyHStack(spacing: 0) {
                        pages
                    }
                } else {
                    LazyVStack(spacing: 0) {
                        pages
                    }
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.viewAligned)
        .scrollPosition(id: $visiblePageID)
        .environment(\.layoutDirection, isRTL ? .rightToLeft : .leftToRight)
        .background(YomiTheme.readerBackground)
        .id(viewModel.readingMode)
        .onAppear {
            visiblePageID = viewModel.pages[safe: viewModel.currentPageIndex]?.id
        }
        .onChange(of: visiblePageID) { _, id in
            guard let id else { return }
            Task { await viewModel.moveTo(page: id, environment: environment) }
        }
        .onChange(of: viewModel.pages.count) { _, _ in
            visiblePageID = viewModel.pages[safe: viewModel.currentPageIndex]?.id
        }
        .overlay(alignment: .center) {
            tapZones
        }
    }

    @ViewBuilder
    private var pages: some View {
        ForEach(viewModel.pages) { page in
            PageImage(urlString: page.urlString)
                .containerRelativeFrame(isHorizontal ? .horizontal : .vertical)
                .id(page.id)
        }
    }

    /// Invisible navigation zones: tap left/right edges to step pages
    /// (with chapter transitions at boundaries), tap the centre for chrome.
    private var tapZones: some View {
        GeometryReader { proxy in
            HStack(spacing: 0) {
                zone(width: proxy.size.width * 0.25) {
                    Task {
                        if viewModel.currentPageIndex > 0 {
                            await viewModel.moveTo(page: viewModel.currentPageIndex - 1, environment: environment)
                        } else {
                            await viewModel.rewindBeforeStart(environment: environment)
                        }
                    }
                }
                zone(width: proxy.size.width * 0.5) {
                    viewModel.showChrome.toggle()
                }
                zone(width: proxy.size.width * 0.25) {
                    Task {
                        if viewModel.currentPageIndex + 1 < viewModel.pages.count {
                            await viewModel.moveTo(page: viewModel.currentPageIndex + 1, environment: environment)
                            visiblePageID = viewModel.pages[safe: viewModel.currentPageIndex]?.id
                        } else {
                            await viewModel.advanceBeyondEnd(environment: environment)
                        }
                    }
                }
            }
        }
        .allowsHitTesting(true)
    }

    private func zone(width: CGFloat, action: @escaping () -> Void) -> some View {
        Color.clear
            .frame(width: width)
            .contentShape(Rectangle())
            .onTapGesture(perform: action)
    }
}

// MARK: - Webtoon reader (continuous vertical scrolling)

struct WebtoonReaderView: View {
    @ObservedObject var viewModel: ReaderViewModel
    @EnvironmentObject private var environment: AppEnvironment

    /// `webtoon` scrolls without gaps; `continuousVertical` keeps the Android
    /// "long strip with gaps" behaviour.
    private var gap: CGFloat {
        viewModel.readingMode == .continuousVertical ? 8 : 0
    }

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(spacing: gap) {
                ForEach(viewModel.pages) { page in
                    PageImage(urlString: page.urlString)
                        .id(page.id)
                        .onAppear {
                            Task { await viewModel.moveTo(page: page.id, environment: environment) }
                        }
                }

                if !viewModel.pages.isEmpty {
                    endOfChapter
                }
            }
        }
        .background(YomiTheme.readerBackground)
        .id(viewModel.readingMode)
    }

    private var endOfChapter: some View {
        VStack(spacing: 12) {
            Text("End of \(viewModel.chapter.name)")
                .font(.footnote)
                .foregroundStyle(YomiTheme.onSurfaceVariant)
            Button {
                Task { await viewModel.advanceBeyondEnd(environment: environment) }
            } label: {
                Label("Next chapter", systemImage: "chevron.forward")
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.borderedProminent)
            .tint(YomiTheme.primary)
            .foregroundStyle(YomiTheme.onPrimary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
    }
}

// MARK: - Page image (zoom + double-tap)

struct PageImage: View {
    @EnvironmentObject private var environment: AppEnvironment
    let urlString: String

    @State private var image: UIImage?
    @State private var failed = false
    @State private var scale: CGFloat = 1.0
    @GestureState private var pinch: CGFloat = 1.0

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale * pinch)
                    .gesture(
                        MagnificationGesture()
                            .updating($pinch) { value, state, _ in
                                state = value
                            }
                            .onEnded { value in
                                scale = min(4, max(1, scale * value))
                            }
                    )
                    .onTapGesture(count: 2) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            scale = scale > 1 ? 1 : 2
                        }
                    }
            } else if failed {
                VStack(spacing: 8) {
                    Image(systemName: "exclamationmark.photo")
                        .foregroundStyle(YomiTheme.outline)
                    Text("Page failed to load")
                        .font(.caption)
                        .foregroundStyle(YomiTheme.outline)
                }
                .frame(maxWidth: .infinity, minHeight: 300)
            } else {
                ProgressView()
                    .tint(YomiTheme.primary)
                    .frame(maxWidth: .infinity, minHeight: 300)
            }
        }
        .frame(maxWidth: .infinity)
        .task(id: urlString) {
            await load()
        }
        .accessibilityLabel("Manga page")
    }

    @MainActor
    private func load() async {
        failed = false
        image = nil
        do {
            let data = try await environment.pipeline.imageData(for: urlString)
            image = UIImage(data: data)
            failed = image == nil
        } catch {
            failed = true
        }
    }
}

// MARK: - Helpers

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
