import SwiftUI

/// Three dots that pulse in sequence — the "the agent is thinking" cue shown
/// where the reply will appear (bottom-left of the transcript), before any text
/// has streamed in. Same idea as Claude's typing bubble.
struct TypingIndicator: View {
    @State private var phase = 0.0

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3) { i in
                Circle()
                    .fill(Color.secondary)
                    .frame(width: 7, height: 7)
                    .opacity(opacity(for: i))
                    .scaleEffect(scale(for: i))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: Capsule())
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: false)) {
                phase = 3
            }
        }
        .accessibilityLabel("Thinking")
    }

    private func opacity(for i: Int) -> Double {
        let d = (phase - Double(i)).truncatingRemainder(dividingBy: 3)
        let x = d < 0 ? d + 3 : d
        return 0.3 + 0.7 * max(0, 1 - abs(x - 0.5) / 1.5)
    }
    private func scale(for i: Int) -> Double {
        0.85 + 0.15 * opacity(for: i)
    }
}
