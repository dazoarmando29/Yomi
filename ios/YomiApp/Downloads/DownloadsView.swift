import SwiftUI
import YomiKit

@MainActor
final class DownloadsViewModel: ObservableObject {
    @Published var items: [DownloadItem] = []
    @Published var storageUsage: Int64 = 0

    private var streamTask: Task<Void, Never>?

    func start(environment: AppEnvironment) {
        streamTask?.cancel()
        streamTask = Task {
            let stream = await environment.downloads.subscribe()
            for await snapshot in stream {
                self.items = snapshot
                self.storageUsage = await environment.downloads.storageUsage()
            }
        }
    }

    func stop() {
        streamTask?.cancel()
        streamTask = nil
    }

    func retry(_ item: DownloadItem, environment: AppEnvironment) async {
        await environment.downloads.retry(id: item.id)
    }

    func retryAll(environment: AppEnvironment) async {
        await environment.downloads.retryAllFailed()
    }

    func cancel(_ item: DownloadItem, environment: AppEnvironment) async {
        await environment.downloads.cancel(id: item.id)
    }

    func clearFinished(environment: AppEnvironment) async {
        await environment.downloads.clearFinished()
    }

    func clearAll(environment: AppEnvironment) async {
        try? await environment.downloads.clearAllDownloads()
    }
}

struct DownloadsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel = DownloadsViewModel()
    @State private var showClearConfirmation = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if viewModel.items.isEmpty {
                        Text("No downloads yet. Open a manga and use “Download all”, or long-press a chapter.")
                            .font(.footnote)
                            .foregroundStyle(YomiTheme.onSurfaceVariant)
                            .listRowBackground(YomiTheme.surfaceContainer)
                    }
                    ForEach(viewModel.items) { item in
                        DownloadRow(
                            item: item,
                            retry: { Task { await viewModel.retry(item, environment: environment) } },
                            cancel: { Task { await viewModel.cancel(item, environment: environment) } }
                        )
                        .listRowBackground(YomiTheme.surfaceContainer)
                    }
                } header: {
                    Text("Queue")
                } footer: {
                    Text("Downloads continue while the app is open. iOS may pause long background transfers.")
                }

                Section("Storage") {
                    HStack {
                        Text("Used by downloads")
                        Spacer()
                        Text(byteText(viewModel.storageUsage))
                            .foregroundStyle(YomiTheme.onSurfaceVariant)
                    }
                    .listRowBackground(YomiTheme.surfaceContainer)
                    Button("Clear finished items") {
                        Task { await viewModel.clearFinished(environment: environment) }
                    }
                    .listRowBackground(YomiTheme.surfaceContainer)
                    Button("Delete all downloads", role: .destructive) {
                        showClearConfirmation = true
                    }
                    .listRowBackground(YomiTheme.surfaceContainer)
                }
            }
            .scrollContentBackground(.hidden)
            .background(YomiTheme.background)
            .navigationTitle("Downloads")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Retry all") {
                        Task { await viewModel.retryAll(environment: environment) }
                    }
                }
            }
            .confirmationDialog(
                "Delete all downloads?",
                isPresented: $showClearConfirmation,
                titleVisibility: .visible
            ) {
                Button("Delete all", role: .destructive) {
                    Task { await viewModel.clearAll(environment: environment) }
                }
                Button("Cancel", role: .cancel) {}
            }
            .task {
                viewModel.start(environment: environment)
            }
            .onDisappear {
                viewModel.stop()
            }
        }
    }

    private func byteText(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

struct DownloadRow: View {
    let item: DownloadItem
    let retry: () -> Void
    let cancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.mangaTitle)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(YomiTheme.onSurface)
                        .lineLimit(1)
                    Text(item.chapterName)
                        .font(.caption)
                        .foregroundStyle(YomiTheme.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer()
                statusIcon
            }

            switch item.status {
            case .downloading(let page, let total):
                YomiProgressBar(fraction: total > 0 ? Double(page) / Double(total) : 0)
                Text("\(page)/\(total) pages")
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
                Button("Cancel", action: cancel)
                    .font(.caption)
            case .failed(let message):
                Text(message)
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.error)
                HStack(spacing: 14) {
                    Button("Retry", action: retry)
                        .font(.caption)
                    Button("Dismiss", action: cancel)
                        .font(.caption)
                }
            case .queued:
                Text("Waiting in queue")
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
                Button("Cancel", action: cancel)
                    .font(.caption)
            case .completed:
                Text("Available offline")
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.secondary)
            case .cancelled:
                Text("Cancelled")
                    .font(.caption2)
                    .foregroundStyle(YomiTheme.outline)
                Button("Retry", action: retry)
                    .font(.caption)
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch item.status {
        case .completed:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(YomiTheme.secondary)
        case .downloading:
            ProgressView()
                .tint(YomiTheme.primary)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundStyle(YomiTheme.error)
        case .queued:
            Image(systemName: "clock")
                .foregroundStyle(YomiTheme.outline)
        case .cancelled:
            Image(systemName: "xmark.circle")
                .foregroundStyle(YomiTheme.outline)
        }
    }
}
