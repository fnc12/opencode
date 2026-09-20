package studio.eugenezakharov.opencode.ui

/**
 * Process-wide flags set from instrumented-test launch intents (mirrors the iOS
 * `ProcessInfo.arguments` checks). Production launches leave these at their
 * defaults, so they have no effect outside tests.
 */
object UiTestFlags {
    /** When true, an injected synthetic permission seeds the dock (UITEST_PERMISSION). */
    @Volatile
    var injectPermission: Boolean = false

    /** When true, an injected synthetic question seeds the dock (UITEST_QUESTION). */
    @Volatile
    var injectQuestion: Boolean = false

    /** When true, synthetic todos seed the panel (UITEST_TODO). */
    @Volatile
    var injectTodo: Boolean = false

    /** Raw `/question` JSON array to inject as the pending question — lets tests
     *  reproduce layout bugs with real captured payloads (UITEST_QUESTION_JSON). */
    @Volatile
    var injectQuestionJson: String? = null

    /** When true, `SessionDestination` builds its `SessionViewModel` with the live
     *  SSE loop disabled. The nav-flow integration test navigates into a session
     *  but must not leave an infinite reconnect stream running (a viewModelScope
     *  created via `remember{}` is never cleared), which would leak coroutines
     *  across later tests in the same process. No effect in production. */
    @Volatile
    var disableStream: Boolean = false
}
