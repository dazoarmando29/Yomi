import SwiftUI

/// Yomi dark theme — tokens ported 1:1 from the Android design system
/// (presentation-core/src/main/res/values-night/colors_tachiyomi.xml):
/// dark charcoal surfaces with violet accents and cyan secondary.
enum YomiTheme {
    // Surfaces
    static let background = Color(hex: 0x111319)
    static let surface = Color(hex: 0x111319)
    static let surfaceLowest = Color(hex: 0x0C0E14)
    static let surfaceLow = Color(hex: 0x191B22)
    static let surfaceContainer = Color(hex: 0x1E1F26)
    static let surfaceHigh = Color(hex: 0x282A30)
    static let surfaceHighest = Color(hex: 0x33343B)

    // Content
    static let onSurface = Color(hex: 0xE2E2EB)
    static let onSurfaceVariant = Color(hex: 0xCBC3D7)
    static let outline = Color(hex: 0x958EA0)
    static let outlineVariant = Color(hex: 0x494454)

    // Accents
    static let primary = Color(hex: 0xD0BCFF)
    static let onPrimary = Color(hex: 0x3C0091)
    static let primaryContainer = Color(hex: 0xA078FF)
    static let secondary = Color(hex: 0x4CD7F6)
    static let tertiary = Color(hex: 0xADC6FF)
    static let error = Color(hex: 0xFFB4AB)

    // Reader chrome
    static let readerBackground = Color.black
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}

// MARK: - Reusable components

/// Rounded card container used across library/browses screens.
struct YomiCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
            .background(YomiTheme.surfaceContainer)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(YomiTheme.outlineVariant.opacity(0.5), lineWidth: 1)
            )
    }
}

/// Pill chip (categories, genres, filters).
struct YomiChip: View {
    let text: String
    var selected: Bool = false

    var body: some View {
        Text(text)
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(selected ? YomiTheme.primaryContainer : YomiTheme.surfaceHigh)
            .foregroundStyle(selected ? YomiTheme.onPrimary : YomiTheme.onSurface)
            .clipShape(Capsule())
    }
}

/// Unread count badge for chapter rows and covers.
struct UnreadBadge: View {
    let count: Int

    var body: some View {
        if count > 0 {
            Text("\(count)")
                .font(.caption2.bold())
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(YomiTheme.primary)
                .foregroundStyle(YomiTheme.onPrimary)
                .clipShape(Capsule())
                .accessibilityLabel("\(count) unread chapters")
        }
    }
}

/// Standard progress bar for downloads/reading.
struct YomiProgressBar: View {
    let fraction: Double
    var tint: Color = YomiTheme.primary

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(YomiTheme.surfaceHigh)
                Capsule()
                    .fill(tint)
                    .frame(width: max(4, proxy.size.width * min(1, max(0, fraction))))
            }
        }
        .frame(height: 5)
    }
}

/// Error banner with retry affordance.
struct ErrorBanner: View {
    let message: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(YomiTheme.error)
            Text(message)
                .font(.footnote)
                .foregroundStyle(YomiTheme.onSurface)
            Spacer()
        }
        .padding(12)
        .background(YomiTheme.surfaceHigh)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
