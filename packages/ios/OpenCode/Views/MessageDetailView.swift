import SwiftUI

/// Full detail of one message on its own screen (à la Slack): the whole content
/// — text, the complete "thinking", and each tool's input + output — as
/// selectable text you can copy in part or whole. This is where the collapsed
/// inline rows (Thinking, tool rows) expand.
struct MessageDetailView: View {
    let message: MessageWithParts
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(fullText)
                    .font(.callout)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { UIPasteboard.general.string = fullText } label: {
                        Image(systemName: "doc.on.doc")
                    }
                    .accessibilityLabel("Copy all")
                }
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }

    private var title: String {
        message.info.role == "user" ? "You" : "Assistant"
    }

    /// A readable, selectable transcript of the whole message.
    private var fullText: String {
        var out: [String] = []
        for part in message.parts where part.isVisible {
            switch part.content {
            case .text(let text) where !text.isEmpty:
                out.append(text)
            case .reasoning(let text) where !text.isEmpty:
                out.append("💭 Thinking\n\n" + text)
            case .tool(let tool):
                let (label, detail) = ToolDisplay.describe(tool)
                var block = "→ \(label)"
                if let detail, !detail.isEmpty { block += "  \(detail)" }
                if let output = tool.state.output, !output.isEmpty { block += "\n\n" + output }
                out.append(block)
            case .patch(let patch):
                out.append("⌥ Patch — \(PatchDisplay.summary(patch))")
            case .file(let file):
                out.append("📎 \(FileRefDisplay.chip(file))")
            default:
                break
            }
        }
        return out.joined(separator: "\n\n")
    }
}
