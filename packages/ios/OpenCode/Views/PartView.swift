import SwiftUI

struct PartView: View {
    let part: MessagePart

    var body: some View {
        switch part.content {
        case .text(let content):
            Text(content)
                .font(.body)
                .textSelection(.enabled)

        case .tool(let tool):
            ToolPartView(tool: tool)

        case .stepStart(let step):
            if let title = step.title {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

        case .stepFinish:
            EmptyView()

        case nil:
            EmptyView()
        }
    }
}
