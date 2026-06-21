import SwiftUI

struct UserMessageRow: View {
    let parts: [MessagePart]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("You")
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundStyle(.blue)

            ForEach(parts) { part in
                if case .text(let content) = part.content {
                    Text(content)
                        .font(.body)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }
}
