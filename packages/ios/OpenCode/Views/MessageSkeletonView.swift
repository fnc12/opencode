import SwiftUI
import UIKit

/// Placeholder conversation shown during the initial history load instead of a
/// bare spinner — grey bubble rows with a shimmer sweep, so the screen reads as
/// "content is coming" (like Instagram/Slack) rather than "nothing here". Once
/// the newest page lands (usually well under a second, see pagination) this is
/// replaced by the real list.
struct MessageSkeletonView: View {
    /// A fixed, deterministic layout — alternating assistant (wide, left) and
    /// user (narrower, right) rows of varying line counts, so it resembles a real
    /// transcript without looking mechanically uniform.
    private static let rows: [SkeletonRow] = [
        SkeletonRow(fromUser: false, lines: 3, width: 0.82),
        SkeletonRow(fromUser: true, lines: 1, width: 0.5),
        SkeletonRow(fromUser: false, lines: 2, width: 0.7),
        SkeletonRow(fromUser: false, lines: 4, width: 0.85),
        SkeletonRow(fromUser: true, lines: 2, width: 0.55),
        SkeletonRow(fromUser: false, lines: 2, width: 0.66),
    ]

    @State private var shimmer = false

    var body: some View {
        bubbles
            .overlay(shimmerSweep.allowsHitTesting(false))
            // Clip the shimmer sweep to the bubble shapes so it only glints over
            // "content", not the gaps between rows.
            .mask(bubbles)
            .onAppear { withAnimation(.linear(duration: 1.15).repeatForever(autoreverses: false)) { shimmer = true } }
            .accessibilityIdentifier("session.skeleton")
            .accessibilityLabel("Loading messages")
    }

    private var bubbles: some View {
        VStack(alignment: .leading, spacing: 18) {
            ForEach(Array(Self.rows.enumerated()), id: \.offset) { _, row in
                bubble(row)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    // One placeholder bubble: a stack of grey "text lines" inside a rounded card.
    private func bubble(_ row: SkeletonRow) -> some View {
        HStack(spacing: 0) {
            if row.fromUser { Spacer(minLength: 0) }
            VStack(alignment: .leading, spacing: 7) {
                ForEach(0..<row.lines, id: \.self) { line in
                    RoundedRectangle(cornerRadius: 5)
                        .fill(Color.gray.opacity(0.22))
                        .frame(height: 12)
                        // The last line of a paragraph is short, like real wrapped text.
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .scaleEffect(x: line == row.lines - 1 ? 0.6 : 1, anchor: .leading)
                }
            }
            .padding(12)
            .frame(width: UIScreen.main.bounds.width * row.width, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color.gray.opacity(0.12)))
            if !row.fromUser { Spacer(minLength: 0) }
        }
    }

    // A highlight band that sweeps left→right across the bubbles.
    private var shimmerSweep: some View {
        GeometryReader { geo in
            let w = geo.size.width
            LinearGradient(
                colors: [.clear, Color.white.opacity(0.35), .clear],
                startPoint: .leading, endPoint: .trailing)
                .frame(width: w * 0.6)
                .offset(x: shimmer ? w : -w * 0.6)
        }
    }
}

private struct SkeletonRow {
    let fromUser: Bool
    let lines: Int
    /// Fraction of the screen width the bubble spans.
    let width: CGFloat
}
