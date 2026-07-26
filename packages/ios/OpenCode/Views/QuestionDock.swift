import SwiftUI

/// A pending question from the agent. It rides the transcript as its LAST ROW —
/// right after the newest message, scrolling with the content — so it never
/// floats over (and overlaps) the messages the way a fixed bar did.
///
/// A single-question request lays out directly (height-hugging). A multi-question
/// request is shown as SLIDES (one question per screen, à la Claude): dots for
/// progress, swipe between them, and answering a single-select question
/// auto-advances to the next. Skip/Submit stay pinned below, always reachable.
struct QuestionDock: View {
    let request: QuestionRequest
    /// Called with one array of selected labels per question.
    let onReply: ([[String]]) -> Void
    let onReject: () -> Void

    @State private var selections: [String: Set<String>] = [:]
    @State private var page = 0

    private var isMulti: Bool { request.questions.count > 1 }
    /// Fixed height for the slide viewport so navigating between questions doesn't
    /// resize the row (each slide scrolls internally if its options overflow).
    private static let slideHeight: CGFloat = 260

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Question", systemImage: "questionmark.circle.fill")
                    .font(.caption.bold())
                    .foregroundStyle(.blue)
                Spacer()
                if isMulti {
                    Text("\(min(page + 1, request.questions.count)) / \(request.questions.count)")
                        .font(.caption).monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }

            if isMulti {
                TabView(selection: $page) {
                    ForEach(Array(request.questions.enumerated()), id: \.offset) { index, question in
                        ScrollView { questionBody(question) }
                            .tag(index)
                    }
                }
                .frame(height: Self.slideHeight)
                .tabViewStyle(.page(indexDisplayMode: .always))
                .indexViewStyle(.page(backgroundDisplayMode: .interactive))
            } else if let only = request.questions.first {
                questionBody(only)
            }

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

    @ViewBuilder private func questionBody(_ question: QuestionItem) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if !question.header.isEmpty {
                Text(question.header).font(.caption.bold()).foregroundStyle(.secondary)
            }
            Text(question.question).font(.callout)

            ForEach(question.options) { option in
                Button {
                    toggle(question, option.label)
                } label: {
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
        .frame(maxWidth: .infinity, alignment: .leading)
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
        advance(after: question)
    }

    /// After answering a single-select question, slide to the next one — the
    /// Claude-style flow. Multi-select waits (the user may still be picking); they
    /// swipe on when ready.
    private func advance(after question: QuestionItem) {
        guard isMulti, !question.allowsMultiple,
              let idx = request.questions.firstIndex(where: { $0.id == question.id }),
              idx + 1 < request.questions.count else { return }
        withAnimation { page = idx + 1 }
    }

    private var answers: [[String]] {
        request.questions.map { Array(selections[$0.id] ?? []) }
    }

    private var canSubmit: Bool {
        request.questions.allSatisfy { !(selections[$0.id]?.isEmpty ?? true) }
    }
}
