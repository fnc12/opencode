import SwiftUI
import Observation

/// Reading-mode collapse of the question dock, driven continuously from the
/// message list's scroll position: 0 = at the newest message (full dock),
/// 1 = deep in history (one-line pill). A reference `@Observable` so the UIKit
/// controller can push a fresh value every scroll frame and only the dock
/// re-renders — the keyboard-hosted composer accessory is never rebuilt.
@MainActor @Observable final class ReadingMode {
    var collapseProgress: CGFloat = 0
}

/// A pending question from the agent, shown above the composer. Each question is
/// single- or multi-select; the reply is the selected labels per question.
struct QuestionDock: View {
    let request: QuestionRequest
    /// True while the real keyboard is up: the dock shares the input accessory
    /// with it, and accessory + keyboard must fit the screen — so the questions
    /// viewport shrinks instead of wedging the keyboard presentation. Changing
    /// only this number keeps the view structure (and the composer's focus)
    /// stable.
    var compact: Bool = false
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
    private static let compactQuestionsHeight: CGFloat = 120

    private var maxHeight: CGFloat { compact ? Self.compactQuestionsHeight : Self.maxQuestionsHeight }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Question", systemImage: "questionmark.circle.fill")
                .font(.caption.bold())
                .foregroundStyle(.blue)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(request.questions) { question in
                            VStack(alignment: .leading, spacing: 6) {
                                if !question.header.isEmpty {
                                    Text(question.header).font(.caption.bold()).foregroundStyle(.secondary)
                                }
                                Text(question.question).font(.callout)

                                ForEach(question.options) { option in
                                    Button {
                                        toggle(question, option.label)
                                        advance(after: question, proxy: proxy)
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
                            .id(question.id)
                        }
                    }
                    .background(GeometryReader { geo in
                        Color.clear.preference(key: QuestionsHeightKey.self, value: geo.size.height)
                    })
                }
                .frame(height: min(max(contentHeight, 1), maxHeight))
                .onPreferenceChange(QuestionsHeightKey.self) { contentHeight = $0 }
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

    /// After answering a single-select question, auto-scroll to the next
    /// unanswered one — otherwise, with several questions in the scroll
    /// viewport, it's not obvious why Submit is still disabled. Multi-select
    /// questions don't auto-advance (the user may still be picking options).
    private func advance(after question: QuestionItem, proxy: ScrollViewProxy) {
        guard !question.allowsMultiple else { return }
        guard let next = request.questions.first(where: {
            $0.id != question.id && (selections[$0.id]?.isEmpty ?? true)
        }) else { return } // all answered — Submit is enabled now
        withAnimation { proxy.scrollTo(next.id, anchor: .top) }
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

/// The one-line form the question dock collapses into while the user reads
/// history — it stops the full dock from covering the transcript. Tapping it
/// returns to the newest message, where the full dock lives.
struct QuestionPill: View {
    let count: Int

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "questionmark.circle.fill")
            Text("Question from the agent (\(count))")
            Spacer()
            Image(systemName: "chevron.down")
        }
        .font(.caption)
        .padding(.horizontal, 14).padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Color.blue.opacity(0.12))
        .foregroundStyle(.blue)
    }
}

/// Wraps `QuestionDock` so it collapses CONTINUOUSLY into `QuestionPill` as the
/// user scrolls up into history — the collapse tracks `reading.collapseProgress`
/// (0 = newest/full, 1 = deep-in-history/pill) rather than snapping at a fixed
/// threshold, so the dock slides down into the pill in step with the finger.
///
/// The dock and the pill are BOTH always in the view tree; only their height and
/// opacity animate. That keeps the accessory's structure constant, so the
/// keyboard-hosted composer is never re-created by a view-tree swap (the trap
/// that drops focus + dismisses the keyboard — see the frozen-phone fix). While
/// the real keyboard is up (`compact`) the collapse is pinned open: the dock
/// stays put (shrunk to share the screen with the keyboard) and the pill never
/// shows, because scrolling to read history happens with the keyboard down.
struct CollapsibleQuestionDock: View {
    let request: QuestionRequest
    /// The live collapse driver, updated from the list's scroll position.
    let reading: ReadingMode
    /// True while the real keyboard is up — the collapse is disabled then.
    var compact: Bool = false
    let onReply: ([[String]]) -> Void
    let onReject: () -> Void
    /// Pill tap: return to the newest message where the full dock lives.
    let onExpand: () -> Void

    /// The dock's natural (uncollapsed) height, measured from its own layout so
    /// the collapse can interpolate down to the pill without a magic number.
    @State private var fullHeight: CGFloat = 0
    /// The pill's laid-out height (one line + vertical padding) — the collapse
    /// floor.
    private static let pillHeight: CGFloat = 37

    var body: some View {
        // Keyboard up ⇒ no collapse (reading history happens keyboard-down).
        let p = compact ? 0 : reading.collapseProgress
        // Scrub the height from the measured full dock down to the pill; before
        // the first measurement (fullHeight 0) let the content self-size.
        let height: CGFloat? = fullHeight > 0
            ? Self.pillHeight + (fullHeight - Self.pillHeight) * (1 - p)
            : nil
        ZStack(alignment: .bottom) {
            // Both present through the crossfade so the collapse is continuous;
            // each drops out only at its own vanishing end (already at ~0 opacity),
            // which also removes it from the accessibility tree — `.accessibilityHidden`
            // doesn't reach into the separate input-accessory window the dock is
            // hosted in, but tree membership does. Only this dock's OWN subtree
            // toggles; the composer is a sibling in the outer bar, never touched,
            // so no composer teardown (unlike the old bar-level pill/dock swap).
            if p < 0.995 {
                QuestionDock(request: request, compact: compact, onReply: onReply, onReject: onReject)
                    // Render at natural height regardless of the collapsed outer
                    // frame, so it CLIPS (slides down) instead of squishing — and so
                    // the measured height is the true full height even mid-collapse.
                    .fixedSize(horizontal: false, vertical: true)
                    .background(GeometryReader { geo in
                        Color.clear.preference(key: DockFullHeightKey.self, value: geo.size.height)
                    })
                    .opacity(1 - p)
                    .allowsHitTesting(p < 0.5)
            }
            if p > 0.005 {
                Button(action: onExpand) { QuestionPill(count: request.questions.count) }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("question.pill")
                    .opacity(p)
                    .allowsHitTesting(p >= 0.5)
            }
        }
        .frame(height: height, alignment: .bottom)
        .clipped()
        // Keep the last real measurement: the dock leaves the tree at full collapse,
        // dropping the preference to 0 — ignoring that stops the height snapping to
        // full for a frame when the user scrolls back and the dock re-appears.
        .onPreferenceChange(DockFullHeightKey.self) { if $0 > 0 { fullHeight = $0 } }
    }
}

/// Reports the question dock's natural (uncollapsed) height so `CollapsibleQuestionDock`
/// can interpolate the collapse down to the pill.
private struct DockFullHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}
