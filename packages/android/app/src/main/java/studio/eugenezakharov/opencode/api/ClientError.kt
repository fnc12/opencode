package studio.eugenezakharov.opencode.api

sealed class ClientError(message: String) : Exception(message) {
    data object InvalidURL : ClientError("Invalid server URL")
    data class Http(val code: Int) : ClientError("HTTP error: $code")
}
