package studio.eugenezakharov.opencode.ui.session

import studio.eugenezakharov.opencode.api.models.PartContent

/**
 * Compact, web-client-style presentation of a message's non-text parts: a verb
 * label plus a concise target (file / command / pattern) and an optional badge
 * (diff counts, match count). Pure logic so it can be unit-tested against real
 * session data — the exact Kotlin twin of the iOS `ToolDisplay`, mirroring
 * `getToolInfo()` in the OpenCode web client.
 */
object ToolDisplay {
    /** The verb shown for a tool, matching the web client's English labels. */
    fun label(tool: String): String = when (tool.lowercase()) {
        "read" -> "Read"
        "list" -> "List"
        "glob" -> "Glob"
        "grep" -> "Grep"
        "bash", "shell" -> "Shell"
        "edit" -> "Edit"
        "write" -> "Write"
        "patch", "apply_patch" -> "Patch"
        "webfetch" -> "Webfetch"
        "websearch" -> "Web Search"
        "task" -> "Agent"
        "todowrite", "todo" -> "To-dos"
        "question" -> "Questions"
        else -> tool
    }

    /**
     * A tool's verb plus a one-line target + badge, e.g.
     * `"Edit" to "ast_query.h  +5 −4"`, `"Grep" to "Foo|Bar  no matches"`.
     */
    fun describe(tool: PartContent.Tool): Pair<String, String?> {
        val title = tool.title?.takeIf { it.isNotEmpty() }
        val meta = tool.metadata
        fun input(key: String) = tool.input[key]?.takeIf { it.isNotEmpty() }

        val label = label(tool.tool)
        val detail: String? = when (tool.tool.lowercase()) {
            "read" -> base(title ?: input("filePath"))
            "edit", "write", "patch", "apply_patch" ->
                join(base(title ?: input("filePath")), diffBadge(meta))
            "bash", "shell" -> {
                val d = title ?: input("description") ?: input("command")
                val exit = meta?.exit
                if (exit != null && exit != 0) join(d, "exit $exit") else d
            }
            "grep" -> {
                val d = title ?: input("pattern")
                val n = meta?.matches
                if (n != null) join(d, if (n == 0) "no matches" else "$n match${if (n == 1) "" else "es"}") else d
            }
            "glob" -> title ?: input("pattern")
            "list" -> base(title ?: input("path"))
            "webfetch" -> title ?: input("url")
            "websearch" -> title ?: input("query")
            "todowrite", "todo" -> todoRatio(meta) ?: title
            "task", "question" -> title
            else -> title
        }
        return label to detail
    }

    /**
     * A tool's output stripped of OpenCode's LLM-facing XML wrapper, so the
     * detail screen shows the real content (code, listing, …) instead of raw
     * `<path>/<type>/<content>` tags — matching the web client and the iOS twin.
     */
    fun cleanOutput(tool: PartContent.Tool): String? {
        val output = tool.output?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return unwrapEnvelope(output) ?: output
    }

    /**
     * OpenCode's `read` tool wraps its result (for the LLM) as
     * `<path>…</path>\n<type>…</type>\n<WRAPPER>\n…payload…\n</WRAPPER>` — a file's
     * `<content>`, a directory's `<entries>`, etc. Return the inner payload of
     * whatever wrapper follows `<type>`, so any current/future wrapper is handled
     * without per-tag special cases (server: packages/opencode/src/tool/read.ts).
     */
    private fun unwrapEnvelope(s: String): String? {
        if (!s.startsWith("<path>")) return null
        val afterType = s.indexOf("</type>").takeIf { it >= 0 } ?: return null
        val rest = s.substring(afterType + "</type>".length).trim()
        if (!rest.startsWith("<")) return null
        val gt = rest.indexOf('>').takeIf { it > 1 } ?: return null
        val name = rest.substring(1, gt)
        if (name.isEmpty() || name.contains(' ') || name.contains('/')) return null
        val close = rest.lastIndexOf("</$name>").takeIf { it > gt } ?: return null
        return rest.substring(gt + 1, close).trim('\n')
    }

    /** "+N −M" badge from an edit/write file diff, or null when both are zero. U+2212 minus. */
    fun diffBadge(meta: studio.eugenezakharov.opencode.api.models.ToolMeta?): String? {
        val a = meta?.additions ?: 0
        val d = meta?.deletions ?: 0
        if (a == 0 && d == 0) return null
        return "+$a −$d"
    }

    /** "completed/total" for a todowrite, mirroring the web client's "X/Y". */
    fun todoRatio(meta: studio.eugenezakharov.opencode.api.models.ToolMeta?): String? {
        val total = meta?.todoTotal ?: return null
        if (total == 0) return null
        return "${meta.todoCompleted ?: 0}/$total"
    }

    /** Last path component, matching the web client's getFilename helper. */
    fun base(path: String?): String? =
        path?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')?.substringAfterLast('\\')

    private fun join(lhs: String?, rhs: String?): String? {
        if (rhs.isNullOrEmpty()) return lhs
        if (lhs.isNullOrEmpty()) return rhs
        return "$lhs  $rhs"
    }
}

/** Compact presentation of a `patch` part — the files changed in one turn. */
object PatchDisplay {
    fun summary(patch: PartContent.Patch): String {
        val n = patch.files.size
        return "$n file${if (n == 1) "" else "s"}"
    }
}

/**
 * Compact presentation of a `file` part (IDE context attachment): the filename
 * plus the line / range parsed from the `url` query (`?start=&end=`).
 */
object FileRefDisplay {
    fun chip(file: PartContent.FileRef): String {
        val name = file.filename ?: lastComponent(file.url) ?: "file"
        val range = lineRange(file.url) ?: return name
        return "$name:$range"
    }

    /** "266" for start==end, "266-280" for a span, null when no range is encoded. */
    fun lineRange(url: String?): String? {
        val query = url?.substringAfter('?', "")?.takeIf { it.isNotEmpty() } ?: return null
        var start: String? = null
        var end: String? = null
        for (pair in query.split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv.size != 2) continue
            when (kv[0]) {
                "start" -> start = kv[1]
                "end" -> end = kv[1]
            }
        }
        val s = start?.takeIf { it.isNotEmpty() } ?: return null
        return if (!end.isNullOrEmpty() && end != s) "$s-$end" else s
    }

    private fun lastComponent(url: String?): String? =
        url?.substringBefore('?')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
}
