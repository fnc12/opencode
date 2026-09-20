package studio.eugenezakharov.opencode

import studio.eugenezakharov.opencode.api.ComposerPrefs

/** Shared in-memory [ComposerPrefs] so ViewModel/screen tests need no Context. */
internal class FakeComposerPrefs(
    override var providerID: String = "",
    override var modelID: String = "",
    override var agent: String = "build",
) : ComposerPrefs {
    override fun setModel(providerID: String, modelID: String) {
        this.providerID = providerID; this.modelID = modelID
    }
}
