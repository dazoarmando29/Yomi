import SwiftUI
import UniformTypeIdentifiers
import YomiKit

// MARK: - Backup import/export

@MainActor
final class BackupViewModel: ObservableObject {
    @Published var report: ImportReport?
    @Published var parsedBackup: YomiBackup?
    @Published var errorMessage: String?
    @Published var importResult: ImportApplyResult?
    @Published var isWorking = false

    func handleImport(url: URL, environment: AppEnvironment) async {
        isWorking = true
        defer { isWorking = false }
        errorMessage = nil
        importResult = nil
        do {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)

            let knownSources = await environment.registry.all.map { $0.id }
            let prepared = try await BackupImporter.prepare(
                data: data,
                knownSources: knownSources,
                store: environment.library
            )
            parsedBackup = prepared.backup
            report = prepared.report
        } catch {
            errorMessage = "Could not read backup: \(error.localizedDescription)"
        }
    }

    /// Applies the import. Only called after explicit user confirmation —
    /// existing data is merged, never overwritten or deleted.
    func confirmImport(environment: AppEnvironment) async {
        guard let backup = parsedBackup else { return }
        isWorking = true
        defer { isWorking = false }
        do {
            let result = try await environment.library.apply(BackupImporter.plan(backup))
            importResult = result
            report = nil
            parsedBackup = nil
        } catch {
            errorMessage = "Import failed: \(error.localizedDescription)"
        }
    }

    func discard() {
        report = nil
        parsedBackup = nil
        importResult = nil
        errorMessage = nil
    }

    func exportData(environment: AppEnvironment) async -> Data {
        let snapshot = await environment.library.snapshot()
        var names: [Int64: String] = [:]
        for source in await environment.registry.all {
            names[source.id.rawValue] = source.name
        }
        return BackupExporter.exportGzipped(from: snapshot, sourceNames: names)
    }
}

struct BackupView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel = BackupViewModel()
    @State private var showImporter = false
    @State private var showExporter = false
    @State private var exportDocument: BackupDocument?
    @State private var showConfirmApply = false

    var body: some View {
        List {
            Section {
                Button {
                    showImporter = true
                } label: {
                    Label("Import backup (proto.gz)", systemImage: "square.and.arrow.down")
                }
                Button {
                    Task {
                        exportDocument = BackupDocument(data: await viewModel.exportData(environment: environment))
                        showExporter = true
                    }
                } label: {
                    Label("Export backup", systemImage: "square.and.arrow.up")
                }
            } footer: {
                Text("Compatible with backups created by Yomi and Mihon on Android (gzipped Protobuf).")
            }

            if let error = viewModel.errorMessage {
                Section {
                    ErrorBanner(message: error)
                        .listRowBackground(Color.clear)
                }
            }

            if let report = viewModel.report {
                Section("Import preview") {
                    LabeledContent("Manga", value: "\(report.mangaCount)")
                    LabeledContent("Chapters", value: "\(report.chapterCount)")
                    LabeledContent("Categories", value: "\(report.categoryCount)")
                    LabeledContent("History entries", value: "\(report.historyCount)")
                    LabeledContent("Tracking links", value: "\(report.trackingCount)")
                    if report.duplicateMangaCount > 0 {
                        LabeledContent("Already in library", value: "\(report.duplicateMangaCount) (will be merged)")
                    }
                }

                Section("Sources in this backup") {
                    ForEach(report.sources, id: \.sourceId) { source in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(source.name)
                                    .foregroundStyle(YomiTheme.onSurface)
                                Text("\(source.mangaCount) manga")
                                    .font(.caption2)
                                    .foregroundStyle(YomiTheme.onSurfaceVariant)
                            }
                            Spacer()
                            Image(systemName: source.isAvailable ? "checkmark.circle.fill" : "questionmark.circle")
                                .foregroundStyle(source.isAvailable ? YomiTheme.secondary : YomiTheme.error)
                        }
                    }
                }

                if !report.missingSources.isEmpty {
                    Section("Missing sources") {
                        Text("These sources have no iOS provider yet. Their manga are imported and preserved, but chapters can't be refreshed until a native provider exists. Nothing is deleted.")
                            .font(.footnote)
                            .foregroundStyle(YomiTheme.onSurfaceVariant)
                    }
                }

                if !report.skippedSections.isEmpty {
                    Section("Not applied on iOS") {
                        ForEach(report.skippedSections, id: \.self) { section in
                            Text(section)
                                .font(.footnote)
                                .foregroundStyle(YomiTheme.onSurfaceVariant)
                        }
                    }
                }

                Section {
                    Button("Apply import") {
                        showConfirmApply = true
                    }
                    .foregroundStyle(YomiTheme.primary)
                    Button("Discard", role: .destructive) {
                        viewModel.discard()
                    }
                }
            }

            if let result = viewModel.importResult {
                Section("Import complete") {
                    LabeledContent("Manga added", value: "\(result.addedManga)")
                    LabeledContent("Manga merged", value: "\(result.mergedManga)")
                    LabeledContent("Chapters added", value: "\(result.addedChapters)")
                    LabeledContent("Categories added", value: "\(result.addedCategories)")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(YomiTheme.background)
        .navigationTitle("Backup & restore")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.data, .gzip],
            allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let url = urls.first {
                Task { await viewModel.handleImport(url: url, environment: environment) }
            }
        }
        .fileExporter(
            isPresented: $showExporter,
            document: exportDocument,
            contentType: .data,
            defaultFilename: "yomi-backup.proto.gz"
        ) { _ in }
        .confirmationDialog(
            "Apply this import?",
            isPresented: $showConfirmApply,
            titleVisibility: .visible
        ) {
            Button("Apply") {
                Task { await viewModel.confirmImport(environment: environment) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Existing library data is never deleted. Manga already in your library are merged.")
        }
    }
}

// MARK: - Export document

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
