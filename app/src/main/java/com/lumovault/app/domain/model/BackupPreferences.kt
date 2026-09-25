package com.lumovault.app.domain.model

/**
 * What the user has told the background pass it may do.
 *
 * One object rather than three separate flows, because every caller of these needs all of them at once and
 * deciding them together is the point: `automatic = false` means nothing else is consulted, and
 * `wifiOnly`/`chargingOnly` only mean anything to the pass that has to wait. Split into three reads, each
 * screen would build its own idea of what the settings allow, which is how a toggle drawn off becomes a
 * queue that runs anyway.
 *
 * [automatic] is PRD section 61's `backupEnabled` — the same column onboarding writes when it asks the
 * question the first time. There is deliberately no second "background backup enabled" field: two columns
 * that can disagree about one decision is a bug with a UI attached.
 */
data class BackupPreferences(
    val automatic: Boolean,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
) {
    companion object {
        /**
         * What a build with no settings row assumes.
         *
         * Backup off, and the conservative network behaviour: an app that has never been asked cannot
         * upload, and an app that has not been told otherwise should not spend mobile data.
         */
        val Default = BackupPreferences(automatic = false, wifiOnly = true, chargingOnly = false)
    }
}
