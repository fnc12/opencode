package studio.eugenezakharov.opencode.api.models

import kotlinx.serialization.Serializable

/** One way to authenticate a provider (`GET /provider/auth`). Mirrors iOS. */
@Serializable
data class ProviderAuthMethod(
    val type: String,      // "api" | "oauth" | "wellknown"
    val label: String = "",
)
