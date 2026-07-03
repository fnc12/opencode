import SwiftUI

/// A single compact line above the composer — "✓ Tasks done/total" — tapping it
/// opens the full checklist in a sheet (mobile screens are small, so the list
/// isn't inlined). Fixed height, so it never shoves the composer off-screen.
struct TodoPill: View {
    let todos: [TodoItem]
    let onTap: () -> Void

    var body: some View {
        let done = todos.filter(\.done).count
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: "checklist")
                Text("Tasks \(done)/\(todos.count)").fontWeight(.semibold)
                Spacer()
                Image(systemName: "chevron.up").font(.caption2)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Color(.secondarySystemBackground))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("session.tasks")
    }
}

/// The full task list, shown as a sheet from the session view.
struct TodoSheet: View {
    let todos: [TodoItem]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(todos) { todo in
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: icon(todo.status))
                        .foregroundStyle(color(todo.status))
                    Text(todo.content)
                        .strikethrough(todo.done, color: .secondary)
                        .foregroundStyle(todo.done ? Color.secondary : .primary)
                    Spacer(minLength: 0)
                }
                .font(.callout)
            }
            .navigationTitle("Tasks \(todos.filter(\.done).count)/\(todos.count)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }

    private func icon(_ status: String) -> String {
        switch status {
        case "completed": return "checkmark.circle.fill"
        case "in_progress": return "circle.dotted.circle"
        case "cancelled": return "xmark.circle"
        default: return "circle"
        }
    }
    private func color(_ status: String) -> Color {
        switch status {
        case "completed": return .green
        case "in_progress": return .blue
        case "cancelled": return .secondary
        default: return .secondary
        }
    }
}
