import SwiftUI
import YomiKit

struct SettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @State private var defaultReadingMode: ReadingMode = .webtoon
    @State private var downloadOnlyOverWiFi = false
    @State private var notifyDownloadCompletion = true

    var body: some View {
        NavigationStack {
            List {
                Section("Reader") {
                    Picker("Default reading mode", selection: $defaultReadingMode) {
                        Text("Webtoon (long strip)").tag(ReadingMode.webtoon)
                        Text("Left to right").tag(ReadingMode.leftToRight)
                        Text("Right to left").tag(ReadingMode.rightToLeft)
                        Text("Vertical (paged)").tag(ReadingMode.vertical)
                        Text("Continuous vertical").tag(ReadingMode.continuousVertical)
                    }
                    .tint(YomiTheme.primary)
                }

                Section("Appearance") {
                    HStack {
                        Text("Theme")
                        Spacer()
                        Text("Yomi dark")
                            .foregroundStyle(YomiTheme.onSurfaceVariant)
                    }
                    HStack {
                        Text("Accent")
                        Spacer()
                        Circle()
                            .fill(YomiTheme.primary)
                            .frame(width: 16, height: 16)
                    }
                }

                Section("Downloads") {
                    Toggle("Download only on Wi-Fi", isOn: $downloadOnlyOverWiFi)
                        .tint(YomiTheme.primary)
                }

                Section("Notifications") {
                    Toggle("Notify when downloads finish", isOn: $notifyDownloadCompletion)
                        .tint(YomiTheme.primary)
                }

                Section("Data") {
                    NavigationLink {
                        BackupView()
                    } label: {
                        Label("Backup & restore", systemImage: "arrow.triangle.2.circlepath")
                    }
                    NavigationLink {
                        TrackingView()
                    } label: {
                        Label("Trackers", systemImage: "chart.bar.fill")
                    }
                    Button {
                        Task {
                            await environment.pipeline.clearMemory()
                            try? await environment.pipeline.clearDisk()
                        }
                    } label: {
                        Label("Clear image cache", systemImage: "trash")
                    }
                }

                Section {
                    NavigationLink {
                        AboutView()
                    } label: {
                        Label("About Yomi iOS", systemImage: "info.circle")
                    }
                } footer: {
                    Text("Yomi iOS \(appVersion) — a native port of the Yomi manga reader.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(YomiTheme.background)
            .navigationTitle("Settings")
            .task {
                let prefs = await environment.preferences.preferences
                defaultReadingMode = prefs.defaultReadingMode == .default ? .webtoon : prefs.defaultReadingMode
                downloadOnlyOverWiFi = prefs.downloadOnlyOverWiFi
                notifyDownloadCompletion = prefs.notifyDownloadCompletion
            }
            .onChange(of: defaultReadingMode) { _, value in
                Task { try? await environment.preferences.update { $0.defaultReadingMode = value } }
            }
            .onChange(of: downloadOnlyOverWiFi) { _, value in
                Task { try? await environment.preferences.update { $0.downloadOnlyOverWiFi = value } }
            }
            .onChange(of: notifyDownloadCompletion) { _, value in
                Task { try? await environment.preferences.update { $0.notifyDownloadCompletion = value } }
            }
        }
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        return version
    }
}

// MARK: - About (honest platform limitations)

struct AboutView: View {
    var body: some View {
        List {
            Section("Yomi") {
                LabeledContent("App", value: "Yomi manga reader")
                LabeledContent("Platform", value: "Native SwiftUI")
                LabeledContent("Core", value: "YomiKit (pure Swift)")
            }
            Section("Source providers") {
                Text("Android APK extensions cannot run on iOS: apps may not load downloaded executable code, and iOS has no APK installer or JVM/Dalvik runtime. Yomi iOS instead ships compiled, signed source providers behind a strict trust boundary — starting with the deterministic Yomi Sample Source.")
                    .font(.footnote)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
                Text("No remote code is ever downloaded or executed. See IOS_COMPATIBILITY.md in the repository for the full compatibility matrix and the roadmap for additional sources.")
                    .font(.footnote)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
            }
            Section("Backups") {
                Text("Yomi iOS imports and exports the same gzipped Protobuf backup format as the Android app (Mihon/Tachiyomi-compatible). Android-only sections such as app preferences and APK extension stores are listed in the import report instead of being silently dropped.")
                    .font(.footnote)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
            }
        }
        .scrollContentBackground(.hidden)
        .background(YomiTheme.background)
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }
}
