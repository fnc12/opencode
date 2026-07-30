import SwiftUI

/// Full detail of one message on its own screen (à la Slack): the whole content
/// broken into blocks — the "thinking" set apart from the actual answer text and
/// from each tool's output — every block character-selectable/copyable.
struct MessageDetailView: View {
    let message: MessageWithParts
    var onClose: () -> Void = {}
    @Environment(\.colorScheme) private var colorScheme
    /// Thinking blocks expanded by the user. Collapsed (3 lines) by default:
    /// people open this screen for the ANSWER or a tool's output, and a long
    /// chain of thought otherwise buries it under a screenful of scrolling.
    @State private var expandedThinking: Set<String> = []

    private enum Block: Identifiable {
        case thinking(String)
        case text(String)
        case code(NSAttributedString)
        case tool(title: String, tool: ToolContent)
        case note(String)
        var id: String {
            switch self {
            case .thinking(let t): return "think:\(t.hashValue)"
            case .text(let t): return "text:\(t.hashValue)"
            case .code(let s): return "code:\(s.string.hashValue)"
            case .tool(let title, let t): return "tool:\(title.hashValue):\(t.callID.hashValue)"
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
                            let expanded = expandedThinking.contains(block.id)
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Label("Thinking", systemImage: "brain")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.secondary)
                                    Spacer()
                                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.secondary)
                                }
                                .contentShape(Rectangle())
                                .onTapGesture { toggleThinking(block.id) }
                                if expanded {
                                    // Selectable full text once opened.
                                    SelectableText(attributed: MarkdownRenderer.attributed(
                                        text, font: .preferredFont(forTextStyle: .callout), color: .secondaryLabel))
                                } else {
                                    // A 3-line teaser; tap anywhere to expand.
                                    Text(text)
                                        .font(.callout)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(3)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .contentShape(Rectangle())
                                        .onTapGesture { toggleThinking(block.id) }
                                }
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(uiColor: .secondarySystemBackground))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .accessibilityIdentifier("detail.thinking")

                        case .text(let text):
                            SelectableText(attributed: MarkdownRenderer.attributed(
                                text, font: .preferredFont(forTextStyle: .callout), color: .label))
                                .frame(maxWidth: .infinity, alignment: .leading)

                        case .code(let code):
                            CodeBlockRepresentable(code: code)
                                .frame(maxWidth: .infinity, alignment: .leading)

                        case .tool(let title, let tool):
                            VStack(alignment: .leading, spacing: 6) {
                                Text(title)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.tint)
                                ToolOutputView(tool: tool)
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
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { onClose() } }
            }
        }
    }

    private var title: String {
        message.info.role == "user" ? "You" : "Assistant"
    }

    private func toggleThinking(_ id: String) {
        withAnimation(.easeInOut(duration: 0.2)) {
            if expandedThinking.contains(id) {
                expandedThinking.remove(id)
            } else {
                expandedThinking.insert(id)
            }
        }
    }

    private var blocks: [Block] {
        var out: [Block] = []
        for part in message.parts where part.isVisible {
            switch part.content {
            case .text(let text) where !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty:
                out.append(contentsOf: splitText(text))
            case .reasoning(let text) where !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty:
                out.append(.thinking(text))
            case .tool(let tool):
                let (label, detail) = ToolDisplay.describe(tool)
                let title = detail.map { "\(label)  \($0)" } ?? label
                out.append(.tool(title: title, tool: tool))
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

    /// Splits a text part into text runs and fenced code blocks (```lang … ```).
    private func splitText(_ text: String) -> [Block] {
        var out: [Block] = []
        var buf: [String] = []
        func flush() {
            let joined = buf.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            buf.removeAll()
            if !joined.isEmpty { out.append(.text(joined)) }
        }
        let lines = text.components(separatedBy: "\n")
        var i = 0
        while i < lines.count {
            let trimmed = lines[i].trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") {
                flush()
                let lang = String(trimmed.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                var codeLines: [String] = []
                i += 1
                while i < lines.count, !lines[i].trimmingCharacters(in: .whitespaces).hasPrefix("```") {
                    codeLines.append(lines[i]); i += 1
                }
                if i < lines.count { i += 1 }
                let code = codeLines.joined(separator: "\n")
                let hl = SyntaxHighlighter.attributed(code, language: lang, dark: colorScheme == .dark,
                                                      fontSize: CodeBlockView.fontSize)
                    ?? NSAttributedString(string: code, attributes: [.font: CodeBlockView.font, .foregroundColor: UIColor.label])
                out.append(.code(hl))
                continue
            }
            buf.append(lines[i]); i += 1
        }
        flush()
        return out
    }

    /// Flattened text for the "copy all" convenience button.
    private var plainText: String {
        blocks.compactMap { block in
            switch block {
            case .thinking(let t): return "💭 Thinking\n\n\(t)"
            case .text(let t): return t
            case .code(let s): return s.string
            case .tool(let title, let tool):
                let isEdit = ["edit", "write", "patch", "apply_patch"].contains(tool.tool.lowercased())
                let body = (isEdit ? tool.state.metadata?.diff : nil) ?? ToolDisplay.cleanOutput(tool)
                return body.map { "→ \(title)\n\n\($0)" } ?? "→ \(title)"
            case .note(let n): return n
            }
        }.joined(separator: "\n\n")
    }
}

/// Hosts the shared `CodeBlockView` (highlighted, horizontally-scrollable) in the
/// SwiftUI detail screen, sized to its computed height.
private struct CodeBlockRepresentable: UIViewRepresentable {
    let code: NSAttributedString
    func makeUIView(context: Context) -> CodeBlockView { CodeBlockView(code: code, selectable: true) }
    func updateUIView(_ view: CodeBlockView, context: Context) {}
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: CodeBlockView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? UIScreen.main.bounds.width, height: CodeBlockView.height(for: code))
    }
}
