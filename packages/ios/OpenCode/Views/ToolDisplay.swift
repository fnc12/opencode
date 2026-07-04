import Foundation

/// Compact, web-client-style presentation of a message's non-text parts: a verb
/// label plus a concise target (file / command / pattern) and an optional badge
/// (diff counts, match count). Pure value logic so it can be unit-tested against
/// real session data, mirroring `getToolInfo()` in the OpenCode web client
/// (packages/ui/src/components/message-part.tsx).
enum ToolDisplay {
    /// The verb shown for a tool, matching the web client's English labels.
    static func label(for tool: String) -> String {
        switch tool.lowercased() {
        case "read":               return "Read"
        case "list":               return "List"
        case "glob":               return "Glob"
        case "grep":               return "Grep"
        case "bash", "shell":      return "Shell"
        case "edit":               return "Edit"
        case "write":              return "Write"
        case "patch", "apply_patch": return "Patch"
        case "webfetch":           return "Webfetch"
        case "websearch":          return "Web Search"
        case "task":               return "Agent"
        case "todowrite", "todo":  return "To-dos"
        case "question":           return "Questions"
        default:                   return tool
        }
    }

    /// A tool's verb plus a one-line target + badge, e.g.
    /// `("Edit", "ast_query.h  +5 −4")`, `("Grep", "Foo|Bar  no matches")`.
    static func describe(_ tool: ToolContent) -> (label: String, detail: String?) {
        let title = (tool.state.title?.isEmpty == false) ? tool.state.title : nil
        let meta = tool.state.metadata

        func input(_ key: String) -> String? {
            guard let v = tool.state.input?[key]?.value else { return nil }
            let s = "\(v)"
            return s.isEmpty ? nil : s
        }

        let label = label(for: tool.tool)
        var detail: String?

        switch tool.tool.lowercased() {
        case "read":
            // Web shows just the filename (getFilename(input.filePath)).
            detail = base(title ?? input("filePath"))
        case "edit", "write", "patch", "apply_patch":
            detail = base(title ?? input("filePath"))
            if let badge = diffBadge(meta?.filediff) { detail = join(detail, badge) }
        case "bash", "shell":
            detail = title ?? input("description") ?? input("command")
            if let exit = meta?.exit, exit != 0 { detail = join(detail, "exit \(exit)") }
        case "grep":
            detail = title ?? input("pattern")
            if let n = meta?.matches {
                detail = join(detail, n == 0 ? "no matches" : "\(n) match\(n == 1 ? "" : "es")")
            }
        case "glob":
            detail = title ?? input("pattern")
        case "list":
            detail = base(title ?? input("path"))
        case "webfetch":
            detail = title ?? input("url")
        case "websearch":
            detail = title ?? input("query")
        case "todowrite", "todo":
            detail = todoRatio(meta?.todos) ?? title
        case "task", "question":
            detail = title
        default:
            detail = title
        }
        return (label, detail)
    }

    /// A tool's output stripped of OpenCode's LLM-facing XML wrapper, so the
    /// detail screen shows the real content (code, listing, …) instead of raw
    /// `<path>…</path><type>…</type><content>…</content>` tags — matching the web
    /// client, which renders the inner content, not the envelope.
    static func cleanOutput(_ tool: ToolContent) -> String? {
        guard let output = tool.state.output?.trimmingCharacters(in: .whitespacesAndNewlines),
              !output.isEmpty else { return nil }
        // read wraps a file as <content>…</content> and a directory as
        // <entries>…</entries> (both after <path>/<type>). Show the inner payload.
        for tag in ["content", "entries"] {
            if let inner = between(output, open: "<\(tag)>", close: "</\(tag)>") {
                return inner.trimmingCharacters(in: .newlines)
            }
        }
        return output
    }

    /// Text strictly between the first `open` and the following `close` marker.
    private static func between(_ s: String, open: String, close: String) -> String? {
        guard let o = s.range(of: open),
              let c = s.range(of: close, range: o.upperBound..<s.endIndex) else { return nil }
        return String(s[o.upperBound..<c.lowerBound])
    }

    /// "+N −M" badge from an edit/write file diff, or nil when both are zero.
    /// Uses a real minus sign (U+2212), matching the web client's DiffChanges.
    static func diffBadge(_ diff: FileDiff?) -> String? {
        guard let diff, (diff.additions ?? 0) != 0 || (diff.deletions ?? 0) != 0 else { return nil }
        return "+\(diff.additions ?? 0) −\(diff.deletions ?? 0)"
    }

    /// "completed/total" for a todowrite, mirroring the web client's "X/Y".
    static func todoRatio(_ todos: [MetaTodo]?) -> String? {
        guard let todos, !todos.isEmpty else { return nil }
        let done = todos.filter { $0.status == "completed" }.count
        return "\(done)/\(todos.count)"
    }

    /// Last path component, matching the web client's getFilename helper.
    static func base(_ path: String?) -> String? {
        guard let path, !path.isEmpty else { return nil }
        return (path as NSString).lastPathComponent
    }

    private static func join(_ lhs: String?, _ rhs: String) -> String {
        guard let lhs, !lhs.isEmpty else { return rhs }
        return lhs + "  " + rhs
    }
}

/// Compact presentation of a `patch` part — the files changed in one turn. The
/// standalone patch part carries only the file list (no counts), matching what
/// the web client renders without the per-file diff metadata.
enum PatchDisplay {
    static func summary(_ patch: PatchContent) -> String {
        let n = patch.files.count
        return "\(n) file\(n == 1 ? "" : "s")"
    }
}

/// Compact presentation of a `file` part (IDE context attachment): the filename
/// plus the single line / range parsed from the `url` query (`?start=&end=`).
enum FileRefDisplay {
    static func chip(_ part: FileRefContent) -> String {
        let name = part.filename ?? lastComponent(part.url) ?? "file"
        guard let range = lineRange(part.url) else { return name }
        return "\(name):\(range)"
    }

    /// "266" for start==end, "266-280" for a span, nil when no range is encoded.
    static func lineRange(_ url: String?) -> String? {
        guard let url, let comps = URLComponents(string: url) else { return nil }
        let items = comps.queryItems ?? []
        let start = items.first { $0.name == "start" }?.value
        let end = items.first { $0.name == "end" }?.value
        guard let start, !start.isEmpty else { return nil }
        if let end, !end.isEmpty, end != start { return "\(start)-\(end)" }
        return start
    }

    private static func lastComponent(_ url: String?) -> String? {
        guard let url, let comps = URLComponents(string: url), !comps.path.isEmpty else { return nil }
        return (comps.path as NSString).lastPathComponent
    }
}
