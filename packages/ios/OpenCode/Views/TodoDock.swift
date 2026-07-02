import SwiftUI

/// The agent's task list shown above the composer — a collapsible checklist
/// with progress, so you can watch the plan advance from the phone.
struct TodoDock: View {
    let todos: [TodoItem]
    @State private var expanded = true

    var body: some View {
        let done = todos.filter(\.done).count
        VStack(alignment: .leading, spacing: 6) {
            Button { withAnimation(.easeInOut(duration: 0.15)) { expanded.toggle() } } label: {
                HStack(spacing: 6) {
                    Image(systemName: "checklist")
                    Text("Tasks \(done)/\(todos.count)").fontWeight(.semibold)
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            if expanded {
                ForEach(todos) { todo in
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: icon(todo.status))
                            .foregroundStyle(color(todo.status))
                            .font(.caption)
                        Text(todo.content)
                            .font(.caption)
                            .strikethrough(todo.done, color: .secondary)
                            .foregroundStyle(todo.done ? Color.secondary : .primary)
                        Spacer(minLength: 0)
                    }
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
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
