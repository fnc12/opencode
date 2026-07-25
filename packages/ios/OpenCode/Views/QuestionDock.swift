import SwiftUI

/// A pending question from the agent, shown above the composer. Each question is
/// single- or multi-select; the reply is the selected labels per question.
struct QuestionDock: View {
    let request: QuestionRequest
    /// Called with one array of selected labels per question.
    let onReply: ([[String]]) -> Void
    let onReject: () -> Void

    @State private var selections: [String: Set<String>] = [:]
    @State private var contentHeight: CGFloat = 0

    /// The dock lives inside the composer's input accessory, which sizes to its
    /// content with NO outer bound — a real multi-question request (measured:
    /// 3 questions / 11 options with long descriptions) grew it past the whole
    /// screen, shoving Skip/Submit off-screen with no way out. The questions
    /// therefore scroll inside a bounded viewport (height-hugging for small
    /// requests) while the action row below stays reachable, always.
    private static let maxQuestionsHeight: CGFloat = 280

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Question", systemImage: "questionmark.circle.fill")
                .font(.caption.bold())
                .foregroundStyle(.blue)

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(request.questions) { question in
                        VStack(alignment: .leading, spacing: 6) {
                            if !question.header.isEmpty {
                                Text(question.header).font(.caption.bold()).foregroundStyle(.secondary)
                            }
                            Text(question.question).font(.callout)

                            ForEach(question.options) { option in
                                Button { toggle(question, option.label) } label: {
                                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                                        Image(systemName: isSelected(question, option.label) ? "checkmark.circle.fill" : "circle")
                                            .foregroundStyle(isSelected(question, option.label) ? .blue : .secondary)
                                        VStack(alignment: .leading, spacing: 1) {
                                            Text(option.label).foregroundStyle(.primary)
                                            if !option.description.isEmpty {
                                                Text(option.description).font(.caption2).foregroundStyle(.secondary)
                                            }
                                        }
                                        Spacer()
                                    }
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier(option.label)
                            }
                        }
                    }
                }
                .background(GeometryReader { geo in
                    Color.clear.preference(key: QuestionsHeightKey.self, value: geo.size.height)
                })
            }
            .frame(height: min(max(contentHeight, 1), Self.maxQuestionsHeight))
            .onPreferenceChange(QuestionsHeightKey.self) { contentHeight = $0 }

            HStack {
                Button(role: .destructive) { onReject() } label: {
                    Text("Skip")
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("question.reject")

                Spacer()

                Button { onReply(answers) } label: {
                    Text("Submit")
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canSubmit)
                .accessibilityIdentifier("question.submit")
            }
        }
        .padding(12)
        .background(.blue.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.blue.opacity(0.3)))
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }

    private func isSelected(_ question: QuestionItem, _ label: String) -> Bool {
        selections[question.id]?.contains(label) ?? false
    }

    private func toggle(_ question: QuestionItem, _ label: String) {
        var set = selections[question.id] ?? []
        if question.allowsMultiple {
            if set.contains(label) { set.remove(label) } else { set.insert(label) }
        } else {
            set = [label] // radio
        }
        selections[question.id] = set
    }

    private var answers: [[String]] {
        request.questions.map { Array(selections[$0.id] ?? []) }
    }

    private var canSubmit: Bool {
        request.questions.allSatisfy { !(selections[$0.id]?.isEmpty ?? true) }
    }
}

/// Reports the questions content's natural height so the scroll viewport can
/// hug small requests instead of always reserving the maximum.
private struct QuestionsHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}
