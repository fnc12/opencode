package studio.eugenezakharov.opencode.api.models

/** An image staged for a prompt: sent as a `file` part with a base64 data URL. */
data class PromptAttachment(
    val mime: String,
    val filename: String,
    val url: String,
)
