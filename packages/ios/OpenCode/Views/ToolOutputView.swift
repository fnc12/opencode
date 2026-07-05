import SwiftUI

/// Per-tool rendering of a tool result on the detail screen, mirroring the web
/// client's dedicated components: edit/write show a colored diff, todowrite a
/// checklist, everything else the clean (envelope-stripped) output as
/// selectable monospaced text.
struct ToolOutputView: View {
    let tool: ToolContent

    var body: some View {
        switch tool.tool.lowercased() {
        case "edit", "write", "patch", "apply_patch":
            if let diff = tool.state.metadata?.diff, !diff.isEmpty {
                SelectableText(attributed: DiffRenderer.attributed(diff))
            } else {
                plainOutput
            }
        case "todowrite", "todo":
            let todos = tool.state.metadata?.todos ?? []
            if todos.isEmpty { plainOutput } else { TodoChecklist(todos: todos) }
        default:
            plainOutput
        }
    }

    @ViewBuilder private var plainOutput: some View {
        if let out = ToolDisplay.cleanOutput(tool), !out.isEmpty {
            SelectableText(text: out, monospaced: true)
        }
    }
}

/// A `todowrite`'s items as a checklist — done items checked + struck through,
/// like the web client.
struct TodoChecklist: View {
    let todos: [MetaTodo]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(todos.enumerated()), id: \.offset) { _, todo in
                let done = todo.status == "completed"
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: done ? "checkmark.circle.fill" : (todo.status == "in_progress" ? "circle.dotted" : "circle"))
                        .foregroundStyle(done ? .green : .secondary)
                        .font(.callout)
                    Text(todo.content ?? "")
                        .font(.callout)
                        .strikethrough(done, color: .secondary)
                        .foregroundStyle(done ? .secondary : .primary)
                    Spacer(minLength: 0)
                }
            }
        }
    }
}
