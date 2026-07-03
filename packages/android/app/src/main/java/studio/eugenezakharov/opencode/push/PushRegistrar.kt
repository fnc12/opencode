package studio.eugenezakharov.opencode.push

/**
 * Bridges the FCM token (delivered by [ShubatMessagingService], which the system
 * owns and can't see the app's connection) to whoever registers it with the
 * relay. Mirrors iOS's PushManager.
 */
object PushRegistrar {
    @Volatile
    var lastToken: String? = null
        private set

    /** Set by the app; invoked whenever a token arrives. */
    @Volatile
    var onToken: ((String) -> Unit)? = null

    fun deliver(token: String) {
        lastToken = token
        onToken?.invoke(token)
    }
}
