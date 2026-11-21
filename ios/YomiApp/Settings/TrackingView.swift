import SwiftUI

/// Tracker integrations. The OAuth authorization-code + PKCE flow is the
/// supported route on iOS; live sync requires user-supplied registered client
/// credentials (never committed, never requested in-app without a configured
/// client id). Until then trackers stay honestly "not configured".
struct TrackingView: View {
    private let trackers: [(name: String, detail: String, status: Status)] = [
        ("MyAnimeList", "OAuth 2.0 authorization code + PKCE", .needsConfiguration),
        ("AniList", "OAuth 2.0 authorization code + PKCE", .needsConfiguration),
        ("Kitsu", "Email + password OAuth", .planned),
        ("Shikimori", "OAuth 2.0", .planned),
    ]

    enum Status {
        case needsConfiguration
        case planned
        case active
    }

    var body: some View {
        List {
            Section {
                ForEach(trackers, id: \.name) { tracker in
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(tracker.name)
                                .foregroundStyle(YomiTheme.onSurface)
                            Text(tracker.detail)
                                .font(.caption)
                                .foregroundStyle(YomiTheme.onSurfaceVariant)
                        }
                        Spacer()
                        statusView(tracker.status)
                    }
                }
            } footer: {
                Text("Tracker sync requires registering an OAuth application with the tracker and providing its client id in a local configuration. Yomi never asks for your tracker password and never embeds account secrets.")
                    .font(.footnote)
            }

            Section("Imported tracking data") {
                Text("Tracking entries restored from backups are preserved as metadata. They sync again once a tracker is configured.")
                    .font(.footnote)
                    .foregroundStyle(YomiTheme.onSurfaceVariant)
            }
        }
        .scrollContentBackground(.hidden)
        .background(YomiTheme.background)
        .navigationTitle("Trackers")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func statusView(_ status: Status) -> some View {
        switch status {
        case .active:
            YomiChip(text: "Active", selected: true)
        case .needsConfiguration:
            YomiChip(text: "Needs configuration")
        case .planned:
            YomiChip(text: "Planned")
        }
    }
}
