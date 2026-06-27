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
}
