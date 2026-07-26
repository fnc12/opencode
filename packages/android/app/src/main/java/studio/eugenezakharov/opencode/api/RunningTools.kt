package studio.eugenezakharov.opencode.api

import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent

/** A tool currently executing inside an unfinished assistant turn. */
data class RunningTool(
    val id: String,
    val name: String,
    val title: String?,
    val startedMs: Double?,
)

/**
 * Extracts the live "background processes" from the conversation: tool parts
 * with `status == running` in assistant messages that haven't completed. Long
 * tools (a docker pull, a firmware build) produce no chat text for minutes —
 * without this the session reads as stuck. Mirrors iOS `RunningTools`.
 */
object RunningTools {
    fun extract(messages: List<MessageWithParts>): List<RunningTool> {
        val out = mutableListOf<RunningTool>()
        for (m in messages) {
            val info = m.info as? MessageInfo.Assistant ?: continue
            if (info.completed != null) continue
            for (part in m.parts) {
                val tool = part.content as? PartContent.Tool ?: continue
                if (tool.status != "running") continue
                out.add(
                    RunningTool(
                        id = part.id,
                        name = tool.tool,
                        title = tool.title ?: tool.input["command"] ?: tool.input["filePath"],
                        startedMs = tool.timeStart,
                    ),
                )
            }
        }
        return out
    }
}
