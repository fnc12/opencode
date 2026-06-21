import SwiftUI

struct ToolPartView: View {
    let tool: ToolContent
    @State private var expanded = false

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            VStack(alignment: .leading, spacing: 8) {
                if let output = tool.state.output, !output.isEmpty {
                    Text(output)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(expanded ? nil : 5)
                }
                if let error = tool.state.error, !error.isEmpty {
                    Text(error)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.red)
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.caption)
                    .foregroundStyle(stateColor)
                Text(tool.state.title ?? tool.tool)
                    .font(.system(.caption, design: .monospaced))
                    .fontWeight(.medium)
                    .lineLimit(1)
                if let duration = tool.state.time?.duration {
                    Text(String(format: "%.1fs", duration / 1000))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .tint(.secondary)
    }

    private var icon: String {
        switch tool.state.status {
        case "completed": "checkmark.circle.fill"
        case "running": "circle.dotted"
        case "error": "xmark.circle.fill"
        case "pending": "clock"
        default: "gearshape"
        }
    }

    private var stateColor: Color {
        switch tool.state.status {
        case "completed": .green
        case "running": .blue
        case "error": .red
        case "pending": .orange
        default: .secondary
        }
    }
}
