package studio.eugenezakharov.opencode.api.models

/**
 * A file staged for a prompt, sent as a `file` part. Either an image (base64
 * data URL) or a repo file attached as context (with `sourcePath`/`sourceContent`
 * so the agent sees the text inline, mirroring the web).
 */
data class PromptAttachment(
    val mime: String,
    val filename: String,
    val url: String,
    val sourcePath: String? = null,
    val sourceContent: String? = null,
)
