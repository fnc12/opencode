import SwiftUI

/// A tool currently executing inside an unfinished assistant turn.
struct RunningTool: Equatable {
    let id: String        // part id
    let name: String      // tool name (bash, read, question, …)
    let title: String?    // server-provided title or input hint
    let startedMS: Double?
}

/// Extracts the live "background processes" from the conversation: tool parts
/// with `status == running` in assistant messages that haven't completed.
/// Long tools (a docker pull, a firmware build) produce no chat text for
/// minutes — without this the session reads as stuck.
enum RunningTools {
    static func extract(_ messages: [MessageWithParts]) -> [RunningTool] {
        var out: [RunningTool] = []
        for message in messages {
            guard case .assistant(let info) = message.info, info.time.completed == nil else { continue }
            for part in message.parts {
                guard case .tool(let tool)? = part.content, tool.state.status == "running" else { continue }
                out.append(RunningTool(
                    id: part.id,
                    name: tool.tool,
                    title: tool.state.title ?? ToolDisplay.describe(tool).1,
                    startedMS: tool.state.time?.start))
            }
        }
        return out
    }
}

/// One-line strip above the composer: spinner + what's running + for how long.
/// Shown ONLY while something actually runs — its absence is the signal that
/// the agent is generating text, not waiting on a process.
struct RunningToolsPill: View {
    let tools: [RunningTool]

    var body: some View {
        // Re-render every 10s so the elapsed time ticks without the accessory
        // being rebuilt from outside.
        TimelineView(.periodic(from: .now, by: 10)) { context in
            HStack(spacing: 8) {
                ProgressView().controlSize(.mini)
                Text(label(now: context.date))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 0)
                if tools.count > 1 {
                    Text("\(tools.count)")
                        .fontWeight(.semibold)
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(Color.secondary.opacity(0.18), in: Capsule())
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 14).padding(.vertical, 6)
            .frame(maxWidth: .infinity)
            .background(Color.secondary.opacity(0.08))
        }
        .accessibilityIdentifier("session.runningTools")
    }

    private func label(now: Date) -> String {
        guard let first = tools.first else { return "" }
        // The question tool "runs" while the agent waits for the user — say so.
        if first.name == "question" { return "waiting for your answer" }
        var parts: [String] = [first.name]
        if let title = first.title, !title.isEmpty { parts.append(title) }
        if let started = first.startedMS {
            let seconds = max(0, now.timeIntervalSince1970 - started / 1000)
            parts.append(seconds < 60 ? "\(Int(seconds))s" : "\(Int(seconds / 60))m")
        }
        return parts.joined(separator: " · ")
    }
}
