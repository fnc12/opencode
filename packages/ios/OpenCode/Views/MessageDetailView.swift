import SwiftUI

/// Full detail of one message on its own screen (à la Slack): the whole content
/// broken into blocks — the "thinking" set apart from the actual answer text and
/// from each tool's output — every block character-selectable/copyable.
struct MessageDetailView: View {
    let message: MessageWithParts
    @Environment(\.dismiss) private var dismiss

    private enum Block: Identifiable {
        case thinking(String)
        case text(String)
        case tool(title: String, output: String?)
        case note(String)
        var id: String {
            switch self {
            case .thinking(let t): return "think:\(t.hashValue)"
            case .text(let t): return "text:\(t.hashValue)"
            case .tool(let title, let o): return "tool:\(title.hashValue):\(o?.hashValue ?? 0)"
            case .note(let n): return "note:\(n.hashValue)"
            }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(blocks) { block in
                        switch block {
                        case .thinking(let text):
                            VStack(alignment: .leading, spacing: 6) {
                                Label("Thinking", systemImage: "brain")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                SelectableText(text: text, color: .secondaryLabel)
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(uiColor: .secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                        case .text(let text):
                            SelectableText(text: text)
                                .frame(maxWidth: .infinity, alignment: .leading)

                        case .tool(let title, let output):
                            VStack(alignment: .leading, spacing: 6) {
                                Text(title)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.tint)
                                if let output, !output.isEmpty {
                                    SelectableText(text: output, monospaced: true)
                                }
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(uiColor: .tertiarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                        case .note(let note):
                            Text(note).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { UIPasteboard.general.string = plainText } label: {
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

    private var blocks: [Block] {
        var out: [Block] = []
        for part in message.parts where part.isVisible {
            switch part.content {
            case .text(let text) where !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty:
                out.append(.text(text))
            case .reasoning(let text) where !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty:
                out.append(.thinking(text))
            case .tool(let tool):
                let (label, detail) = ToolDisplay.describe(tool)
                let title = detail.map { "\(label)  \($0)" } ?? label
                out.append(.tool(title: title, output: tool.state.output))
            case .patch(let patch):
                out.append(.note("⌥ Patch — \(PatchDisplay.summary(patch))"))
            case .file(let file):
                out.append(.note("📎 \(FileRefDisplay.chip(file))"))
            default:
                break
            }
        }
        return out
    }

    /// Flattened text for the "copy all" convenience button.
    private var plainText: String {
        blocks.compactMap { block in
            switch block {
            case .thinking(let t): return "💭 Thinking\n\n\(t)"
            case .text(let t): return t
            case .tool(let title, let o): return o.map { "→ \(title)\n\n\($0)" } ?? "→ \(title)"
            case .note(let n): return n
            }
        }.joined(separator: "\n\n")
    }
}
