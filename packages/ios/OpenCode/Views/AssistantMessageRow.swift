import SwiftUI

struct AssistantMessageRow: View {
    let info: AssistantMessage
    let parts: [MessagePart]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(info.agent)
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundStyle(.green)

                Spacer()

                Text(tokenSummary)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if let error = info.error {
                Text("Error: \(error.displayText)")
                    .font(.callout)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(.orange, in: RoundedRectangle(cornerRadius: 8))
            }

            ForEach(parts) { part in
                PartView(part: part)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }

    private var tokenSummary: String {
        let input = info.tokens.input
        let output = info.tokens.output
        return "\(formatted(input))→\(formatted(output))"
    }

    private func formatted(_ n: Int) -> String {
        if n >= 1000 { return "\(n / 1000)k" }
        return "\(n)"
    }
}
