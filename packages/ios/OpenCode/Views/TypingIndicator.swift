import SwiftUI

/// Three dots that bounce in sequence — the "the agent is thinking" cue shown
/// where the reply will appear (bottom-left of the transcript), before any text
/// has streamed in. Same idea as Claude's typing bubble.
///
/// Each dot runs the same ease-in-out bounce, offset by a per-dot delay so the
/// motion reads as a smooth travelling wave rather than three dots pulsing in
/// lockstep. Driven off a single `animating` flag flipped on appear, so SwiftUI
/// owns the repeat/interpolation (no manual phase math to look janky).
struct TypingIndicator: View {
    @State private var animating = false

    private let dotCount = 3
    private let period = 0.6      // one up-down bounce
    private let stagger = 0.18    // delay between adjacent dots

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<dotCount, id: \.self) { i in
                Circle()
                    .fill(Color.secondary)
                    .frame(width: 7, height: 7)
                    .scaleEffect(animating ? 1.0 : 0.55)
                    .opacity(animating ? 1.0 : 0.35)
                    .animation(
                        .easeInOut(duration: period)
                            .repeatForever(autoreverses: true)
                            .delay(Double(i) * stagger),
                        value: animating)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: Capsule())
        .onAppear { animating = true }
        .accessibilityLabel("Thinking")
    }
}
